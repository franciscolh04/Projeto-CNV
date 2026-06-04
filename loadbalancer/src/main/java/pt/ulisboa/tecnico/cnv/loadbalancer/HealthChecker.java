package pt.ulisboa.tecnico.cnv.loadbalancer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Health checker: Validates worker availability and extracts CPU via /ping endpoint.
 */
public class HealthChecker implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(HealthChecker.class.getName());
    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int READ_TIMEOUT_MS = 2000;
    private static final int WORKER_PORT = 8000;
    private static final int MAX_STRIKES = 5;
    
    private final HttpClient httpClient;
    private final AutoScaler autoScaler;

    public HealthChecker(AutoScaler autoScaler) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .build();
        this.autoScaler = autoScaler;
    }

    @Override
    public void run() {
        for (WorkerNode node : LoadBalancer.activeWorkers.values()) {
            checkWorkerHealthAsync(node);
        }
    }

    private void checkWorkerHealthAsync(WorkerNode node) {
        try {
            String url = "http://" + node.getIp() + ":" + WORKER_PORT + "/ping";
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                    .GET()
                    .build();
            
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, throwable) -> {
                    if (throwable == null && response.statusCode() == 200) {
                        node.setMissedPings(0);
                        response.headers().firstValue("X-CPU-Utilization").ifPresent(cpuStr -> {
                            try {
                                node.setCpuUtilization(Double.parseDouble(cpuStr));
                            } catch (NumberFormatException e) {
                                LOGGER.fine("[HC] Invalid CPU header");
                            }
                        });
                    } else {
                        handleWorkerFailure(node);
                    }
                });
        } catch (Exception e) {
            // Assume max CPU if we can't reach the worker
            node.setCpuUtilization(100.0);
            LOGGER.fine("[HC] Health check failed for " + node.getIp());
        }
    }

    private void handleWorkerFailure(WorkerNode node) {
        node.incrementMissedPings();
        
        int strikes = node.getMissedPings();
        if (strikes >= MAX_STRIKES) {
            LoadBalancer.activeWorkers.remove(node.getIp());
            
            if (LoadBalancer.activeWorkers.isEmpty() && LoadBalancer.autoScaler != null) {
                LoadBalancer.autoScaler.requestEvaluation();
            }
            if (!autoScaler.drainingWorkers.contains(node.getIp())) {
                terminateInstanceAsync(node.getInstanceId(), node.getIp());
            }
        }
    }
    

    private void terminateInstanceAsync(String instanceId, String ip) {
        CompletableFuture.runAsync(() -> {
            try {
                autoScaler.launchInstanceManager.terminateInstanceByIp(ip);
                LOGGER.log(Level.INFO, "[HC] Background termination completed for instance " + instanceId);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error during background termination of instance " + instanceId, e);
            }
        });
    }
}