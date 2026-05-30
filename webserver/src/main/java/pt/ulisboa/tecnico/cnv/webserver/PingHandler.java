package pt.ulisboa.tecnico.cnv.webserver;

import java.io.IOException;
import java.io.OutputStream;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.util.logging.Logger;

/**
 * Health check endpoint: Returns {"status":"ok"} with X-CPU-Utilization header.
 */
public class PingHandler implements HttpHandler {
    private static final Logger LOGGER = Logger.getLogger(PingHandler.class.getName());
    
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            double cpuPercent = CPUMonitor.getInstance().getCpuUtilizationPercent();
            String cpuHeader = String.format("%.2f", cpuPercent);
            String response = "{\"status\":\"ok\"}";
            
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("X-CPU-Utilization", cpuHeader);
            exchange.sendResponseHeaders(200, response.length());
            
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes("UTF-8"));
            os.close();
            LOGGER.fine("[PingHandler] CPU: " + cpuHeader + "%");
            
        } catch (Exception e) {
            LOGGER.severe("[PingHandler] Error: " + e.getMessage());
            exchange.sendResponseHeaders(500, 0);
            exchange.close();
        }
    }
}

