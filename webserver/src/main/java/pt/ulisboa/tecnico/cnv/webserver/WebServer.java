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
        if (args.length > 0) {
            loadBalancerIp = args[0]; 
        }
        if (args.length > 1) {
            myInstanceId = args[1];
        }
        System.out.println("[Worker] O meu ID da AWS é: " + myInstanceId);

        // Handshake with the Load Balancer
        if (args.length > 0) {
            System.out.println("[Worker] Trying to register myself to the LB: " + loadBalancerIp);
            
            try {
                URL registerUrl = new URL("http://" + loadBalancerIp + ":8000/register?id=" + myInstanceId);
                HttpURLConnection registerConn = (HttpURLConnection) registerUrl.openConnection();
                registerConn.setRequestMethod("GET");
                
                int responseCode = registerConn.getResponseCode(); 
                if (responseCode == 200) {
                    System.out.println("[Worker] Registed myself to the LB successfully.");
                }
            } catch (Exception e) {
                System.err.println("[Worker] ERROR: Failed to register with LB at " + loadBalancerIp);
            }
        } else {
            System.out.println("[Worker] ERROR: Registration with LB failed. No LB IP provided. Running without registration.");
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", new RootHandler());
        server.createContext("/fractals", new FractalsHandler());
        server.createContext("/dna", new DnaHandler());
        server.createContext("/grayscott", new GrayScottHandler());

        // Health check endpoint
        // Rota de Health Check para o Master Node verificar se este Worker está vivo
        server.createContext("/health", t -> {
            String response = "OK";
            t.sendResponseHeaders(200, response.length());
            OutputStream os = t.getResponseBody();
            os.write(response.getBytes());
            os.close();
        });

        server.start();
    }
}
