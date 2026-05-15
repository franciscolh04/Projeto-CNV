package pt.ulisboa.tecnico.cnv.loadbalancer;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.Map;

public class WorkerRegisterHandler implements HttpHandler {
    private Map<String, Integer> workers;
    private Map<String, String> workerIds; 
    
    public WorkerRegisterHandler(Map<String, Integer> workers, Map<String, String> workerIds) {
        this.workers = workers;
        this.workerIds = workerIds; 
    }

    @Override
    public void handle(HttpExchange t) throws IOException {
        String workerIp = t.getRemoteAddress().getAddress().getHostAddress();
        String query = t.getRequestURI().getQuery();
        String instanceId = query != null ? query.split("=")[1] : "ID_UNKNOWN";

        workers.put(workerIp, 0);
        workerIds.put(workerIp, instanceId); 
        
        System.out.println("[Handshake] New Worker ready! IP: " + workerIp + " | ID: " + instanceId);
        
        t.sendResponseHeaders(200, -1);
        t.close();
    }
}