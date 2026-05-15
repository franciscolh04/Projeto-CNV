package pt.ulisboa.tecnico.cnv.loadbalancer;

import com.sun.net.httpserver.HttpServer;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.concurrent.*;
import java.util.Base64;
import java.util.Map;

import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;

public class LoadBalancer {
    public static String masterIp = "127.0.0.1";

    private static ConcurrentHashMap<String, Integer> activeWorkers = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<String, String> workerIds = new ConcurrentHashMap<>();
    
    //TODO-Rodrigo: Change these variables to environment variables or config file for better security and flexibility
    private static Ec2Client ec2Client;
    private static final String AMI_ID = "ami-0b1e1e92bd25b14de";
    private static final String INSTANCE_TYPE = "t3.micro";       
    private static final String KEY_NAME = "mykeypair";           
    private static final String SEC_GROUP_ID = "sg-01cf5faef7b0d263e"; 

    private static final int MAX_LOAD_PER_WORKER = 3; 
    private static final int MIN_LOAD_PER_WORKER = 1; 
    private static final long COOLDOWN_PERIOD_MS = 120000;
    private static long lastScaleActionTime = 0; 

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            masterIp = args[0];
        }
        System.out.println("Initializing Master Node (Load Balancer + Auto Scaler) with IP: " + masterIp);

        ec2Client = Ec2Client.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
                .build();

        HttpServer lbServer = HttpServer.create(new InetSocketAddress(8000), 0);
        lbServer.setExecutor(Executors.newCachedThreadPool());
        
        LoadBalancerHandler lbHandler = new LoadBalancerHandler(activeWorkers, workerIds);
        lbServer.createContext("/fractals", lbHandler);
        lbServer.createContext("/dna", lbHandler);
        lbServer.createContext("/grayscott", lbHandler);
        
        // Handshake
        lbServer.createContext("/register", new WorkerRegisterHandler(activeWorkers, workerIds));
        
        lbServer.start();
        System.out.println("Load Balancer active in port 8000.");

        ScheduledExecutorService autoScalerTimer = Executors.newScheduledThreadPool(1);
        autoScalerTimer.scheduleAtFixedRate(() -> evaluateScalingPolicies(), 10, 30, TimeUnit.SECONDS);

        ScheduledExecutorService healthCheckTimer = Executors.newScheduledThreadPool(1);
        healthCheckTimer.scheduleAtFixedRate(() -> performHealthChecks(), 5, 10, TimeUnit.SECONDS); 
    }

    private static void evaluateScalingPolicies() {
        int totalWorkers = activeWorkers.size();
        
        if (totalWorkers == 0) {
            System.out.println("[AS] Sistem empty! Launching first Worker node...");
            launchNewWorker();
            lastScaleActionTime = System.currentTimeMillis();
            return;
        }

        int totalSystemLoad = 0;
        String leastBusyWorkerIp = null;
        int minLoad = Integer.MAX_VALUE;

        for (Map.Entry<String, Integer> entry : activeWorkers.entrySet()) {
            int load = entry.getValue();
            totalSystemLoad += load;
            
            if (load < minLoad) {
                minLoad = load;
                leastBusyWorkerIp = entry.getKey();
            }
        }

        double averageLoad = (double) totalSystemLoad / totalWorkers;
        System.out.println(String.format("[AS] State -> Workers: %d | Total Load: %d | Média: %.2f", 
                                         totalWorkers, totalSystemLoad, averageLoad));

        if (System.currentTimeMillis() - lastScaleActionTime < COOLDOWN_PERIOD_MS) return;

        if (averageLoad > MAX_LOAD_PER_WORKER) {
            System.out.println("[AS] Traffic Alert! Scaling UP...");
            launchNewWorker();
            lastScaleActionTime = System.currentTimeMillis();
        } else if (averageLoad <= MIN_LOAD_PER_WORKER && totalWorkers > 1) {
            System.out.println("[AS] Underused Worker Node. Scaling DOWN...");
            if (leastBusyWorkerIp != null) {
                String instanceIdToKill = workerIds.get(leastBusyWorkerIp);
                activeWorkers.remove(leastBusyWorkerIp);
                workerIds.remove(leastBusyWorkerIp);
                terminateWorker(instanceIdToKill);
                lastScaleActionTime = System.currentTimeMillis();
            }
        }
    }

    private static String launchNewWorker() {
        try {
            String bashScript = "#!/bin/bash\n" +
                    "cd /home/ec2-user\n" +
                    "TOKEN=$(curl -X PUT \"http://169.254.169.254/latest/api/token\" -H \"X-aws-ec2-metadata-token-ttl-seconds: 21600\" -s)\n" +
                    "INSTANCE_ID=$(curl -H \"X-aws-ec2-metadata-token: $TOKEN\" -s http://169.254.169.254/latest/meta-data/instance-id)\n" +
                    "java -cp webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar pt.ulisboa.tecnico.cnv.webserver.WebServer " + masterIp + " $INSTANCE_ID > worker.log 2>&1 &\n";
            
            String encodedUserData = Base64.getEncoder().encodeToString(bashScript.getBytes());

            RunInstancesRequest runInstancesRequest = RunInstancesRequest.builder()
                    .imageId(AMI_ID)
                    .instanceType(INSTANCE_TYPE)
                    .minCount(1).maxCount(1)
                    .keyName(KEY_NAME)
                    .securityGroupIds(SEC_GROUP_ID)
                    .userData(encodedUserData)
                    .build();

            RunInstancesResponse response = ec2Client.runInstances(runInstancesRequest);
            String newInstanceId = response.instances().get(0).instanceId();
            System.out.println("[AWS] Success! New instance launching: " + newInstanceId);
            return newInstanceId;
        } catch (Exception e) {
            System.err.println("[AWS] Error while launching instance: " + e.getMessage());
            return null;
        }
    }

    private static void terminateWorker(String instanceId) {
        try {
            TerminateInstancesRequest terminateRequest = TerminateInstancesRequest.builder()
                    .instanceIds(instanceId).build();
            ec2Client.terminateInstances(terminateRequest);
            System.out.println("[AWS] Instance " + instanceId + " terminated with success.");
        } catch (Exception e) {
            System.err.println("[AWS] Error while trying to terminate instance " + instanceId + ": " + e.getMessage());
        }
    }

    private static void performHealthChecks() {
        if (activeWorkers.isEmpty()) return; 

        for (String workerIp : activeWorkers.keySet()) {
            try {
                URL healthUrl = new URL("http://" + workerIp + ":8000/health");
                HttpURLConnection conn = (HttpURLConnection) healthUrl.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(2000); 
                conn.setReadTimeout(2000);

                if (conn.getResponseCode() != 200) throw new RuntimeException("Error in Health Check");
                
            } catch (Exception e) {
                System.err.println("[HealthCheck] ALERT: Worker " + workerIp + " did not answer! Removed and Terminated.");

                String zombieInstanceId = workerIds.get(workerIp);
                activeWorkers.remove(workerIp);
                workerIds.remove(workerIp);
                
                if (zombieInstanceId != null) {
                    terminateWorker(zombieInstanceId);
                }
            }
        }
    }
}