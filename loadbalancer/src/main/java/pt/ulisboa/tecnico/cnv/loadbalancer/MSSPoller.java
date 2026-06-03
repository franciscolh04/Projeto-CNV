package pt.ulisboa.tecnico.cnv.loadbalancer;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

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

    private final DynamoDbClient dynamoDb;

    public MSSPoller() {
        // Initialize AWS SDK v2 DynamoDB Client
        this.dynamoDb = DynamoDbClient.builder()
                .region(Region.US_EAST_1)
                .build();
    }

    @Override
    public void run() {
        try {
            // Note: In production, use Query with timestamp instead of full Scan
            ScanRequest scanRequest = ScanRequest.builder()
                    .tableName(TABLE_NAME)
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
        
        if ("grayscott".equals(operation)) {
            long size = extractParam(params, "size", 256);
            long iters = extractParam(params, "maxIterations", 1000);
            workUnits = (size * size) * iters;
        } else if ("fractals".equals(operation)) {
            long w = extractParam(params, "w", 400);
            long h = extractParam(params, "h", 300);
            workUnits = w * h;
        }

        // 1. Calculate observed cost per work unit (Beta)
        double observedCostPerUnit = (double) actualCost / workUnits;
        
        // 2. Fetch current EMA from cache (default to observed if empty)
        double currentEMA = LoadBalancer.metricsModelCache.getOrDefault(operation, observedCostPerUnit);
        
        // 3. Apply Exponential Moving Average formula
        double newEMA = (ALPHA * observedCostPerUnit) + ((1.0 - ALPHA) * currentEMA);
        
        // 4. Update the thread-safe cache
        LoadBalancer.metricsModelCache.put(operation, newEMA);
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
}