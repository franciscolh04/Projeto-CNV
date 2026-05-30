package pt.ulisboa.tecnico.cnv.loadbalancer;

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
 * Health checker: Validates worker availability and extracts CPU via /ping endpoint.
 */
public class HealthChecker implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(HealthChecker.class.getName());
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;
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

            if (isAlive(node)) {
                System.out.println("[HC] Worker " + node.getIp() + " is alive (CPU: " + 
                    String.format("%.2f", node.getCpuUtilization()) + "%).");
                node.setMissedPings(0); // Reset strikes on successful ping
                continue;
            }
            
            int strikes = node.getMissedPings() + 1;
            node.setMissedPings(strikes);
            LOGGER.info("[HC] Worker " + node.getIp() + " missed ping (" + strikes + "/" + MAX_STRIKES + ")");

            if (strikes >= MAX_STRIKES) {
                LOGGER.info("[HC] Removing dead worker " + node.getIp());
                it.remove();
                terminateInstance(node.getInstanceId(), node.getIp());
            }
        }
    }

    // Extract CPU metric from X-CPU-Utilization header and record in WorkerNode history
    private boolean isAlive(WorkerNode node) {
        try {
            String url = "http://" + node.getIp() + ":" + WORKER_PORT + "/ping";
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                    .GET()
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, 
                    HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                response.headers().firstValue("X-CPU-Utilization")
                    .ifPresent(cpuStr -> {
                        try {
                            node.setCpuUtilization(Double.parseDouble(cpuStr));
                        } catch (NumberFormatException e) {
                            LOGGER.fine("[HC] Invalid CPU header: " + cpuStr);
                        }
                    });
                return true;
            }
            
            return false;
            
        } catch (java.net.http.HttpTimeoutException | java.net.ConnectException e) {
            LOGGER.fine("[HC] Worker " + node.getIp() + " unreachable");
            return false;
        } catch (Exception e) {
            LOGGER.fine("[HC] Health check failed for " + node.getIp());
            return false;
        }
    }

    private void terminateInstance(String instanceId, String ip) {
        try {
            LaunchInstance launcher = new LaunchInstance();
            launcher.terminateInstance(instanceId);
            LOGGER.log(Level.INFO, "[HC] Terminated instance " + instanceId + " for dead worker " + ip);
            launcher.close();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error terminating instance " + instanceId, e);
        }
    }
}