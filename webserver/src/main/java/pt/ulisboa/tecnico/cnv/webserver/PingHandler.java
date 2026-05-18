package pt.ulisboa.tecnico.cnv.webserver;

import java.io.IOException;
import java.io.OutputStream;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

public class PingHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String response = "{\"status\":\"ok\"}";
        
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length());
        
        OutputStream os = exchange.getResponseBody();
        os.write(response.getBytes("UTF-8"));
        os.close();
    }
}
