package pt.ulisboa.tecnico.cnv.webserver;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;

import com.sun.net.httpserver.HttpServer;

import pt.ulisboa.tecnico.cnv.dna.DnaHandler;
import pt.ulisboa.tecnico.cnv.fractals.FractalsHandler;
import pt.ulisboa.tecnico.cnv.grayscott.GrayScottHandler;

public class WebServer {
    public static void main(String[] args) throws Exception {
        System.out.println("Starting Worker Node...");
        String loadBalancerIp = "127.0.0.1";
        String myInstanceId = "ID_LOCAL";

        // Get the LB IP and instance ID from command line arguments if provided
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: java WebServer <LoadBalancerIP> <InstanceID>");
        }

        loadBalancerIp = args[0]; 
        myInstanceId = args[1];
        
        System.out.println("[Worker] AWS ID: " + myInstanceId);

        HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", new RootHandler());
        server.createContext("/fractals", new FractalsHandler());
        server.createContext("/dna", new DnaHandler());
        server.createContext("/grayscott", new GrayScottHandler());

        System.out.println("[Worker] Trying to register myself to the LB: " + loadBalancerIp);
        
        try {
            URL registerUrl = new URL("http://" + loadBalancerIp + ":8000/register?id=" + myInstanceId);
            HttpURLConnection registerConn = (HttpURLConnection) registerUrl.openConnection();
            registerConn.setRequestMethod("GET");
            registerConn.setConnectTimeout(3000); // 3 seconds timeout
            registerConn.setReadTimeout(3000);    // 3 seconds timeout
            
            int responseCode = registerConn.getResponseCode(); 
            if (responseCode == 200) {
                System.out.println("[Worker] Registed myself to the LB successfully.");
            }
        } catch (Exception e) {
            System.err.println("[Worker] ERROR: Failed to register with LB at " + loadBalancerIp);
        }

        // Health check endpoint
        server.createContext("/ping", new PingHandler());
        server.start();
    }
}
