package pt.ulisboa.tecnico.cnv.loadbalancer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.regions.Region;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * HTTP handler: Routes client requests to least-loaded healthy worker.
 */
public class LoadBalancerHandler implements HttpHandler {
    private static final Logger LOGGER = Logger.getLogger(LoadBalancerHandler.class.getName());
    private static final long CONNECT_TIMEOUT_MS = 5000;
    private static final long READ_TIMEOUT_MS = 120000;
    private static final int WORKER_PORT = 8000;
    private static final int MAX_RETRIES = 1;
    private static final int FAAS_THRESHOLD = 2_000; // TODO: Tune this
    private static final double BUSY_THRESHOLD = 0.60;
    
    private final HttpClient httpClient;

    private final LambdaClient awsLambda;

    public LoadBalancerHandler() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .build();
        this.awsLambda = LambdaClient.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
                .build();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String path = exchange.getRequestURI().getPath();
        Boolean faasSuccess = true;

        int estimatedWork = estimateWork(exchange);

        // Retry logic: try current worker, then one more if it fails
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            WorkerNode targetNode = getSuitableServer();
            if (targetNode == null) {
                if (faasSuccess && invokeFunction(exchange)) {
                    LOGGER.info("[LB] FaaS fallback succeeded for " + truncateForLogging(path));
                    return;
                }

                sendResponse(exchange, 503, "Service Unavailable: No workers alive.");
                return;
            }

            double currentInstanceLoad = targetNode.getRelativeWork(); 
            boolean isInstanceBusy = currentInstanceLoad > BUSY_THRESHOLD; 

            boolean isSmallRequest = estimatedWork <= FAAS_THRESHOLD;
            boolean shouldOffloadToFaas = isSmallRequest && isInstanceBusy;

            // Small requests can be diverted to FaaS when the selected worker is already busy.
            // Large requests stay on EC2 so they continue to reserve worker capacity for heavy jobs.
            if (faasSuccess && shouldOffloadToFaas) {
                faasSuccess = invokeFunction(exchange);
                if (faasSuccess) {
                    LOGGER.info("[LB] FaaS invocation succeeded for " + truncateForLogging(path));
                    return;
                } else {
                    faasSuccess = false;
                    LOGGER.warning("[LB] FaaS invocation failed, falling back to worker nodes.");
                }
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
                
            } catch (java.net.http.HttpTimeoutException e) {
                LOGGER.log(Level.WARNING, "Worker " + workerIp + " request timeout on attempt " + (attempt + 1) + " - may indicate slow/stuck workload", e);
                // Will retry on next iteration
                
            } catch (java.net.ConnectException e) {
                LOGGER.log(Level.WARNING, "Worker " + workerIp + " connection failed on attempt " + (attempt + 1), e);
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
     * Truncates a string for logging to prevent log pollution.
     * Returns the full string if <= MAX_LENGTH, otherwise returns first MAX_LENGTH chars + "..."
     */
    private String truncateForLogging(String str) {
        final int MAX_LENGTH = 100;
        if (str == null || str.length() <= MAX_LENGTH) {
            return str;
        }
        return str.substring(0, MAX_LENGTH) + "...";
    }

    /**
     * Properly encodes a query string to handle special characters.
     * Encodes parameter names and values while preserving the query structure.
     */
    private String encodeQueryString(String query) {
        if (query == null || query.isEmpty()) {
            return query;
        }
        
        StringBuilder encoded = new StringBuilder();
        String[] params = query.split("&");
        
        for (int i = 0; i < params.length; i++) {
            if (i > 0) encoded.append("&");
            
            String[] parts = params[i].split("=", 2);
            String key = URLEncoder.encode(parts[0], StandardCharsets.UTF_8);
            encoded.append(key);
            
            if (parts.length > 1) {
                encoded.append("=");
                String value = URLEncoder.encode(parts[1], StandardCharsets.UTF_8);
                encoded.append(value);
            }
        }
        
        return encoded.toString();
    }

    /**
     * Forwards request to worker node with separate connect and read timeouts.
     * Connect timeout: fail fast if worker is unreachable
     * Read timeout: allow heavy workloads to complete processing
     */
    private String forwardRequest(String workerIp, String path, String query) throws Exception {
        String url = "http://" + workerIp + ":" + WORKER_PORT + path;
        if (query != null && !query.isEmpty()) {
            // Properly encode query parameters to handle special characters (colons, angle brackets, etc.)
            String encodedQuery = encodeQueryString(query);
            url += "?" + encodedQuery;
        }
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                .GET()
                .build();
        
        LOGGER.info("Forwarding request to worker " + workerIp + ": " + truncateForLogging(url));
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        response.headers().firstValue("X-Request-Cost").ifPresent(costStr -> {
            try {
                long rawCost = Long.parseLong(costStr);
                
                String fullUrl = path; 
                if (query != null && !query.isEmpty()) {
                     fullUrl += "?" + query;
                }
                
                // Save recent EXACT MATCH
                LoadBalancer.recentExactCache.put(fullUrl, rawCost);
                
                // Update local Beta (EMA) immediately
                try {
                    java.util.Map<String, String> p = parseQuery(query);
                    if (path.contains("grayscott")) {
                        String seed = p.getOrDefault("seedMode", "center");
                        String ext = p.getOrDefault("stopOnExtinction", "false");
                        long s = Long.parseLong(p.getOrDefault("size", "256"));
                        long n = Long.parseLong(p.getOrDefault("maxIterations", "1000"));
                        updateLocalEMA("grayscott_" + seed + "_" + ext, rawCost, (s * s) * n);
                    } else if (path.contains("fractals")) {
                        long w = Long.parseLong(p.getOrDefault("w", "400"));
                        long h = Long.parseLong(p.getOrDefault("h", "300"));
                        updateLocalEMA("fractals", rawCost, w * h);
                    }
                } catch (Exception e) {
                    LOGGER.warning("[Metrics] Error in Fast Loop: " + e.getMessage());
                }

                LOGGER.info("[Metrics] Fast Loop registered cost for " + truncateForLogging(fullUrl) + " -> " + rawCost);
            } catch (NumberFormatException e) {
                LOGGER.warning("[LB] Invalid X-Request-Cost header");
            }
        });
        
        return response.body();
    }

    private Boolean invokeFunction(HttpExchange exchange) {
        String functionName="";
        String query = exchange.getRequestURI().getQuery();
        String path = exchange.getRequestURI().getPath();
        String json="{}";

        // TODO: Move to an auxiliar Function ?
        try {
            java.util.Map<String, String> params = parseQuery(query);

            if (path.contains("fractal")) {
                long w = Long.parseLong(params.getOrDefault("w", "1"));
                long h = Long.parseLong(params.getOrDefault("h", "1"));
                long it = Long.parseLong(params.getOrDefault("iterations", "1"));
                json = String.format("{\"w\":\"%s\",\"h\":\"%s\",\"iterations\":\"%s\"}", w, h, it);
                functionName = "fractals-lambda";
            } else if (path.contains("grayscott")) {
                long s = Long.parseLong(params.getOrDefault("size", "1"));
                long n = Long.parseLong(params.getOrDefault("maxIterations", "1"));
                double F = Double.parseDouble(params.getOrDefault("f", "0.030"));
                double K = Double.parseDouble(params.getOrDefault("k", "0.062"));
                boolean stopOnExtinction = Boolean.parseBoolean(params.getOrDefault("stopOnExtinction", "false"));
                String seedMode = params.getOrDefault("seedMode", "center");
                functionName = "grayscott-lambda";
                json=String.format("{\"size\":\"%s\",\"maxIterations\":\"%s\",\"f\":\"%s\",\"k\":\"%s\",\"stopOnExtinction\":\"%s\",\"seedMode\":\"%s\"}", s, n, F, K, stopOnExtinction, seedMode);
            } else if (path.contains("dna")) {
                // seq lengths
                String s1 = params.getOrDefault("seq1", "seq1:ATGC");
                String s2 = params.getOrDefault("seq2", "seq2:ATGC");
                String minLengthParam = params.getOrDefault("minLength", "1");
                boolean stopOnFirst = Boolean.parseBoolean(params.getOrDefault("stopOnFirst", "false"));
                functionName = "dna-lambda";
                json=String.format("{\"seq1\":\"%s\",\"seq2\":\"%s\",\"minLength\":\"%s\",\"stopOnFirst\":\"%s\"}", s1, s2, minLengthParam, stopOnFirst);
            }

            LOGGER.info("[LB] Invoking Lambda function " + functionName + " with payload: " + truncateForLogging(json));
            SdkBytes payload = SdkBytes.fromUtf8String(json) ;
            InvokeRequest request = InvokeRequest.builder().functionName(functionName).payload(payload).build();

            InvokeResponse res = awsLambda.invoke(request);

            if (res.functionError() != null) {
                String errorPayload = res.payload().asUtf8String();
                LOGGER.log(Level.SEVERE, "[FaaS Error] " + res.functionError() + " -> " + errorPayload);     
                return false;
            }

            String responseBody = res.payload().asUtf8String();
            LOGGER.info("[LB] Lambda function " + functionName + " invocation succeeded with response: " + truncateForLogging(responseBody));
            sendResponse(exchange, 200, responseBody);     
            return true;

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error invoking Lambda function", e);
            return false;
        }
   }

    // Fast Loop EMA update for accurate recent approximations
    private void updateLocalEMA(String key, long actualCost, long workUnits) {
        if (workUnits <= 0) return;
        double observedCost = (double) actualCost / workUnits;
        double currentEMA = LoadBalancer.metricsModelCache.getOrDefault(key, observedCost);
        // Alpha = 0.2
        double newEMA = (0.2 * observedCost) + (0.8 * currentEMA);
        LoadBalancer.metricsModelCache.put(key, newEMA);
    }

    /**
     * Estimates the work required for a request dynamically using continuous learning coefficients.
     */
    private int estimateWork(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        
        String cacheKey = path;
        if (query != null && !query.isEmpty()) {
             cacheKey += "?" + query;
        }

        // Check recent EXACT MATCH cache first for the full URL (including query) to capture recency and unpredictability
        Long exactRecentCost = LoadBalancer.recentExactCache.get(cacheKey);
        if (exactRecentCost != null) {
            LOGGER.info("[Estimation] EXACT MATCH RECENTE hit. Cost: " + exactRecentCost);
            return (int) Math.max(1L, exactRecentCost / 1_000_000L);
        }
        
        // If no recent exact match, proceed with heuristics based on dynamic EMA coefficients
        try {
            java.util.Map<String, String> params = parseQuery(query);

            if (path.contains("dna")) {
                // Exact match check for chaotic DNA workloads
                Double cachedCost = LoadBalancer.dnaExactCache.get(cacheKey);
                if (cachedCost != null) {
                    LOGGER.info("[Estimation] DNA exact match hit. Cost: " + cachedCost);
                    return (int) Math.max(1L, cachedCost.longValue() / 1_000_000L);
                } else {
                    String s1 = params.getOrDefault("seq1", "");
                    String s2 = params.getOrDefault("seq2", "");
                    long rawCost = (long) s1.length() * (long) s2.length() * 16L;
                    int cost = (int) Math.max(1L, rawCost / 1_000_000L);
                    LOGGER.info("[Estimation] DNA fallback heuristic -> Raw: " + rawCost + " Units: " + cost);
                    return cost;
                }
            } else if (path.contains("fractal")) {
                // Fetch dynamic EMA coefficient
                Double beta = LoadBalancer.metricsModelCache.getOrDefault("fractals", 2579.23);
                long w = Long.parseLong(params.getOrDefault("w", "1"));
                long h = Long.parseLong(params.getOrDefault("h", "1"));
                
                long rawCost = (long) (beta * (w * h));
                int cost = (int) Math.max(1L, rawCost / 1_000_000L);
                LOGGER.info("[Estimation] FRACTAL EMA (Beta=" + String.format("%.2f", beta) + ") -> Raw: " + rawCost + " Units: " + cost);
                return cost;
            } else if (path.contains("grayscott")) {
                // Extract topological params for composite key
                String seedMode = params.getOrDefault("seedMode", "center");
                String stopOnExt = params.getOrDefault("stopOnExtinction", "false");
                String modelKey = "grayscott_" + seedMode + "_" + stopOnExt;

                // Fetch dynamic EMA coefficient for specific topology
                Double beta = LoadBalancer.metricsModelCache.getOrDefault(modelKey, 365.30);
                
                long s = Long.parseLong(params.getOrDefault("size", "1"));
                long n = Long.parseLong(params.getOrDefault("maxIterations", "1"));
                
                long rawCost = (long) (beta * (s * s * n));
                int cost = (int) Math.max(1L, rawCost / 1_000_000L);
                LOGGER.info("[Estimation] GRAYSCOTT EMA (" + modelKey + " Beta=" + String.format("%.2f", beta) + ") -> Raw: " + rawCost + " Units: " + cost);
                return cost;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[Estimation] Error calculating mathematical heuristic", e);
        }

        LOGGER.info("[Estimation] Unknown request. Using default units.");
        return 10;
    }

    // Select worker with lowest weighted score combining CPU and work
    private WorkerNode getSuitableServer() {
        WorkerNode bestNode = null;
        double minScore = Double.MAX_VALUE;
        final double WEIGHT_CPU = 0.4, WEIGHT_WORK = 0.6;

        for (WorkerNode node : LoadBalancer.activeWorkers.values()) {
            // Normalize CPU from 0-100 to 0-1
            double cpuNormalized = node.getCpuUtilization() / 100.0;
            
            // Relative work: current work / max capacity
            double relativeWork = node.getRelativeWork();
            
            // Weighted score
            double score = (WEIGHT_CPU * cpuNormalized) + (WEIGHT_WORK * relativeWork);
            
            LOGGER.fine("[LB] Worker " + node.getIp() + " score=" + String.format("%.3f", score) + 
                " (cpu=" + String.format("%.1f", node.getCpuUtilization()) + "%, work=" + 
                node.getWork().get() + "/" + node.getMaxCapacity() + ")");
            
            if (score < minScore) {
                minScore = score;
                bestNode = node;
            }
        }
        
        if (bestNode != null) {
            LOGGER.info("[LB] Selected worker " + bestNode.getIp() + " with score " + 
                String.format("%.3f", minScore));
        } else {
            LOGGER.warning("[LB] No suitable worker found");
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
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        
        byte[] responseBytes = responseBody.getBytes("UTF-8");
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        
        OutputStream os = exchange.getResponseBody();
        os.write(responseBytes);
        os.close();
    }

    private java.util.Map<String, String> parseQuery(String query) {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        if (query != null && !query.isEmpty()) {
            for (String param : query.split("&")) {
                String[] entry = param.split("=", 2);
                String key = URLDecoder.decode(entry[0], StandardCharsets.UTF_8);
                String value = entry.length > 1 ? URLDecoder.decode(entry[1], StandardCharsets.UTF_8) : "";
                params.put(key, value);
            }
        }
        return params;
    }
}