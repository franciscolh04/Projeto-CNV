package pt.ulisboa.tecnico.cnv.loadbalancer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaAsyncClient;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

/**
 * HTTP handler: Routes client requests to least-loaded healthy worker.
 */
public class LoadBalancerHandler implements HttpHandler {
    private static final Logger LOGGER = Logger.getLogger(LoadBalancerHandler.class.getName());
    private static final long CONNECT_TIMEOUT_MS = 5000;
    private static final long READ_TIMEOUT_MS = 120000;
    private static final int WORKER_PORT = 8000;
    private static final int FAAS_THRESHOLD = 2000;
    private static final int QUEUE_SIZE_THRESHOLD = 3;
    private static final double HARD_LIMIT_SCORE = 0.90;
    
    private final HttpClient httpClient;

    private final LambdaAsyncClient awsLambda;

    public static final PriorityBlockingQueue<Job> pendingQueue = new PriorityBlockingQueue<>();

    private final ExecutorService dispatcherLoop = Executors.newSingleThreadExecutor();

    public LoadBalancerHandler() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .build();
        this.awsLambda = LambdaAsyncClient.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
                .build();
    }
    
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String path = exchange.getRequestURI().getPath();

        int estimatedWork = estimateWork(exchange);

        boolean isSmallRequest = estimatedWork <= FAAS_THRESHOLD;

        // For small requests, if the queue is long, try to offload to FaaS before enqueuing
        if (pendingQueue.size() > QUEUE_SIZE_THRESHOLD) {
            if (LoadBalancer.autoScaler != null) {
                LoadBalancer.autoScaler.requestEvaluation();
            }
            if (isSmallRequest) {
                invokeFunctionAsync(exchange, path, query, estimatedWork);
                return;
            }
        }

        enqueueJob(exchange, path, query, estimatedWork);
    }

    // Enqueue job if FaaS offloading is skipped or fails
    private void enqueueJob(HttpExchange exchange, String path, String query, int estimatedWork) {
        Job job = new Job(exchange, path, query, estimatedWork);

        job.futureResult.whenCompleteAsync((responseBody, throwable) -> {
            try {
                if (throwable != null) {
                    LOGGER.log(Level.SEVERE, "Error processing job", throwable);
                    sendResponse(exchange, 502, "Bad Gateway: " + throwable.getMessage());
                } else {
                    sendResponse(exchange, 200, responseBody);
                }
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Error sending response for Job", e);
            }
        }, Executors.newCachedThreadPool());

        // Adds the job to the pending queue and tries to dispatch immediately
        pendingQueue.add(job);
        LOGGER.info("[LB] Received request: " + exchange.getRequestMethod() + " " + truncateForLogging(exchange.getRequestURI().toString()) + 
            " | Estimated Work: " + job.estimatedWork + " units | Queue Size: " + pendingQueue.size());

        triggerQueueProcessing();
    }


    /*
    * Triggered when a new worker registers. Attempts to dispatch jobs from the pending queue.
     */    
    public void triggerQueueProcessing() {
        dispatcherLoop.submit(this::processQueue);
        LOGGER.fine("[LB] Triggered queue processing. Current queue size: " + pendingQueue.size());
    }
    /*
    * Tries to dispatch jobs from the pending queue to suitable workers while respecting capacity limits.
     * Uses a loop to continuously check the head of the queue and dispatch if possible.
     */
    private void processQueue() {
        while (true) {
            Job job = pendingQueue.poll();
            if (job == null) break;

            WorkerNode targetNode = getSuitableServer(job.estimatedWork); 
            if (targetNode == null) {
                // Re-enqueue the job for later processing
                pendingQueue.add(job);
                break;
            }
            
            try {
                targetNode.getWork().addAndGet(job.estimatedWork);

                forwardRequestAsync(targetNode.getIp(), job.path, job.query)
                        .whenComplete((response, throwable) -> {
                            // Callback executed upon request completion
                            targetNode.getWork().addAndGet(-job.estimatedWork);

                            if (throwable != null || (response != null && response.statusCode() >= 500)) {
                                if (job.retries < job.MAX_RETRIES) {
                                    job.retries++;
                                    pendingQueue.add(job); 
                                } else {
                                    job.futureResult.completeExceptionally(throwable);
                                }
                            } else if (response.statusCode() == 200) {
                                job.futureResult.complete(response.body());
                            } else {
                                job.futureResult.completeExceptionally(new RuntimeException("Status: " + response.statusCode()));
                            }

                            // Trigger another round of queue processing in case there are more jobs that can be dispatched now
                            triggerQueueProcessing(); 
                        });
            } catch (Exception e) {
                targetNode.getWork().addAndGet(-job.estimatedWork);
                job.futureResult.completeExceptionally(e);
                // Ensure we trigger queue processing to handle the next jobs
                triggerQueueProcessing(); 
            }
        }
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
     * Forwards request to worker node asynchronously.
     * Extracts X-Request-Cost header to update the metrics model cache.
     */
    private CompletableFuture<HttpResponse<String>> forwardRequestAsync(String workerIp, String path, String query) {
        String url = "http://" + workerIp + ":" + WORKER_PORT + path;
        if (query != null && !query.isEmpty()) {
            String encodedQuery = encodeQueryString(query);
            url += "?" + encodedQuery;
        }
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                .GET()
                .build();
        
        LOGGER.info("Forwarding request to worker " + workerIp + ": " + truncateForLogging(url));
        
        // Returns the future to allow handling response
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, throwable) -> {
                    // Metrics Learning: extracts X-Request-Cost header and updates the cache
                    if (throwable == null && response.statusCode() == 200) {
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
                                    } else if (path.contains("dna")) {
                                        long minLength = Long.parseLong(p.getOrDefault("minLength", "1"));
                                        String stopOnFirst = p.getOrDefault("stopOnFirst", "false").toLowerCase();
                                        long l1 = p.getOrDefault("seq1", "").length();
                                        long l2 = p.getOrDefault("seq2", "").length();
                                        long workUnits = Math.max(1L, l1 - minLength + 1) * Math.max(1L, l2 - minLength + 1);
                                        
                                        String emaKey;
                                        if (minLength >= 13) emaKey = "dna_high";
                                        else if ("true".equals(stopOnFirst)) emaKey = "dna_low_true";
                                        else emaKey = "dna_low_false";
                                        updateLocalEMA(emaKey, rawCost, workUnits);
                                    }
                                } catch (Exception e) {
                                    LOGGER.warning("[Metrics] Error in Fast Loop: " + e.getMessage());
                                }

                                LOGGER.info("[Metrics] Fast Loop registered cost for " + truncateForLogging(fullUrl) + " -> " + rawCost);
                            } catch (NumberFormatException e) {
                                LOGGER.warning("[LB] Invalid X-Request-Cost header");
                            }
                        });
                    }
                });
    }

    private void invokeFunctionAsync(HttpExchange exchange, String path, String query, int estimatedWork) {
        String functionName="";
        String json="{}";

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

            final String finalFunctionName = functionName;
            LOGGER.info("[LB] Invoking Lambda function " + finalFunctionName + " with payload: " + truncateForLogging(json));
            SdkBytes payload = SdkBytes.fromUtf8String(json) ;
            InvokeRequest request = InvokeRequest.builder().functionName(finalFunctionName).payload(payload).build();

            awsLambda.invoke(request).whenComplete((res, throwable) -> {
                if (throwable != null || res.functionError() != null) {
                    LOGGER.log(Level.SEVERE, "[FaaS Error] Fallback to Queue.");
                    enqueueJob(exchange, path, query, estimatedWork);
                    return;
                }

                try {
                    String responseBody = res.payload().asUtf8String();
                    LOGGER.info("[LB] Lambda function " + finalFunctionName + " invocation succeeded.");
                    sendResponse(exchange, 200, responseBody);
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Error sending Lambda response", e);
                }
            });

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error preparing Lambda function. Fallback to queue.", e);
            enqueueJob(exchange, path, query, estimatedWork);
        }
    }

    // Fast Loop EMA update for accurate recent approximations
    private void updateLocalEMA(String key, long actualCost, long workUnits) {
        if (workUnits <= 0) return;
        double observedCost = (double) actualCost / workUnits;

        double currentEMA = LoadBalancer.metricsModelCache.getOrDefault(key, observedCost);

        double newEMA = (0.05 * observedCost) + (0.95 * currentEMA); // Alpha = 0.05
        LoadBalancer.metricsModelCache.put(key, newEMA);
    }

    /**
     * Estimates the work required for a request dynamically using continuous learning coefficients.
     */
    private int estimateWork(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        final int maxAllowedEstimate = (int) (HARD_LIMIT_SCORE*AutoScaler.getAverageNodeCapacity());
        
        String cacheKey = path;
        if (query != null && !query.isEmpty()) {
             cacheKey += "?" + query;
        }

        // Check recent EXACT MATCH cache first for the full URL (including query) to capture recency and unpredictability
        Long exactRecentCost = LoadBalancer.recentExactCache.get(cacheKey);
        if (exactRecentCost != null) {
            LOGGER.info("[Estimation] EXACT MATCH RECENTE hit. Cost: " + exactRecentCost);
            return (int) Math.min(maxAllowedEstimate, Math.max(1L, exactRecentCost / 1_000_000L));
        }
        
        // If no recent exact match, proceed with heuristics based on dynamic EMA coefficients
        try {
            java.util.Map<String, String> params = parseQuery(query);

            if (path.contains("dna")) {
                long minLength = Long.parseLong(params.getOrDefault("minLength", "1"));
                String stopOnFirst = params.getOrDefault("stopOnFirst", "false").toLowerCase();
                String seq1 = params.getOrDefault("seq1", "");
                String seq2 = params.getOrDefault("seq2", "");
                long l1 = seq1.length();
                long l2 = seq2.length();
                
                String emaKey;
                if (minLength >= 13) emaKey = "dna_high";
                else if ("true".equals(stopOnFirst)) emaKey = "dna_low_true";
                else emaKey = "dna_low_false";
                
                Double beta = LoadBalancer.metricsModelCache.getOrDefault(emaKey, 38.0);
                long workUnits = Math.max(1L, l1 - minLength + 1) * Math.max(1L, l2 - minLength + 1);
                
                long rawCost = (long) (beta * workUnits);
                int cost = (int) Math.max(1L, rawCost / 1_000_000L);
                LOGGER.info("[Estimation] DNA EMA (" + emaKey + " Beta=" + String.format("%.2f", beta) + ") -> Raw: " + rawCost + " Units: " + cost);
                return Math.min(cost, maxAllowedEstimate);
            } else if (path.contains("fractal")) {
                // Fetch dynamic EMA coefficient
                Double beta = LoadBalancer.metricsModelCache.getOrDefault("fractals", 2579.23);
                long w = Long.parseLong(params.getOrDefault("w", "1"));
                long h = Long.parseLong(params.getOrDefault("h", "1"));
                
                long rawCost = (long) (beta * (w * h));
                int cost = (int) Math.max(1L, rawCost / 1_000_000L);
                LOGGER.info("[Estimation] FRACTAL EMA (Beta=" + String.format("%.2f", beta) + ") -> Raw: " + rawCost + " Units: " + cost);
                return Math.min(cost, maxAllowedEstimate);
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
                return Math.min(cost, maxAllowedEstimate);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[Estimation] Error calculating mathematical heuristic", e);
        }

        LOGGER.info("[Estimation] Unknown request. Using default units.");
        return 10;
    }

    /*
    * Select worker with Highest weighted score combining CPU and work where 
    * the work fits without exceeding a hard limit. Returns null if no suitable worker is found.
    */
    private WorkerNode getSuitableServer(int requiredWork) {
        WorkerNode bestNode = null;
        double minScore = Double.MAX_VALUE;
        final double WEIGHT_CPU = 0.4, WEIGHT_WORK = 0.6;

        for (WorkerNode node : LoadBalancer.activeWorkers.values()) {
            // Normalize CPU from 0-100 to 0-1
            double cpuNormalized = node.getCpuUtilization() / 100.0;
            
            // Relative work: current work + new / max capacity
            double projectedRelativeWork = (double) (node.getWork().get() + requiredWork) / node.getMaxCapacity();
            
            LOGGER.fine("[LB] Worker " + node.getIp() + " score=" + String.format("%.3f", projectedRelativeWork) + 
                " (cpu=" + String.format("%.1f", node.getCpuUtilization()) + "%, work=" + 
                node.getWork().get() + "/" + node.getMaxCapacity() + ")");

            // Weighted score
            double projectedScore = (WEIGHT_CPU * cpuNormalized) + (WEIGHT_WORK * projectedRelativeWork);
            
            if (projectedScore < minScore) {
                minScore = projectedScore;
                bestNode = node;
            }
        }
    
        if (bestNode != null && minScore <= HARD_LIMIT_SCORE) {
            LOGGER.info("[LB] Selected primary worker " + bestNode.getIp() + 
                " (score: " + String.format("%.3f", minScore) + ")");
            return bestNode;
        } 
        
        // Every worker exceeds the hard limit
        LOGGER.warning("[LB] No suitable worker found (all exceeded limit). Job stays in queue.");
        if (LoadBalancer.autoScaler != null) {
            LoadBalancer.autoScaler.requestEvaluation();
        }
        return null;
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