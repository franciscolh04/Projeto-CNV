## Load Balancer

HTTP load balancer for distributing requests across EC2 worker instances with auto-scaling capabilities.

### How to build

1. Ensure `JAVA_HOME` is set to Java 11+ distribution
2. Run `mvn clean package`

### How to run

Start the load balancer:

```
java -jar target/loadbalancer-1.0.0-SNAPSHOT-jar-with-dependencies.jar
```

The server listens on port 8000 and accepts requests to:

- `/fractals` - Fractals computation workload
- `/dna` - DNA sequence alignment workload
- `/grayscott` - Gray-Scott reaction-diffusion simulation

### Features

- **Real-time Request Routing:** Distributes incoming requests to healthy EC2 workers using a "Lowest Work First" policy based on estimated instruction counts.
- **Dynamic Auto-Scaling:** Automatically provisions and terminates EC2 instances based on a **Weighted Cluster Score** (40% CPU, 60% Relative Work).
- **Graceful Draining:** Safely decommissions workers during scale-in by allowing them to finish pending tasks before termination.
- **Health Checking:** Continuous monitoring of worker health every 5 seconds, with automatic recovery and failover for failed instances.
- **Predictive Work Estimation:** Uses Exponential Moving Averages (EMA) and DynamoDB history to estimate the computational cost of requests before execution.
- **AWS Integration:** Native integration with AWS EC2 (Lifecycle Management), Lambda (FaaS offloading), and DynamoDB (Metric Storage).
