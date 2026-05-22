package pt.ulisboa.tecnico.cnv.loadbalancer;

import com.sun.net.httpserver.HttpServer;

import software.amazon.awssdk.services.ec2.endpoints.internal.Value.Str;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Main Load Balancer to distribute requests across EC2 worker instances.
 */
public class LoadBalancer {
    private static final Logger LOGGER = Logger.getLogger(LoadBalancer.class.getName());
    
    public static final ConcurrentHashMap<String, WorkerNode> activeWorkers = new ConcurrentHashMap<>();

    // TODO: Implementar modelo de previsão de custo e desempenho ( Operation -> cost estimation )
    public static final ConcurrentHashMap<String, Integer> metricsModelCache = new ConcurrentHashMap<>();

    private static final int LB_PORT = 8000;

    private static String masterIp = "127.0.0.1";
    
    private static ScheduledExecutorService scheduler;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java LoadBalancer <master-ip>");
            System.exit(1);
        }
        masterIp = args[0];

        try {
            System.out.println("Starting Load Balancer on port " + LB_PORT);
            startHttpServer();
            startScheduledTasks(masterIp);
            System.out.println("Load Balancer ready on port " + LB_PORT);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to start Load Balancer", e);
            System.exit(1);
        }
    }

    private static void startHttpServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(LB_PORT), 0);
        LoadBalancerHandler handler = new LoadBalancerHandler();
        
        server.createContext("/fractals", handler);
        server.createContext("/dna", handler);
        server.createContext("/grayscott", handler);
        server.createContext("/register", new WorkerRegisterHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    private static void startScheduledTasks(String masterIP) {
        scheduler = Executors.newScheduledThreadPool(3);
        scheduler.scheduleAtFixedRate(new HealthChecker(), 0, 5, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(new AutoScaler(masterIP), 0, 15, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(new MSSPoller(), 0, 30, TimeUnit.SECONDS);
    }

}