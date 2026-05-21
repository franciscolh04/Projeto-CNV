package pt.ulisboa.tecnico.cnv.loadbalancer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;

public class WorkerRegisterHandler implements HttpHandler {   

    @Override
    public void handle(HttpExchange t) throws IOException {
        String workerIp = t.getRemoteAddress().getAddress().getHostAddress();
        String query = t.getRequestURI().getQuery();
        String instanceId = query != null ? query.split("=")[1] : "ID_UNKNOWN";

        LoadBalancer.activeWorkers.put(workerIp, new WorkerNode(instanceId, workerIp));
        
        System.out.println("[Handshake] New Worker ready! IP: " + workerIp + " | ID: " + instanceId);
        
        t.sendResponseHeaders(200, -1);
        t.close();
    }
}