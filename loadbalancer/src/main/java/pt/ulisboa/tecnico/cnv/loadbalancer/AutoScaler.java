package pt.ulisboa.tecnico.cnv.loadbalancer;

import java.util.logging.Logger;

import software.amazon.awssdk.services.ec2.endpoints.internal.Value.Str;

import java.util.logging.Level;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.ArrayList;

/**
 * Auto-scaling thread - scales EC2 instances based on average worker load.
 */
public class AutoScaler implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(AutoScaler.class.getName());
    private static final double MAX_WORK_THRESHOLD = 150000.0; // ~150B instructions
    private static final double MIN_WORK_THRESHOLD = 20000.0; // ~20B instructions
    private static final int MIN_INSTANCES = 1;
    private static final int MAX_INSTANCES = 10;
    private static final long COOLDOWN_MS = 120000;
    private static final long DRAIN_TIMEOUT_MS = 60000; // 60 seconds timeout for draining

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

            if (totalWorkers == 0){
                // Scales out immediately if cooldown has passed and there are no active workers
                if (lastScaleOperation == 0 || timeSinceLastScale > COOLDOWN_MS) {
                    scaleUp();
                    lastScaleOperation = System.currentTimeMillis();
                }
                return;
            };

            int totalWork = 0;
            for (WorkerNode node : LoadBalancer.activeWorkers.values()) {
                totalWork += node.getWork().get();
            }
            
            double avgWork = (double) totalWork / totalWorkers;

            // Scale up: average work above threshold, under max instances, and cooldown passed
            if (avgWork > MAX_WORK_THRESHOLD && totalWorkers < MAX_INSTANCES && timeSinceLastScale > COOLDOWN_MS) {
                scaleUp();
                lastScaleOperation = System.currentTimeMillis();
            } 
            // Scale down: 3 min grace period passed and work below minimum threshold
            else if (timeSinceLastScale >= COOLDOWN_MS && avgWork < MIN_WORK_THRESHOLD && 
                     totalWorkers > MIN_INSTANCES) {

                boolean isClusterStable = true;
                for (WorkerNode node : LoadBalancer.activeWorkers.values()) {
                    if (node.getMissedPings() > 0) {
                        isClusterStable = false;
                        break;
                    }
                }

                if (!isClusterStable) {
                    LOGGER.info("[AS] Cluster is not stable (some workers missed pings). Skipping scale down.");
                    return;
                }

                String workerToRemove = findLeastLoadedWorker();
                if (workerToRemove != null) {
                    initiateScaleDown(workerToRemove);
                    lastScaleOperation = System.currentTimeMillis();
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error during auto-scaling", e);
        }
    }

    private void initiateScaleDown(String workerIp) {
        try {
            WorkerNode workerNode = LoadBalancer.activeWorkers.remove(workerIp);
            if (workerNode != null) {
                // Move worker to draining state instead of terminating immediately
                drainingWorkers.put(workerIp, new DrainingNode(workerNode, System.currentTimeMillis()));
                LOGGER.info("[AS] Worker " + workerIp + " moved to draining state");
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error initiating scale down for worker " + workerIp, e);
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

    private void scaleUp() {
        try {
            String instanceId = launchInstanceManager.launchInstance(masterIp);
            if (instanceId == null) {
                LOGGER.warning("[AS] Failed to launch instance");
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error during scale up", e);
        }
    }
}