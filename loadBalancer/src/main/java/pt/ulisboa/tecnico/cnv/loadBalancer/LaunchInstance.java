package pt.ulisboa.tecnico.cnv.loadBalancer;

import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.Base64;

/**
 * Manages EC2 instance lifecycle - launch and terminate instances.
 */
public class LaunchInstance {
    private static final Logger LOGGER = Logger.getLogger(LaunchInstance.class.getName());

    // TODO: ADD THIS TO THE CONFIGURATION FILE (SCRIPTS)
    private static final Region AWS_REGION = Region.US_EAST_1;
    private static final String AMI_ID = System.getenv("CNV_AMI_ID");
    private static final String INSTANCE_TYPE = System.getenv("CNV_INSTANCE_TYPE") != null ? 
            System.getenv("CNV_INSTANCE_TYPE") : "t3.micro";
    private static final String KEY_NAME = System.getenv("CNV_KEY_NAME") != null ? 
            System.getenv("CNV_KEY_NAME") : "mykeypair";
    private static final String SEC_GROUP_ID = System.getenv("CNV_SEC_GROUP_ID");
    
    private static final long WAIT_TIME_FOR_READY = 1000L * 60 * 5;
    private static final long CHECK_INTERVAL = 1000L * 5;

    private final Ec2Client ec2Client;

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

    public String launchInstance() {
        try {
            // Bash script to run Java with Javassist agent when machine boots
            String userDataScript = "#!/bin/bash\n" +
                    "su - ec2-user -c 'cd /home/ec2-user && java -javaagent:instrumentation-1.0.0-SNAPSHOT.jar=pt.ulisboa.tecnico.cnv.javassist.tools.ComplexityEstimator:pt.ulisboa.tecnico.cnv:output -cp webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar pt.ulisboa.tecnico.cnv.webserver.WebServer > worker.log 2>&1 &'\n";
            String encodedUserData = Base64.getEncoder().encodeToString(userDataScript.getBytes());

            RunInstancesRequest request = RunInstancesRequest.builder()
                    .imageId(AMI_ID)
                    .instanceType(INSTANCE_TYPE)
                    .minCount(1)
                    .maxCount(1)
                    .keyName(KEY_NAME)
                    .securityGroupIds(SEC_GROUP_ID)
                    .userData(encodedUserData)
                    .build();

            RunInstancesResponse response = ec2Client.runInstances(request);
            String instanceId = response.instances().get(0).instanceId();
            System.out.println("Instance launched: " + instanceId);
            
            String privateIp = waitForInstanceReady(instanceId);
            
            if (privateIp != null) {
                System.out.println("Instance ready with IP: " + privateIp);
                LoadBalancer.activeWorkers.put(privateIp, new WorkerNode(instanceId, privateIp));
                return instanceId;
            } else {
                System.out.println("Timeout waiting for instance IP");
                terminateInstance(instanceId);
                return null;
            }
            
        } catch (AwsServiceException e) {
            LOGGER.log(Level.SEVERE, "AWS error: " + e.awsErrorDetails().errorCode(), e);
            return null;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error", e);
            return null;
        }
    }

    private String waitForInstanceReady(String instanceId) {
        long startTime = System.currentTimeMillis();
        
        while (System.currentTimeMillis() - startTime < WAIT_TIME_FOR_READY) {
            try {
                DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                        .instanceIds(instanceId)
                        .build();
                
                DescribeInstancesResponse response = ec2Client.describeInstances(request);
                
                if (!response.reservations().isEmpty()) {
                    Instance instance = response.reservations().get(0).instances().get(0);
                    
                    if (instance.state().name().equals("running") && 
                        instance.privateIpAddress() != null && 
                        !instance.privateIpAddress().isEmpty()) {
                        return instance.privateIpAddress();
                    }
                }
                
                Thread.sleep(CHECK_INTERVAL);
                
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error checking instance status", e);
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
            System.out.println("Instance terminated: " + instanceId);
            
        } catch (AwsServiceException e) {
            LOGGER.log(Level.SEVERE, "Error terminating instance", e);
        }
    }

    public void terminateInstanceByIp(String privateIp) {
        try {
            DescribeInstancesRequest request = DescribeInstancesRequest.builder().build();
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
    }
}
