## Nature@Cloud

This project contains the following sub-projects:

1. `fractals` - the Julia Set fractals workload
2. `dna` - the DNA Genome matcher workload
3. `grayscott` - the Gray-Scott reaction-diffusion workload
4. `webserver` - the web server exposing the functionality of the workloads

Refer to the `README.md` files of the sub-projects to get more details about each specific sub-project.

### How to build everything

1. Make sure your `JAVA_HOME` environment variable is set to Java 11+ distribution
2. Run `mvn clean package`

### How to deploy the Load Balancer
1. Make sure to correctly set the `config.sh` file with your AWS credentials and configuration, an `config.sh.example` file is provided as a template.
2. Run `./build-worker-ami.sh` to create the AMI for the worker instances.
3. Run `./deploy-lb.sh` to deploy the Load Balancer. This script will also start the Load Balancer server and every log will be available in `master.log` on the instance.

Note: The Load Balancer will automatically bring up an 2 worker instance, but it will scale up and down based on the load, that is currently computed based on simultaneously requests load. You can check the logs to see when new workers are launched or terminated.
Note: The Load Balancer is configured to use a maximum load of 3 requests per worker, and a cooldown period of 2 minutes between scaling actions. You can adjust these parameters in the `LoadBalancer.java` file if needed.
Note: The Load Balancer performs health checks on the workers every 30 seconds, if a worker fails to respond to the health check, it will be removed from the pool and terminated.
Note: The Load Balancer uses a dynamic Least Connections / Least Load strategy. Instead of sequentially assigning requests (round-robin), it continuously monitors the current active load of each worker and always routes the incoming request to the worker with the lowest current load, ensuring optimal resource distribution.
Note: The Load Balancer estimates the load of each request as 1 unit, and it decreases the load by 1 unit when the request is completed. This is a simple estimation, that needs to be changed and done accordingly to the project requirements.

Logs de teste de carga executado:
[AWS] Instance i-03bf027c87bb54feb terminated with success.
[AS] State -> Workers: 1 | Total Load: 0 | Média: 0.00
[AS] State -> Workers: 1 | Total Load: 0 | Média: 0.00
[AS] State -> Workers: 1 | Total Load: 0 | Média: 0.00
[AS] State -> Workers: 1 | Total Load: 0 | Média: 0.00
[AS] State -> Workers: 1 | Total Load: 0 | Média: 0.00
[AS] State -> Workers: 1 | Total Load: 0 | Média: 0.00
[AS] State -> Workers: 1 | Total Load: 0 | Média: 0.00
[AS] State -> Workers: 1 | Total Load: 0 | Média: 0.00
[AS] State -> Workers: 1 | Total Load: 0 | Média: 0.00
[LB-RADAR] Received request: /fractals?w=40000&h=40000&n=20000 from /148.71.120.63:50718
[ALOCATION] Forwarding request to Worker i-044c130d05c65c820 at 172.31.18.187 with current load: 1
[AS] State -> Workers: 1 | Total Load: 1 | Média: 1.00
[LB-RADAR] Received request: /fractals?w=40000&h=40000&n=20000 from /148.71.120.63:12431
[ALOCATION] Forwarding request to Worker i-044c130d05c65c820 at 172.31.18.187 with current load: 2
[LB-RADAR] Received request: /fractals?w=40000&h=40000&n=20000 from /148.71.120.63:50726
[ALOCATION] Forwarding request to Worker i-044c130d05c65c820 at 172.31.18.187 with current load: 3
[AS] State -> Workers: 1 | Total Load: 3 | Média: 3.00
[LB-RADAR] Received request: /fractals?w=40000&h=40000&n=20000 from /148.71.120.63:12465
[ALOCATION] Forwarding request to Worker i-044c130d05c65c820 at 172.31.18.187 with current load: 4
[AS] State -> Workers: 1 | Total Load: 4 | Média: 4.00
[AS] Traffic Alert! Scaling UP...
[AWS] Success! New instance launching: i-0c139c7e90d7bfe0d
[LB-RADAR] Received request: /fractals?w=40000&h=40000&n=20000 from /148.71.120.63:12473
[ALOCATION] Forwarding request to Worker i-044c130d05c65c820 at 172.31.18.187 with current load: 5
[AS] State -> Workers: 1 | Total Load: 5 | Média: 5.00
[Handshake] New Worker ready! IP: 172.31.16.156 | ID: i-0c139c7e90d7bfe0d
[AS] State -> Workers: 2 | Total Load: 5 | Média: 2.50
[LB-RADAR] Received request: /fractals?w=400&h=400&n=200 from /148.71.120.63:50762
[ALOCATION] Forwarding request to Worker i-0c139c7e90d7bfe0d at 172.31.16.156 with current load: 1
[ALOCATION] Completed request for Worker i-0c139c7e90d7bfe0d at 172.31.16.156 with current load: 0