package pt.ulisboa.tecnico.cnv.loadBalancer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Health checker that periodically validates worker availability.
 * Uses same HttpClient pattern as LoadBalancerHandler for consistency.
 * Removes and terminates unhealthy workers to prevent zombie processes.
 */
public class HealthChecker implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(HealthChecker.class.getName());
    
    private static final long CONNECT_TIMEOUT_MS = 5000;   // 5 seconds
    private static final long READ_TIMEOUT_MS = 10000;     // 10 seconds (ping is fast)
    private static final int WORKER_PORT = 8000;
    private static final int MAX_STRIKES = 3;
    
    private final HttpClient httpClient;

    public HealthChecker() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .build();
    }

    @Override
    public void run() {
        Iterator<Map.Entry<String, WorkerNode>> it = LoadBalancer.activeWorkers.entrySet().iterator();
        
        while (it.hasNext()) {
            Map.Entry<String, WorkerNode> entry = it.next();
            WorkerNode node = entry.getValue();
            String ip = node.getIp();
            String instanceId = node.getInstanceId();
            int strikes = node.getMissedPings();

            if (isAlive(ip)) {
                node.setMissedPings(0); // Reset strikes on successful ping
                continue;
            }
            
            strikes++;
            System.out.println("[HealthChecker] Worker " + node.getIp() + " missed ping (" + strikes + "/" + MAX_STRIKES + ")");

            if (strikes >= MAX_STRIKES) {
                System.out.println("[HealthChecker] Worker " + node.getIp() + " is dead. Removing...");
                it.remove(); 

                // Terminate the EC2 instance to prevent zombie processes
                terminateInstance(instanceId, ip);
            }
        }
    }

    /**
     * Checks if worker is alive using ping endpoint with dual timeout strategy.
     * Uses same HttpClient pattern as LoadBalancerHandler for consistency.
     */
    private boolean isAlive(String ip) {
        try {
            String url = "http://" + ip + ":" + WORKER_PORT + "/ping";
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                    .GET()
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, 
                    HttpResponse.BodyHandlers.ofString());
            
            return response.statusCode() == 200;
            
        } catch (java.net.http.HttpTimeoutException | java.net.ConnectException e) {
            LOGGER.log(Level.FINE, "Worker " + ip + " health check timed out or unreachable", e);
            return false;
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Worker " + ip + " health check failed: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Terminates the EC2 instance associated with this worker.
     * Prevents zombie processes from remaining after worker is removed.
     */
    private void terminateInstance(String instanceId, String ip) {
        try {
            LaunchInstance launcher = new LaunchInstance();
            launcher.terminateInstance(instanceId);
            LOGGER.log(Level.INFO, "Terminated instance " + instanceId + " for dead worker " + ip);
            launcher.close();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error terminating instance " + instanceId, e);
        }
    }
}