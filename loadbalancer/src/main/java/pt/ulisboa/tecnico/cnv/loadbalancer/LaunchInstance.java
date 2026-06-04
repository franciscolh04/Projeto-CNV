package pt.ulisboa.tecnico.cnv.loadbalancer;

import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Manages EC2 instance lifecycle - launch and terminate instances.
 */
public class LaunchInstance {
    private static final Logger LOGGER = Logger.getLogger(LaunchInstance.class.getName());

    private static final Region AWS_REGION = Region.US_EAST_1;
    private static final String AMI_ID = System.getenv("CNV_AMI_ID");
    private static final String INSTANCE_TYPE = System.getenv("CNV_INSTANCE_TYPE") != null ? 
            System.getenv("CNV_INSTANCE_TYPE") : "t3.micro";
    private static final String KEY_NAME = System.getenv("CNV_KEY_NAME") != null ? 
            System.getenv("CNV_KEY_NAME") : "mykeypair";
    private static final String SEC_GROUP_ID = System.getenv("CNV_SEC_GROUP_ID");
    private static final String USERDATA_SCRIPT_PATH = System.getenv("CNV_USERDATA_SCRIPT_PATH") != null ?
            System.getenv("CNV_USERDATA_SCRIPT_PATH") : "./scripts/webserver-userdata.sh";
    
    private static final long WAIT_TIME_FOR_READY = 1000L * 60 * 5;
    private static final long CHECK_INTERVAL = 1000L * 5;

    private final Ec2Client ec2Client;

    private final ScheduledExecutorService watchdogScheduler = Executors.newScheduledThreadPool(2);

    public LaunchInstance() {

        if (AMI_ID == null || SEC_GROUP_ID == null) {
            LOGGER.severe("[LI] Environment variables CNV_AMI_ID and CNV_SEC_GROUP_ID must be set");
            throw new IllegalStateException("[LI] Missing required environment variables");
        }

        this.ec2Client = Ec2Client.builder()
                .region(AWS_REGION)
                .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
                .build();
    }

    public List<String> launchInstances(String masterIP, int count) {
        List<String> launchedInstanceIds = new java.util.ArrayList<>();
        try {

            LOGGER.info("[LI] Started launching new instance with master IP: " + masterIP);

            // Read and prepare userdata script with master IP injection
            String userDataScript = prepareUserDataScript(masterIP);
            
            if (userDataScript == null) {
                LOGGER.severe("[LI] Failed to prepare userdata script");
                return null;
            }
            
            // Encode userdata in Base64
            String encodedUserData = Base64.getEncoder().encodeToString(userDataScript.getBytes());

            RunInstancesRequest request = RunInstancesRequest.builder()
                    .imageId(AMI_ID)
                    .instanceType(INSTANCE_TYPE)
                    .minCount(count)
                    .maxCount(count)
                    .keyName(KEY_NAME)
                    .securityGroupIds(SEC_GROUP_ID)
                    .userData(encodedUserData)
                    .build();

            RunInstancesResponse response = ec2Client.runInstances(request);
        
            for (Instance instance : response.instances()) {
                String instanceId = instance.instanceId();
                launchedInstanceIds.add(instanceId);
                System.out.println("[LI] Instance launched in AWS: " + instanceId);
                scheduleInstanceCheck(instanceId, System.currentTimeMillis(), 1);
            }

            return launchedInstanceIds;
            
        } catch (AwsServiceException e) {
            LOGGER.log(Level.SEVERE, "AWS error: " + e.awsErrorDetails().errorCode(), e);
            return null;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error", e);
            return null;
        }
    }

    /**
     * Reads the webserver-userdata.sh script and injects the master IP
     */
    private String prepareUserDataScript(String masterIP) {
        try {
            // Read the userdata script
            String scriptContent = new String(Files.readAllBytes(Paths.get(USERDATA_SCRIPT_PATH)));
            
            // Replace placeholder with actual master IP
            scriptContent = scriptContent.replace("$MASTER_PRIVATE_IP", masterIP);
            
            LOGGER.info("[LI] Userdata script prepared with master IP: " + masterIP);
            return scriptContent;
            
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error reading userdata script from " + USERDATA_SCRIPT_PATH, e);
            return null;
        }
    }

    private String waitForInstanceReady(String instanceId) {
        long startTime = System.currentTimeMillis();
        long checkInterval = CHECK_INTERVAL;
        int attemptCount = 0;
        
        // Wait a bit before first check - AWS needs time to propagate the instance
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        while (System.currentTimeMillis() - startTime < WAIT_TIME_FOR_READY) {
            attemptCount++;
            try {
                DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                        .instanceIds(instanceId)
                        .build();
                
                DescribeInstancesResponse response = ec2Client.describeInstances(request);
                
                if (!response.reservations().isEmpty()) {
                    Instance instance = response.reservations().get(0).instances().get(0);
                    
                    if (instance.state().name() == InstanceStateName.RUNNING && 
                        instance.privateIpAddress() != null && 
                        !instance.privateIpAddress().isEmpty()) {
                        return instance.privateIpAddress();
                    }
                }
                
                // Reset backoff on success (even if not ready yet)
                checkInterval = CHECK_INTERVAL;
                
                Thread.sleep(checkInterval);
                
            } catch (AwsServiceException e) {
                // Handle transient errors (instance not yet visible in EC2 service)
                int statusCode = e.statusCode();
                String errorCode = e.awsErrorDetails() != null ? 
                    e.awsErrorDetails().errorCode() : "Unknown";
                
                if ((statusCode == 400 || statusCode == 404) && 
                    (errorCode.contains("does not exist") || errorCode.equals("InvalidInstanceID.NotFound"))) {
                    // This is expected when instance just launched - AWS needs time to propagate
                    LOGGER.log(Level.FINE, "[LI] Instance " + instanceId + " not yet visible (attempt " + 
                        attemptCount + "), retrying...");
                    // Use exponential backoff with jitter, capped at 10 seconds
                    long jitter = (long)(Math.random() * 1000);
                    checkInterval = Math.min(1000L * (1L << Math.min(attemptCount / 3, 3)), 10000L) + jitter;
                } else {
                    // Unexpected AWS error
                    LOGGER.log(Level.WARNING, "[LI] AWS error checking instance status (code: " + 
                        statusCode + ", error: " + errorCode + ")", e);
                    checkInterval = CHECK_INTERVAL;
                }
                
                try {
                    Thread.sleep(checkInterval);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "[LI] Unexpected error checking instance status", e);
                try {
                    Thread.sleep(CHECK_INTERVAL);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        return null;
    }

    public void terminateInstance(String instanceId) {
        try {
            TerminateInstancesRequest request = TerminateInstancesRequest.builder()
                    .instanceIds(instanceId)
                    .build();
            
            ec2Client.terminateInstances(request);
            System.out.println("[LI] Instance terminated: " + instanceId);
            
        } catch (AwsServiceException e) {
            LOGGER.log(Level.SEVERE, "Error terminating instance", e);
        }
    }

    public void terminateInstanceByIp(String privateIp) {
        try {
            DescribeInstancesRequest request = DescribeInstancesRequest
                .builder()
                .filters(Filter.builder().name("private-ip-address").values(privateIp).build())
                .build();
            DescribeInstancesResponse response = ec2Client.describeInstances(request);
            
            for (Reservation reservation : response.reservations()) {
                for (Instance instance : reservation.instances()) {
                    if (privateIp.equals(instance.privateIpAddress())) {
                        terminateInstance(instance.instanceId());
                        return;
                    }
                }
            }
        } catch (AwsServiceException e) {
            LOGGER.log(Level.SEVERE, "Error terminating instance by IP", e);
        }
    }

    public void close() {
        if (ec2Client != null) {
            ec2Client.close();
        }
        // Encerrar o scheduler no shutdown
        if (watchdogScheduler != null) {
            watchdogScheduler.shutdown();
        }
    }

    private void scheduleInstanceCheck(String instanceId, long startTime, int attemptCount) {
        // Check if we've exceeded the maximum wait time before scheduling the next check
        if (System.currentTimeMillis() - startTime > WAIT_TIME_FOR_READY) {
            System.out.println("[LI] Timeout waiting for instance IP: " + instanceId);
            terminateInstance(instanceId);
            if (LoadBalancer.autoScaler != null) {
                LoadBalancer.autoScaler.removeWarmingUpInstance(instanceId);
            }
            return;
        }

        // Schedule the next check after a delay
        watchdogScheduler.schedule(() -> {
            try {
                DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                        .instanceIds(instanceId)
                        .build();
                
                DescribeInstancesResponse response = ec2Client.describeInstances(request);
                
                if (!response.reservations().isEmpty()) {
                    Instance instance = response.reservations().get(0).instances().get(0);
                    
                    if (instance.state().name() == InstanceStateName.RUNNING && 
                        instance.privateIpAddress() != null && 
                        !instance.privateIpAddress().isEmpty()) {
                        
                        System.out.println("[LI] Instance " + instanceId + " is ready with IP: " + instance.privateIpAddress());
                        // Instance is ready, stop checking
                        return; 
                    }
                }
                
                LOGGER.fine("[LI] Instance " + instanceId + " not ready yet (attempt " + attemptCount + "), scheduling next check...");
                // if not ready, schedule another check with exponential backoff
                scheduleInstanceCheck(instanceId, startTime, attemptCount + 1);
                
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "[LI] Error checking instance status for " + instanceId, e);
                // On error, also schedule another check with backoff
                scheduleInstanceCheck(instanceId, startTime, attemptCount + 1);
            }
        }, CHECK_INTERVAL, TimeUnit.MILLISECONDS);
    }
}
