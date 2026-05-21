package pt.ulisboa.tecnico.cnv.loadBalancer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * HTTP handler implementing load balancing logic.
 * Routes client requests to healthy worker instances via reverse proxy.
 * 
 * Request Strategy:
 * - Uses HttpClient consistently with separate connect and read timeouts
 * - Connect timeout: 5s (fail fast on unreachable workers)
 * - Read timeout: 30s (allow workload processing time)
 * - Retry logic: if worker disappears from activeWorkers, retry once on another worker
 * - Response forwarding: sends worker response directly to client
 */
public class LoadBalancerHandler implements HttpHandler {
    private static final Logger LOGGER = Logger.getLogger(LoadBalancerHandler.class.getName());
    
    // Separate timeouts: connect quickly, but wait for heavy workloads
    private static final long CONNECT_TIMEOUT_MS = 5000;   // 5 seconds to establish connection    
    private static final int WORKER_PORT = 8000;
    private static final int MAX_RETRIES = 1;
    
    private final HttpClient httpClient;

    public LoadBalancerHandler() {
        // Configure HttpClient with separate connect timeout
        // Read timeout is handled per-request
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .build();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String path = exchange.getRequestURI().getPath();

        int estimatedWork = estimateWork(exchange);

        // Retry logic: try current worker, then one more if it fails
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            WorkerNode targetNode = getSuitableServer();
            if (targetNode == null) {
                sendResponse(exchange, 503, "Service Unavailable: No workers alive.");
                return;
            }

            targetNode.getWork().addAndGet(estimatedWork);
            String workerIp = targetNode.getIp();

            try {
                String responseBody = forwardRequest(workerIp, path, query);
                
                // Check if worker still exists in activeWorkers (didn't timeout/fail)
                if (LoadBalancer.activeWorkers.containsKey(workerIp)) {
                    sendResponse(exchange, 200, responseBody);
                    return;
                } else {
                    // Worker disappeared during processing, will retry
                    LOGGER.log(Level.WARNING, "Worker " + workerIp + " became unavailable during request");
                }
                
            } catch (java.net.http.HttpTimeoutException | java.net.ConnectException e) {
                LOGGER.log(Level.WARNING, "Worker " + workerIp + " timeout/connection failed on attempt " + (attempt + 1), e);
                // TODO: Consider marking worker as unhealthy immediately on connect timeout, and let HealthChecker handle removal on repeated failures
                // Will retry on next iteration
                
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Worker " + workerIp + " error: " + e.getMessage(), e);
                // Will retry on next iteration
                
            } finally {
                targetNode.getWork().addAndGet(-estimatedWork);
            }
        }

        // All retries exhausted
        sendResponse(exchange, 502, "Bad Gateway: Unable to reach any worker node.");
    }

    /**
     * Forwards request to worker node with dual timeout strategy:
     * No timeout since health checker will terminate unresponsive workers, but catches timeout exceptions to trigger retries.
     */
    private String forwardRequest(String workerIp, String path, String query) throws Exception {
        String url = "http://" + workerIp + ":" + WORKER_PORT + path;
        if (query != null && !query.isEmpty()) {
            url += "?" + query;
        }
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        
        HttpResponse<String> response = httpClient.send(request, 
                HttpResponse.BodyHandlers.ofString());
        
        response.headers().firstValue("X-Request-Cost").ifPresent(costStr -> {
            try {
                // Divide raw cost by 1 million to convert to "units" for our model, ensuring at least 1 unit, preventing integer overflow, and store in cache
                long rawCost = Long.parseLong(costStr);
                int realCost = (int) Math.max(1L, rawCost / 1_000_000L);
                
                String cacheKey = path; 
                if (query != null && !query.isEmpty()) {
                     cacheKey += "?" + query;
                }
                
                LoadBalancer.metricsModelCache.put(cacheKey, realCost);
                LOGGER.info("[Metrics] Learned new cost for " + cacheKey + " -> " + rawCost + " ops -> " + realCost + " units");
                
            } catch (NumberFormatException e) {
                LOGGER.warning("Invalid format for X-Request-Cost header received.");
            }
        });
        
        return response.body();
    }

    /**
     * Estimates the work required for a request based on its path and query parameters.
     * @param exchange The HTTP exchange object.
     * @return The estimated work.
     */
    private int estimateWork(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        
        String cacheKey = path;
        if (query != null && !query.isEmpty()) {
             cacheKey += "?" + query;
        }

        Integer learnedCost = LoadBalancer.metricsModelCache.get(cacheKey);
        if (learnedCost != null) {
            LOGGER.info("[Estimation] Cache hit for " + cacheKey + ". Cost: " + learnedCost);
            return learnedCost;
        }

        // --- HEURISTIC MODEL ---
        try {
            java.util.Map<String, String> params = new java.util.HashMap<>();
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] entry = param.split("=");
                    if (entry.length > 1) params.put(entry[0], entry[1]);
                }
            }

            if (path.contains("fractal")) {
                long w = Long.parseLong(params.getOrDefault("w", "1"));
                long h = Long.parseLong(params.getOrDefault("h", "1"));
                long rawCost = w * h * 1550L;
                int cost = (int) Math.max(1L, rawCost / 1_000_000L);
                LOGGER.info("[Estimation/Heuristic] FRACTAL " + cacheKey + " -> Raw: " + rawCost + " Units: " + cost);
                return cost;
            } else if (path.contains("grayscott")) {
                long s = Long.parseLong(params.getOrDefault("size", "1"));
                long n = Long.parseLong(params.getOrDefault("maxIterations", "1"));
                long rawCost = s * s * n * 273L;
                int cost = (int) Math.max(1L, rawCost / 1_000_000L);
                LOGGER.info("[Estimation/Heuristic] GRAYSCOTT " + cacheKey + " -> Raw: " + rawCost + " Units: " + cost);
                return cost;
            } else if (path.contains("dna")) {
                // seq lengths
                String s1 = params.getOrDefault("seq1", "");
                String s2 = params.getOrDefault("seq2", "");
                long rawCost = (long) s1.length() * (long) s2.length() * 16L;
                int cost = (int) Math.max(1L, rawCost / 1_000_000L);
                LOGGER.info("[Estimation/Heuristic] DNA " + cacheKey + " -> Raw: " + rawCost + " Units: " + cost);
                return cost;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[Estimation] Error calculating heuristic", e);
        }

        LOGGER.info("[Estimation] Unknown request " + cacheKey + ". Using default.");
        return 10; // 10 units = 10,000,000 instructions default
    }

    /**
     * Selects worker with least current work (load balancing strategy).
     * Returns null if no healthy workers available.
     */
    private WorkerNode getSuitableServer() {
        WorkerNode bestNode = null;
        int minWork = Integer.MAX_VALUE;

        for (WorkerNode node : LoadBalancer.activeWorkers.values()) {
            int currentWork = node.getWork().get();
            if (currentWork < minWork) {
                minWork = currentWork;
                bestNode = node;
            }
        }
        return bestNode;
    }

    /**
     * Sends response following same pattern as RootHandler.
     * Adds CORS headers and sets Content-Type as in RootHandler.
     */
    private void sendResponse(HttpExchange exchange, int statusCode, String responseBody) 
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        
        byte[] responseBytes = responseBody.getBytes("UTF-8");
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        
        OutputStream os = exchange.getResponseBody();
        os.write(responseBytes);
        os.close();
    }
}
