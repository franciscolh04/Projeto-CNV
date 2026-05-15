package pt.ulisboa.tecnico.cnv.loadbalancer;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

public class LoadBalancerHandler implements HttpHandler {
    private Map<String, Integer> workers;
    private Map<String, String> workerIds; 

    public LoadBalancerHandler(Map<String, Integer> workers, Map<String, String> workerIds) {
        this.workers = workers;
        this.workerIds = workerIds;
    }

    @Override
    public void handle(HttpExchange t) throws IOException {
        System.out.println("[LB-RADAR] Received request: " + t.getRequestURI() + " from " + t.getRemoteAddress());
        if (workers.isEmpty()) {
            String response = "Service not available at the moment. Initializing Servers...";
            t.sendResponseHeaders(503, response.getBytes().length); 
            OutputStream os = t.getResponseBody();
            os.write(response.getBytes());
            os.close();
            return;
        }

        String bestWorkerIp = null;
        int minLoad = Integer.MAX_VALUE;

        for (Map.Entry<String, Integer> entry : workers.entrySet()) {
            if (entry.getValue() < minLoad) {
                minLoad = entry.getValue();
                bestWorkerIp = entry.getKey();
            }
        }

        int estimatedWeight = 1; 
        workers.put(bestWorkerIp, workers.get(bestWorkerIp) + estimatedWeight);
        System.out.println("[ALOCATION] Forwarding request to Worker " + workerIds.get(bestWorkerIp) + " at " + bestWorkerIp + " with current load: " + workers.get(bestWorkerIp));

        try {
            String query = t.getRequestURI().getQuery();
            String path = t.getRequestURI().getPath();
            URL targetUrl = new URL("http://" + bestWorkerIp + ":8000" + path + (query != null ? "?" + query : ""));
            
            HttpURLConnection connection = (HttpURLConnection) targetUrl.openConnection();
            connection.setRequestMethod(t.getRequestMethod());
            
            int responseCode = connection.getResponseCode();
            InputStream is = (responseCode >= 400) ? connection.getErrorStream() : connection.getInputStream();
            byte[] responseBytes = is.readAllBytes();

            t.sendResponseHeaders(responseCode, responseBytes.length);
            OutputStream os = t.getResponseBody();
            os.write(responseBytes);
            os.close();

        } catch (Exception e) {
            System.err.println("[LB] Communication Error with Worker" + bestWorkerIp);
            String response = "Internal Error while processing the request.";
            t.sendResponseHeaders(500, response.getBytes().length); 
            OutputStream os = t.getResponseBody();
            os.write(response.getBytes());
            os.close();
        } finally {
            if (workers.containsKey(bestWorkerIp)) {
                workers.put(bestWorkerIp, Math.max(0, workers.get(bestWorkerIp) - estimatedWeight));
                System.out.println("[ALOCATION] Completed request for Worker " + workerIds.get(bestWorkerIp) + " at " + bestWorkerIp + " with current load: " + workers.get(bestWorkerIp));
            }
        }
    }
}