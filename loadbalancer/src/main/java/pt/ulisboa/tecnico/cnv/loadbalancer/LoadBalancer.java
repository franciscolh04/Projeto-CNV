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
    
    // Tracks currently active and healthy EC2 worker nodes
    public static final ConcurrentHashMap<String, WorkerNode> activeWorkers = new ConcurrentHashMap<>();

    // EMA Cache for mathematical workloads (Fractals, Gray-Scott and DNA) - Stores Beta coefficient
    public static final ConcurrentHashMap<String, Double> metricsModelCache = new ConcurrentHashMap<>();

    // LRU Cache for the last 1000 EXACT requests (Maintains recency automatically)
    public static final java.util.Map<String, Long> recentExactCache = java.util.Collections.synchronizedMap(
        new java.util.LinkedHashMap<String, Long>(1000, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<String, Long> eldest) {
                return size() > 1000;
            }
        }
    );

    public static final Object clusterStateLock = new Object();

    private static final int LB_PORT = 8000;

    private static String masterIp = "127.0.0.1";
    
    private static ScheduledExecutorService scheduler;

    public static AutoScaler autoScaler;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java LoadBalancer <master-ip>");
            System.exit(1);
        }
        masterIp = args[0];

        // --- Initialize Baseline Heuristics ---
        // So the LB doesn't divide by zero or fail before the MSSPoller completes its first 30s cycle
        metricsModelCache.put("fractals", 2579.23);
        metricsModelCache.put("grayscott_center_false", 365.29);
        metricsModelCache.put("grayscott_center_true", 365.29);
        metricsModelCache.put("grayscott_ring_false", 365.60);
        metricsModelCache.put("grayscott_ring_true", 210.13);
        metricsModelCache.put("grayscott_stripe_false", 365.31);
        metricsModelCache.put("grayscott_stripe_true", 365.31);
        metricsModelCache.put("dna_high", 38.0);
        metricsModelCache.put("dna_low_false", 1.5);
        metricsModelCache.put("dna_low_true", 0.01);
        
        try {
            System.out.println("Starting Load Balancer on port " + LB_PORT);
            startHttpServer(masterIp);
            startScheduledTasks(masterIp);
            System.out.println("Load Balancer ready on port " + LB_PORT);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to start Load Balancer", e);
            System.exit(1);
        }
    }

    private static void startHttpServer(String masterIP) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(LB_PORT), 0);
        LoadBalancerHandler handler = new LoadBalancerHandler();

        autoScaler = new AutoScaler(masterIP);
        
        server.createContext("/fractals", handler);
        server.createContext("/dna", handler);
        server.createContext("/grayscott", handler);
        server.createContext("/register", new WorkerRegisterHandler(handler));
        server.setExecutor(Executors.newFixedThreadPool(200));

        autoScaler.start();
        server.start();
    }

    private static void startScheduledTasks(String masterIP) {
        scheduler = Executors.newScheduledThreadPool(3);
        scheduler.scheduleWithFixedDelay(new HealthChecker(autoScaler), 0, 5, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(new MSSPoller(), 0, 30, TimeUnit.SECONDS);
    }

}