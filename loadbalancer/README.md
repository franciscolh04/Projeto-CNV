## Load Balancer

HTTP load balancer for distributing requests across EC2 worker instances with auto-scaling capabilities.

### How to build

1. Ensure `JAVA_HOME` is set to Java 11+ distribution
2. Run `mvn clean package`

### How to run

Start the load balancer:

```
java -jar target/loadBalancer-1.0.0-SNAPSHOT-jar-with-dependencies.jar
```

The server listens on port 8000 and accepts requests to:

- `/fractals` - Fractals computation workload
- `/dna` - DNA sequence alignment workload
- `/grayscott` - Gray-Scott reaction-diffusion simulation

### Features

- Real-time request routing to healthy workers
- Automatic scaling based on average load
- Health checking with worker recovery
- Work estimation for load balancing decisions
- AWS EC2 integration for worker provisioning
