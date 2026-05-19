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

        HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", new RootHandler());
        server.createContext("/fractals", new FractalsHandler());
        server.createContext("/dna", new DnaHandler());
        server.createContext("/grayscott", new GrayScottHandler());

        // Health check endpoint
        server.createContext("/ping", new PingHandler());
        server.start();
    }
}
