package pt.ulisboa.tecnico.cnv.loadbalancer;

import java.util.logging.Logger;

import java.util.logging.Level;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.ArrayList;

/**
 * Auto-scaling thread - scales EC2 instances based on Weighted Score combining:
 * - CPU Utilization (weight W1 = 0.4)
 * - Relative Work (weight W2 = 0.6)
 * 
 * Score = (W1 * avgCPU) + (W2 * currentWork/maxCapacity)
 * 
     * Scale up/down decisions are smoothed with averaging to avoid oscillations.
 */
public class AutoScaler extends Thread {
    private static final Logger LOGGER = Logger.getLogger(AutoScaler.class.getName());
    
    // Scale thresholds based on Weighted Score (0.0 to 1.0+)
    private static final double SCALE_UP_THRESHOLD = 0.7;    // Scale up when average score > 0.7
    private static final double SCALE_DOWN_THRESHOLD = 0.3;  // Scale down when average score < 0.3
    
    // Weighted Score weights
    private static final double WEIGHT_CPU = 0.4;           // CPU contribution (40%)
    private static final double WEIGHT_RELATIVE_WORK = 0.6; // Relative work contribution (60%)
    
    // Instance limits
    private static final int MIN_INSTANCES = 1;
    private static final int MAX_INSTANCES = 10;
    private static final long COOLDOWN_MS = 120000;         // 2 minutes between scale operations
    private static final long DRAIN_TIMEOUT_MS = 60000;     // 60 seconds timeout for draining

    final LaunchInstance launchInstanceManager;
    private final String masterIp;
    private long lastScaleOperation = 0;
    final ConcurrentHashMap<String, DrainingNode> drainingWorkers = new ConcurrentHashMap<>();

    private final Object monitor = new Object();
    private static final ConcurrentHashMap<String, Long> warmingUpInstances = new ConcurrentHashMap<>();
    private static final long WARMUP_TIMEOUT_MS = 300000; // 5 minutes

    private static final int QUEUE_SIZE_THRESHOLD = 5;

    public AutoScaler(String masterIP) {
        this.launchInstanceManager = new LaunchInstance();
        this.masterIp = masterIP;
    }

    /**
     * Inner class to track workers in draining state with their drain timestamp.
     */
    private static class DrainingNode {
        final WorkerNode workerNode;
        final long drainStartTime;

        DrainingNode(WorkerNode workerNode, long drainStartTime) {
            this.workerNode = workerNode;
            this.drainStartTime = drainStartTime;
        }

        /**
         * Checks if this draining worker can be terminated.
         * Returns true if work is 0 or if drain timeout has been exceeded.
         */
        boolean canTerminate() {
            int currentWork = workerNode.getWork().get();
            long elapsed = System.currentTimeMillis() - drainStartTime;
            return currentWork == 0 || elapsed > DRAIN_TIMEOUT_MS;
        }
    }

    /*
     * Method to trigger re-evaluation of cluster state
    */
    public void requestEvaluation() {
        synchronized (monitor) {
            monitor.notify();
            LOGGER.info("[AS] Wake up signal received. Re-evaluating cluster state...");
        }
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                synchronized (monitor) {
                    // Wait for either a wake-up signal or a timeout to perform periodic evaluation
                    monitor.wait(15000); 
                }
                
                evaluateClusterState();
                
            } catch (InterruptedException e) {
                LOGGER.info("[AS] AutoScaler thread interrupted.");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error during auto-scaling evaluation", e);
            }
        }
    }

    private void evaluateClusterState() {
        cleanupDrainingWorkers();
        cleanupStaleWarmingUpInstances();

        int totalActiveWorkers = LoadBalancer.activeWorkers.size();
        int totalWarmingUp = warmingUpInstances.size();

        // Edge case extremo: zero workers e zero a iniciar
        if (totalActiveWorkers == 0 && totalWarmingUp == 0) {
            executeScaleOutAsync(1);
            return;
        }
        
        long currentCapacity = getClusterTotalCapacity();
        long averageCapacity = getAverageNodeCapacity();
        
        // Project capacity: current active capacity + estimated capacity from warming up instances
        long projectedCapacity = currentCapacity + (totalWarmingUp * averageCapacity);
        long totalDemand = getTotalDemand();
        double avgScore = calculateClusterWeightedScore();

        LOGGER.info("[AS] Status: active=" + totalActiveWorkers + 
            " | warmingUp=" + totalWarmingUp +
            " | demand=" + totalDemand + "/" + projectedCapacity + 
            " | score=" + String.format("%.3f", avgScore) + 
            " | queue=" + LoadBalancerHandler.pendingQueue.size());

        long timeSinceLastScale = System.currentTimeMillis() - lastScaleOperation;
        if (timeSinceLastScale > COOLDOWN_MS) {
            // Usamos a projectedCapacity para tomar a decisão de scale up
            if (shouldScaleUp(totalDemand, projectedCapacity)) {
                processScaleUp(totalDemand, projectedCapacity, totalActiveWorkers + totalWarmingUp);
            } 
            else if (shouldScaleDown(totalDemand, currentCapacity, avgScore, totalActiveWorkers)) {
                processScaleDown(totalDemand, avgScore);
            }
        }
    }

    private void initiateScaleIn(String workerIp) {
        try {
            WorkerNode workerNode = LoadBalancer.activeWorkers.remove(workerIp);
            if (workerNode != null) {
                // Move worker to draining state instead of terminating immediately
                drainingWorkers.put(workerIp, new DrainingNode(workerNode, System.currentTimeMillis()));
                LOGGER.info("[AS] Worker " + workerIp + " moved to draining state");
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error initiating scale in for worker " + workerIp, e);
        }
    }

    /**
     * Cleans up draining workers that have satisfied drain conditions.
     * Terminates workers when either:
     * - Their work is 0 (all tasks completed)
     * - Or drain timeout has been exceeded (forced termination)
     */
    private void cleanupDrainingWorkers() {
        List<String> workersToTerminate = new ArrayList<>();
        
        for (String workerIp : drainingWorkers.keySet()) {
            DrainingNode drainingNode = drainingWorkers.get(workerIp);
            if (drainingNode != null && drainingNode.canTerminate()) {
                workersToTerminate.add(workerIp);
            }
        }
        
        for (String workerIp : workersToTerminate) {
            terminateWorker(workerIp);
        }
    }

    /**
     * Terminates a worker instance and removes it from the draining state asynchronously.
     */
    private void terminateWorker(String workerIp) {
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    launchInstanceManager.terminateInstanceByIp(workerIp);
                    LOGGER.info("[AS] Worker " + workerIp + " async termination requested to AWS successfully.");
                    drainingWorkers.remove(workerIp);
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Error during async termination of worker " + workerIp, e);
                }
            });
            
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error preparing termination for worker " + workerIp, e);
        }
    }

    private String findLeastLoadedWorker() {
        String leastLoaded = null;
        int minWork = Integer.MAX_VALUE;
        
        for (String ip : LoadBalancer.activeWorkers.keySet()) {
            WorkerNode worker = LoadBalancer.activeWorkers.get(ip);
            if (worker != null) {
                int work = worker.getWork().get();
                if (work < minWork) {
                    minWork = work;
                    leastLoaded = ip;
                }
            }
        }
        
        return leastLoaded;
    }

    /**
     * Calculates the Weighted Score for an individual worker using historical averages.
     * 
     * Score = (WEIGHT_CPU * avg_CPU%) + (WEIGHT_RELATIVE_WORK * avg_RelativeWork)
     * 
     * This provides smoother scaling decisions based on sustained metrics, not momentary peaks.
     * 
     * @param worker the WorkerNode to evaluate
     * @return score between 0 and 1+
     */
    private double calculateWorkerScore(WorkerNode worker) {
        // Use historical average CPU (0-100) normalized to 0-1
        double cpuNormalized = worker.getAverageCpuUtilization() / 100.0;
        
        // Use historical average relative work
        double relativeWork = worker.getAverageRelativeWork();
        
        // Weighted score
        double score = (WEIGHT_CPU * cpuNormalized) + (WEIGHT_RELATIVE_WORK * relativeWork);
        
        return score;
    }

    /**
     * Calculates the average Weighted Score of the entire cluster.
     * 
     * @return average score across all workers
     */
    private double calculateClusterWeightedScore() {
        int currentSize = LoadBalancer.activeWorkers.size();
        if (currentSize == 0) return 0.0;

        double totalScore = 0.0;
        for (WorkerNode node : LoadBalancer.activeWorkers.values()) {
            totalScore += calculateWorkerScore(node);
        }

        return totalScore / currentSize;
    }
    
    private static long getClusterTotalCapacity() {
        long capacity = 0;
        for (WorkerNode node : LoadBalancer.activeWorkers.values()) {
            capacity += node.getMaxCapacity();
        }
        return capacity;
    }

    private long getTotalDemand() {
        long currentWork = 0;
        for (WorkerNode node : LoadBalancer.activeWorkers.values()) {
            currentWork += node.getWork().get();
        }
        LOGGER.fine("[AS] Current cluster work: " + currentWork);
        
        long queueWork = 0;
        for (Job job : LoadBalancerHandler.pendingQueue) {
            queueWork += job.estimatedWork;
        }
        LOGGER.fine("[AS] Pending queue work: " + queueWork);
        
        return currentWork + queueWork;
    }

    /**
     * Determines if cluster should scale up.
     */
    private boolean shouldScaleUp(long totalDemand, long clusterCapacity) {
        long idealCapacityThreshold = (long) (clusterCapacity * SCALE_UP_THRESHOLD);
        return totalDemand > idealCapacityThreshold;
    }

    private void processScaleUp(long totalDemand, long projectedCapacity, int projectedTotalWorkers) {
        if (projectedTotalWorkers >= MAX_INSTANCES) {
            LOGGER.fine("[AS] Cannot scale up: MAX_INSTANCES reached (including warming up).");
            return;
        }

        long idealCapacityThreshold = (long) (projectedCapacity * SCALE_UP_THRESHOLD);
        long excessWork = totalDemand - idealCapacityThreshold;
        
        long averageCapacity = getAverageNodeCapacity();
        long comfortableCapacityPerMachine = (long) (averageCapacity * SCALE_UP_THRESHOLD);
        
        int instancesNeeded = (int) Math.ceil((double) excessWork / Math.max(1, comfortableCapacityPerMachine));   
        int instancesToLaunch = Math.min(instancesNeeded, 2);
        instancesToLaunch = Math.min(instancesToLaunch, MAX_INSTANCES - projectedTotalWorkers);
        
        if (instancesToLaunch > 0) {
            LOGGER.info("[AS] Scaling out. Launching " + instancesToLaunch + " async instances.");
            executeScaleOutAsync(instancesToLaunch);
        }
    }

    private void executeScaleOutAsync(int count) {
        lastScaleOperation = System.currentTimeMillis();
        LOGGER.info("[AS] Calling AWS API to launch " + count + " instances...");

        List<String> launchedIds = launchInstanceManager.launchInstances(masterIp, count);
        
        if (launchedIds == null || launchedIds.isEmpty()) {
            LOGGER.warning("[AS] AWS failed to launch instances. Capacity reservation aborted.");
            return;
        } else {
            LOGGER.info("[AS] Successfully requested " + launchedIds.size() + " instances.");
        }

        long currentTime = System.currentTimeMillis();
        for (String instanceId : launchedIds) {
            synchronized (LoadBalancer.clusterStateLock) {
                boolean isAlreadyActive = false;
                for (WorkerNode node : LoadBalancer.activeWorkers.values()) {
                    if (instanceId.equals(node.getInstanceId())) {
                        isAlreadyActive = true;
                        break;
                    }
                }

                if (!isAlreadyActive) {
                    warmingUpInstances.put(instanceId, currentTime);
                } else {
                    LOGGER.info("[AS] Instance " + instanceId + " bypassed warmingUp!");
                }
            }
        }

        requestEvaluation();
    }

    /**
     * Determines if cluster should scale down.
     */
    private boolean shouldScaleDown(long totalDemand, long clusterCapacity, double avgScore, int totalWorkers) {
        if (totalWorkers <= MIN_INSTANCES) return false;
        
        if (LoadBalancerHandler.pendingQueue.size() >= QUEUE_SIZE_THRESHOLD) return false;

        // Block scale down if cluster is unstable
        for (WorkerNode node : LoadBalancer.activeWorkers.values()) {
            if (node.getMissedPings() > 0) {
                LOGGER.fine("[AS] Scale down aborted: Cluster is unstable (missed pings detected).");
                return false;
            }
        }

        long averageCapacity = getAverageNodeCapacity();
        long capacityWithOneLess = clusterCapacity - averageCapacity;
        long safeCapacityThreshold = (long) (capacityWithOneLess * SCALE_DOWN_THRESHOLD);

        return avgScore < SCALE_DOWN_THRESHOLD && totalDemand <= safeCapacityThreshold;
    }

    private void processScaleDown(long totalDemand, double avgScore) {
        int totalWorkers = LoadBalancer.activeWorkers.size();
        boolean nodeRemoved = true;
        
        // Iteratively remove least loaded workers while conditions are met and we haven't hit minimum instance count
        while (totalWorkers > MIN_INSTANCES && nodeRemoved) {
            nodeRemoved = false;
            long clusterCapacity = getClusterTotalCapacity();
            long averageCapacity = getAverageNodeCapacity();
            long safeCapacityThreshold = (long) ((clusterCapacity - averageCapacity) * SCALE_DOWN_THRESHOLD);

            if (avgScore < SCALE_DOWN_THRESHOLD && totalDemand <= safeCapacityThreshold) {
                String workerToRemove = findLeastLoadedWorker();
                if (workerToRemove != null) {
                    LOGGER.info("[AS] Safe conditions met. Removing instance: " + workerToRemove);
                    initiateScaleIn(workerToRemove);
                    totalWorkers--;
                    nodeRemoved = true;
                }
            }
        }
        
        if (totalWorkers < LoadBalancer.activeWorkers.size()) {
            lastScaleOperation = System.currentTimeMillis();
        }
    }

    public static long getAverageNodeCapacity() {
        int totalWorkers = LoadBalancer.activeWorkers.size();
        if (totalWorkers == 0) return WorkerNode.DEFAULT_MAX_CAPACITY;
        return getClusterTotalCapacity() / totalWorkers;
    }

    private void cleanupStaleWarmingUpInstances() {
        long now = System.currentTimeMillis();
        warmingUpInstances.entrySet().removeIf(entry -> {
            boolean expired = (now - entry.getValue()) > WARMUP_TIMEOUT_MS;
            if (expired) {
                launchInstanceManager.terminateInstance(entry.getKey());
            }
            return expired;
        });
    }

    public void removeWarmingUpInstance(String instanceId) {
        if (instanceId != null) {
            if (warmingUpInstances.remove(instanceId) != null) {
                LOGGER.info("[AS] Instance " + instanceId + " successfully registered and removed from warmingUp.");
            }
        }   
    }
}