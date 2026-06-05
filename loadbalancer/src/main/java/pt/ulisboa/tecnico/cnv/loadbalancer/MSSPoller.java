package pt.ulisboa.tecnico.cnv.loadbalancer;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Metrics poller thread - collects metrics from DynamoDB and updates EMA cache
 */
public class MSSPoller implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(MSSPoller.class.getName());
    
    private static final String TABLE_NAME = "RequestHistory";
    
    // EMA Learning Rate: 20% new data, 80% historical data
    private static final double ALPHA = 0.2;

    // Private baseline memory (no concurrency control needed)
    private final Map<String, Double> baselineMetricsModel = new HashMap<>();

    private final DynamoDbClient dynamoDb;
    private long lastPollTime;

    public MSSPoller() {
        // Initialize AWS SDK v2 DynamoDB Client
        this.dynamoDb = DynamoDbClient.builder()
                .region(Region.US_EAST_1)
                .build();
        
        // Start polling from 1 minute ago to capture recent metrics
        this.lastPollTime = System.currentTimeMillis() - 60000;

        // Init baseline heuristics
        baselineMetricsModel.put("fractals", 2579.23);
        baselineMetricsModel.put("grayscott_center_false", 365.29);
        baselineMetricsModel.put("grayscott_center_true", 365.29);
        baselineMetricsModel.put("grayscott_ring_false", 365.60);
        baselineMetricsModel.put("grayscott_ring_true", 210.13);
        baselineMetricsModel.put("grayscott_stripe_false", 365.31);
        baselineMetricsModel.put("grayscott_stripe_true", 365.31);
    }

    @Override
    public void run() {
        try {
            long currentPollTime = System.currentTimeMillis();

            // Set up expression values for filtering
            Map<String, AttributeValue> expressionValues = new HashMap<>();
            expressionValues.put(":lastTime", AttributeValue.builder().n(String.valueOf(this.lastPollTime)).build());

            // Map reserved keyword 'timestamp' to an alias
            Map<String, String> expressionNames = new HashMap<>();
            expressionNames.put("#ts", "timestamp");

            // Safe Scan with time filter to save bandwidth
            ScanRequest scanRequest = ScanRequest.builder()
                    .tableName(TABLE_NAME)
                    .filterExpression("#ts > :lastTime")
                    .expressionAttributeNames(expressionNames)
                    .expressionAttributeValues(expressionValues)
                    .build();

            ScanResponse response = dynamoDb.scan(scanRequest);

            for (Map<String, AttributeValue> item : response.items()) {
                // Ensure attributes exist before parsing
                if (item.containsKey("workloadType") && item.containsKey("actualCost") && item.containsKey("params")) {
                    String type = item.get("workloadType").s();
                    long actualCost = Long.parseLong(item.get("actualCost").n());
                    String params = item.get("params").s();

                    if ("grayscott".equals(type) || "fractals".equals(type)) {
                        updateMathModel(type, params, actualCost);
                    } else if ("dna".equals(type)) {
                        // DNA is unpredictable due to early exits, exact match caching is preferred
                        LoadBalancer.dnaExactCache.put(params, (double) actualCost);
                    }
                }
            }
            // Update timestamp for next 30s cycle
            this.lastPollTime = currentPollTime;
            LOGGER.info("Successfully updated Load Balancer heuristics cache.");

        } catch (Exception e) {
            LOGGER.severe("DynamoDB polling error: " + e.getMessage());
        }
    }

    /**
     * Calculates cost per work unit and updates the Exponential Moving Average in the cache
     */
    private void updateMathModel(String operation, String params, long actualCost) {
        long workUnits = 1;
        String cacheKey = operation; // Default key
        
        if ("grayscott".equals(operation)) {
            long size = extractParam(params, "size", 256);
            long iters = extractParam(params, "maxIterations", 1000);
            workUnits = (size * size) * iters;
            
            // Build composite key for topological partitioning
            String seedMode = extractStringParam(params, "seedMode", "center");
            String stopOnExt = extractStringParam(params, "stopOnExtinction", "false");
            cacheKey = "grayscott_" + seedMode + "_" + stopOnExt;
            
        } else if ("fractals".equals(operation)) {
            long w = extractParam(params, "w", 400);
            long h = extractParam(params, "h", 300);
            workUnits = w * h;
        }

        // Calculate observed cost per work unit (Beta)
        double observedCostPerUnit = (double) actualCost / workUnits;

        // Fetch baseline EMA from internal cache
        double currentBaselineEMA = this.baselineMetricsModel.getOrDefault(cacheKey, observedCostPerUnit);
        
        // Apply Exponential Moving Average formula
        double newEMA = (ALPHA * observedCostPerUnit) + ((1.0 - ALPHA) * currentBaselineEMA);

        // Update internal baseline model and LB cache with new EMA value
        this.baselineMetricsModel.put(cacheKey, newEMA);
        LoadBalancer.metricsModelCache.put(cacheKey, newEMA);
    }

    /**
     * Helper to parse URL parameters
     */
    private long extractParam(String query, String paramName, long defaultValue) {
        if (query == null) return defaultValue;
        
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=");
            if (kv.length == 2 && kv[0].equals(paramName)) {
                try {
                    return Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            }
        }
        return defaultValue;
    }

    /**
     * Parse String URL parameter
     */
    private String extractStringParam(String query, String paramName, String defaultValue) {
        if (query == null) return defaultValue;
        
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=");
            if (kv.length == 2 && kv[0].equals(paramName)) {
                return kv[1];
            }
        }
        return defaultValue;
    }
}