package pt.ulisboa.tecnico.cnv.fractals;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

public class FractalsHandler implements HttpHandler, RequestHandler<Map<String, String>, String> {

    // DynamoDB client for saving request metrics
    private static final DynamoDbAsyncClient dynamoClient = DynamoDbAsyncClient.builder()
            .region(Region.US_EAST_1)
            .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
            .build();

    /**
     * Entrypoint for the workload.
     */
    private String handleWorkload(int width, int height, int iterations) {
        BufferedImage image = JuliaFractal.generate(width, height, iterations);

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            String base64Image = Base64.getEncoder().encodeToString(baos.toByteArray());
            return String.format("data:image/png;base64,%s", base64Image);
        } catch (IOException e) {
            e.printStackTrace();
            return "{ \"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /**
     * Entrypoint for HTTP requests.
     */
    @Override
    public void handle(HttpExchange he) throws IOException {
        // Handling CORS.
        he.getResponseHeaders().add("Access-Control-Allow-Origin", "*");

        if ("OPTIONS".equalsIgnoreCase(he.getRequestMethod())) {
            he.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
            he.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,Authorization");
            he.sendResponseHeaders(204, -1);
            return;
        }

        long startTime = System.currentTimeMillis();
        String urlPart = he.getRequestURI().toString().substring(0, Math.min(100, he.getRequestURI().toString().length()));
        System.out.println("[FRACTAL] START | " + he.getRequestMethod() + " | " + urlPart);

        // Parse request.
        URI requestedUri = he.getRequestURI();
        String query = requestedUri.getRawQuery();
        Map<String, String> parameters = queryToMap(query);

        try {
            int width = Integer.parseInt(parameters.getOrDefault("w", "800"));
            int height = Integer.parseInt(parameters.getOrDefault("h", "600"));
            int iterations = Integer.parseInt(parameters.getOrDefault("iterations", "100"));
            System.out.println("[FRACTAL] PARAMS | w=" + width + ", h=" + height + ", it=" + iterations);

            String response = handleWorkload(width, height, iterations);

            // Fetch metrics from Javassist agent
            long cost = pt.ulisboa.tecnico.cnv.javassist.tools.ComplexityEstimator.getAndResetCost();
            he.getResponseHeaders().add("X-Request-Cost", String.valueOf(cost));

            // Save request and cost to DynamoDB
            try {
                Map<String, AttributeValue> item = new HashMap<>();
                item.put("requestId", AttributeValue.builder().s(UUID.randomUUID().toString()).build());
                item.put("timestamp", AttributeValue.builder().n(String.valueOf(System.currentTimeMillis())).build());
                item.put("workloadType", AttributeValue.builder().s("fractals").build());
                item.put("params", AttributeValue.builder().s(query == null ? "" : query).build());
                item.put("actualCost", AttributeValue.builder().n(String.valueOf(cost)).build());

                PutItemRequest putReq = PutItemRequest.builder()
                        .tableName("RequestHistory")
                        .item(item)
                        .build();

                PutItemResponse f = dynamoClient.putItem(putReq).join();
                System.out.println("[Metrics] DynamoDB putItem response: " + f.toString());
            } catch (Exception e) {
                System.err.println("[Metrics] Failed to write to DynamoDB: " + e.getMessage());
            }

            he.sendResponseHeaders(200, response.length());
            OutputStream os = he.getResponseBody();
            os.write(response.getBytes());
            os.close();
            System.out.println("[FRACTAL] DONE | " + (System.currentTimeMillis() - startTime) + "ms");

        } catch (NumberFormatException e) {
            e.printStackTrace();
            String errorResponse = "{ \"error\":\"Parameters 'w', 'h', and 'iterations' must be valid integers.\"}";
            he.sendResponseHeaders(400, errorResponse.length());
            OutputStream os = he.getResponseBody();
            os.write(errorResponse.getBytes());
            os.close();
        }
    }

    /**
     * Entrypoint for AWS Lambda.
     */
    @Override
    public String handleRequest(Map<String, String> event, Context context) {
        try {
            int width = Integer.parseInt(event.getOrDefault("w", "800"));
            int height = Integer.parseInt(event.getOrDefault("h", "600"));
            int iterations = Integer.parseInt(event.getOrDefault("iterations", "50"));

            return handleWorkload(width, height, iterations);
        } catch (NumberFormatException e) {
            return "{ \"error\":\"Parameters 'w', 'h', and 'iterations' must be valid integers.\"}";
        }
    }

    /**
     * For debugging use - to run from CLI.
     */
    public static void main(String[] args) {
        if (args.length < 4) {
            System.out.println("Usage: java pt.ulisboa.tecnico.cnv.fractals.FractalsHandler <width> <height> <iterations> <output_image.png>");
            return;
        }

        try {
            int width = Integer.parseInt(args[0]);
            int height = Integer.parseInt(args[1]);
            int iterations = Integer.parseInt(args[2]);
            String outputPath = args[3];

            BufferedImage image = JuliaFractal.generate(width, height, iterations);

            File outputFile = new File(outputPath);
            ImageIO.write(image, "png", outputFile);
            System.out.println("Fractal successfully saved to: " + outputFile.getAbsolutePath());

        } catch (NumberFormatException e) {
            System.err.println("The width, height, and iterations arguments should be valid integer values.");
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Failed to write the output image file.");
            System.exit(1);
        }
    }

    /**
     * Parse query string into a map.
     */
    private Map<String, String> queryToMap(String query) {
        if (query == null) {
            return new HashMap<>();
        }
        Map<String, String> result = new HashMap<>();
        for (String param : query.split("&")) {
            String[] entry = param.split("=");
            if (entry.length > 1) {
                result.put(entry[0], entry[1]);
            } else {
                result.put(entry[0], "");
            }
        }
        return result;
    }
}
