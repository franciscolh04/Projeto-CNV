package pt.ulisboa.tecnico.cnv.loadbalancer;

import java.util.logging.Logger;

import software.amazon.awssdk.services.ec2.endpoints.internal.Value.Str;

import java.util.logging.Level;
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
public class AutoScaler implements Runnable {
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

    private final LaunchInstance launchInstanceManager;
    private final String masterIp;
    private long lastScaleOperation = 0;
    private final ConcurrentHashMap<String, DrainingNode> drainingWorkers = new ConcurrentHashMap<>();

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

    @Override
    public void run() {
        try {
            // Clean up draining workers that have completed draining
            cleanupDrainingWorkers();

            int totalWorkers = LoadBalancer.activeWorkers.size();
            long timeSinceLastScale = System.currentTimeMillis() - lastScaleOperation;

            // Edge case: no active workers, scale out immediately
            if (totalWorkers == 0) {
                if (lastScaleOperation == 0 || timeSinceLastScale > COOLDOWN_MS) {
                    scaleOut();
                    lastScaleOperation = System.currentTimeMillis();
                }
                return;
            }

            // Calculate averages
            double avgWork = calculateAverageWork();
            double avgCpuPercent = calculateAverageCpuUtilization();
            double weightedScore = calculateClusterWeightedScore();

            LOGGER.info("[AS] Cluster Status: workers=" + totalWorkers + " avgWork=" + 
                String.format("%.0f", avgWork) + " avgCPU=" + String.format("%.1f", avgCpuPercent) + 
                "% score=" + String.format("%.3f", weightedScore) + " timeSinceScale=" + timeSinceLastScale + "ms");

            // Scale decisions (cooldown is checked inside)
            if (shouldScaleUp(totalWorkers, timeSinceLastScale, weightedScore)) {
                scaleOut();
                lastScaleOperation = System.currentTimeMillis();
            } else if (shouldScaleDown(totalWorkers, timeSinceLastScale, weightedScore)) {
                String workerToRemove = findLeastLoadedWorker();
                if (workerToRemove != null) {
                    LOGGER.info("[AS] Scale down: removing worker " + workerToRemove + 
                        " (score=" + String.format("%.2f", weightedScore) + ")");
                    initiateScaleIn(workerToRemove);
                    lastScaleOperation = System.currentTimeMillis();
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error during auto-scaling", e);
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
     * Terminates a worker instance and removes it from the draining state.
     */
    private void terminateWorker(String workerIp) {
        try {
            drainingWorkers.remove(workerIp);
            launchInstanceManager.terminateInstanceByIp(workerIp);
            LOGGER.info("[AS] Worker " + workerIp + " terminated successfully");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error terminating worker " + workerIp, e);
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

    private void scaleOut() {
        try {
            LOGGER.info("[AS] Scaling up - launching new instance");
            String instanceId = launchInstanceManager.launchInstance(masterIp);
            if (instanceId == null) {
                LOGGER.warning("[AS] Failed to launch instance");
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error during scale out", e);
        }
    }
    
    /**
     * Calculates the average work across all active workers (using historical average).
     * This smooths out momentary spikes and gives a better representation of sustained load.
     */
    private double calculateAverageWork() {
        if (LoadBalancer.activeWorkers.isEmpty()) {
            return 0.0;
        }
        
        double totalAvgWork = 0.0;
        for (WorkerNode node : LoadBalancer.activeWorkers.values()) {
            totalAvgWork += node.getAverageWork();
        }
        
        return totalAvgWork / LoadBalancer.activeWorkers.size();
    }

    /**
     * Calculates the average CPU utilization across all active workers (using historical average).
     * Uses time-windowed average instead of latest value to smooth out spikes.
     * 
     * @return percentage (0-100), or 0 if no active workers
     */
    private double calculateAverageCpuUtilization() {
        if (LoadBalancer.activeWorkers.isEmpty()) {
            return 0.0;
        }
        
        double totalCpu = 0.0;
        for (WorkerNode node : LoadBalancer.activeWorkers.values()) {
            totalCpu += node.getAverageCpuUtilization();
        }
        
        return totalCpu / LoadBalancer.activeWorkers.size();
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
        if (LoadBalancer.activeWorkers.isEmpty()) {
            return 0.0;
        }
        
        double totalScore = 0.0;
        for (WorkerNode node : LoadBalancer.activeWorkers.values()) {
            totalScore += calculateWorkerScore(node);
        }
        
        return totalScore / LoadBalancer.activeWorkers.size();
    }

    /**
     * Determines if cluster should scale up (add workers).
     * 
     * Conditions:
     * - Cluster average score > SCALE_UP_THRESHOLD
     * - Number of workers < MAX_INSTANCES
     * - Cooldown has passed
     * - Cluster is stable (no missed pings)
     */
    private boolean shouldScaleUp(int totalWorkers, long timeSinceLastScale, double weightedScore) {
        // Check cooldown
        if (timeSinceLastScale < COOLDOWN_MS) {
            return false;
        }
        
        // Check max instance limit
        if (totalWorkers >= MAX_INSTANCES) {
            LOGGER.fine("[AS] Cannot scale up: at max instances (" + totalWorkers + ")");
            return false;
        }
        
        // Check cluster stability
        for (WorkerNode node : LoadBalancer.activeWorkers.values()) {
            if (node.getMissedPings() > 0) {
                LOGGER.fine("[AS] Cannot scale up: cluster unstable (worker " + node.getIp() + " missed pings)");
                return false;
            }
        }
        
        // Check score
        boolean shouldScale = weightedScore > SCALE_UP_THRESHOLD;
        if (shouldScale) {
            LOGGER.info("[AS] Scale up condition met: score=" + String.format("%.3f", weightedScore) + 
                " > " + SCALE_UP_THRESHOLD);
        }
        
        return shouldScale;
    }

    /**
     * Determines if cluster should scale down.
     */
    private boolean shouldScaleDown(int totalWorkers, long timeSinceLastScale, double weightedScore) {
        // Check cooldown
        if (timeSinceLastScale < COOLDOWN_MS) {
            return false;
        }
        
        // Check min instance limit
        if (totalWorkers <= MIN_INSTANCES) {
            LOGGER.fine("[AS] Cannot scale down: at min instances (" + totalWorkers + ")");
            return false;
        }

        // Check missed pings
        for (WorkerNode node : LoadBalancer.activeWorkers.values()) {
            if (node.getMissedPings() > 0) {
                LOGGER.fine("[AS] Cannot scale down: cluster unstable (worker " + node.getIp() + " missed pings)");
                return false;
            }
        }
        
        // Check score
        boolean shouldScale = weightedScore < SCALE_DOWN_THRESHOLD;
        if (shouldScale) {
            LOGGER.info("[AS] Scale down: score=" + String.format("%.3f", weightedScore) + 
                " < " + SCALE_DOWN_THRESHOLD);
        }
        
        return shouldScale;
    }
}