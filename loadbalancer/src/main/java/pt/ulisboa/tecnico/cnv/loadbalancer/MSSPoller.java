package pt.ulisboa.tecnico.cnv.loadbalancer;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

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
    private long lastPollTime;

    public MSSPoller() {
        // Initialize AWS SDK v2 DynamoDB Client
        this.dynamoDb = DynamoDbClient.builder()
                .region(Region.US_EAST_1)
                .build();
        
        // Start polling from 1 minute ago to capture recent metrics
        this.lastPollTime = System.currentTimeMillis() - 60000;
    }

    @Override
    public void run() {
        try {
            long currentPollTime = System.currentTimeMillis();
            String[] workloadTypes = {"grayscott", "fractals", "dna"};

            for (String typeName : workloadTypes) {
                // Note: Replacing Scan for Query requires that an index exists in DynamoDB
                // (or that the main table has 'workloadType' as Hash Key and 'timestamp' as Sort Key).
                // If it is a Global Secondary Index (GSI), uncomment line `.indexName(...)`
                QueryRequest queryRequest = QueryRequest.builder()
                        .tableName(TABLE_NAME)
                        // .indexName("WorkloadTypeIndex") // <- Use your GSI's name, if it's the case
                        .keyConditionExpression("workloadType = :type AND #ts > :lastTime")
                        .expressionAttributeNames(Map.of("#ts", "timestamp"))
                        .expressionAttributeValues(Map.of(
                                ":type", AttributeValue.builder().s(typeName).build(),
                                ":lastTime", AttributeValue.builder().n(String.valueOf(lastPollTime)).build()
                        ))
                        .build();

                QueryResponse response = dynamoDb.query(queryRequest);

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
            }
            lastPollTime = currentPollTime;
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
        
        // Fetch current EMA from cache (default to observed if empty)
        double currentEMA = LoadBalancer.metricsModelCache.getOrDefault(cacheKey, observedCostPerUnit);
        
        // Apply Exponential Moving Average formula
        double newEMA = (ALPHA * observedCostPerUnit) + ((1.0 - ALPHA) * currentEMA);
        
        // Update cache with specific topological key
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

    // Extract String URL parameter
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