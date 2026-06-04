package pt.ulisboa.tecnico.cnv.loadbalancer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;

public class WorkerRegisterHandler implements HttpHandler {   

    private final LoadBalancerHandler lbHandler;

    public WorkerRegisterHandler(LoadBalancerHandler handler) {
        this.lbHandler = handler;
    }

    @Override
    public void handle(HttpExchange t) throws IOException {
        String workerIp = t.getRemoteAddress().getAddress().getHostAddress();
        String query = t.getRequestURI().getQuery();
        String instanceId = query != null ? query.split("=")[1] : "ID_UNKNOWN";

        synchronized (LoadBalancer.clusterStateLock) {
            LoadBalancer.activeWorkers.putIfAbsent(workerIp, new WorkerNode(instanceId, workerIp));
            if (LoadBalancer.autoScaler != null) {
                LoadBalancer.autoScaler.removeWarmingUpInstance(instanceId);
            }
        }
        lbHandler.triggerQueueProcessing();
        
        System.out.println("[Handshake] New Worker ready! IP: " + workerIp + " | ID: " + instanceId);
        
        t.sendResponseHeaders(200, -1);
        t.close();
    }
}