## Nature@Cloud

This project contains the following sub-projects:

1. `fractals` - the Julia Set fractals workload
2. `dna` - the DNA Genome matcher workload
3. `grayscott` - the Gray-Scott reaction-diffusion workload
4. `webserver` - the web server exposing the functionality of the workloads

Refer to the `README.md` files of the sub-projects to get more details about each specific sub-project.

### System Architecture and Cloud Configurations

The Nature@Cloud architecture consists of a programmatic Java-based ecosystem (`LoadBalancer.java`, `AutoScaler.java`) integrated with AWS Lambda (FaaS) and DynamoDB (MSS). It provides dynamic, fault-tolerant scaling using real-time JVM metrics.

#### 1. Dynamic Complexity Estimation & Metrics Extraction (Javassist)
A custom Javassist agent (`ComplexityEstimator`) operates at the bytecode level on the EC2 Web Workers. It counts the number of executed JVM instructions per request, assigning weights to different operations (e.g., Heavy Math vs Memory Allocation). Stats are isolated using `ThreadLocal`. Upon completion, the count is appended to the HTTP response header `X-Request-Cost`. To prevent integer overflow, **1 Cost Unit = 1,000,000 executed JVM instructions**.

**Why this approach is effective:** This bytecode-level instrumentation provides a deterministic measure of the actual computational effort required by a request, independent of external factors such as OS scheduling, noisy neighbors, or hardware variance. Unlike wall-clock time, which is volatile, JVM instruction counts offer a stable and granular metric. By assigning weights to "expensive" opcodes (like memory allocations and double-precision math), the system gains a high-fidelity proxy for real resource consumption (CPU and RAM), enabling the Load Balancer to make informed distribution decisions before physical machine saturation occurs.

#### 2. Load Balancer configurations (Custom ELB Alternative)
The Java Load Balancer intercepts requests and dynamically distributes them based on the estimated instruction cost of the request.
* **Routing Algorithm (Spreading Policy):** Routes the incoming request to the worker instance with the lowest current accumulated workload (`currentWork`).
* **Continuous Learning (Exponential Moving Average):** Estimates are dynamic. The Load Balancer uses an EMA algorithm (stored in `metricsModelCache`) that updates instantly via a Fast Loop (Alpha=0.05) based on returned headers, and continuously via a Slow Loop (`MSSPoller.java`) fetching exact historical averages from DynamoDB.
* **Workload-Specific Heuristics:** The system utilizes distinct estimation models tailored to the characteristics of each algorithm. Fractals are predicted via area complexity, Gray-Scott uses topology-specific composite keys (e.g., varying by seeding mode), and DNA implements multi-bucket EMA models based on sequence characteristics and early-exit conditions. An Exact LRU Cache acts as a failsafe for repeated unpredictable payload combinations.

#### 3. AWS Lambda Integration (FaaS)
To amortize EC2 startup latencies and conserve server capacity, **low-complexity requests are automatically offloaded to AWS Lambda functions**. The LB parses the incoming request parameters, applies the EMA calculation, and if the total estimated cost is below `HARD_LIMIT_SCORE`, the request is sent asynchronously to Lambda via `LambdaAsyncClient`. Lambda code executes natively without Javassist instrumentation.

#### 4. AWS DynamoDB Integration (MSS)
At the end of each EC2 execution, the Web Workers write the actual JVM execution cost to a DynamoDB table (`RequestHistory`). The `MSSPoller` thread polls this database periodically to reconstruct the baseline complexity coefficients across the entire cluster, ensuring the Load Balancer learns from real-world execution metrics without overloading individual instances.

#### 5. Fault Tolerance & Graceful Degradation
* **Queue-based Retries:** If an EC2 Worker crashes mid-execution (resulting in an exception or timeout), the Load Balancer captures the failure, intercepts the `CompletableFuture` response, and re-enqueues the job at the end of the `pendingQueue`. The request is safely dispatched to a healthy node without the client seeing an error.
* **Graceful Draining:** During scale-in events, targeted workers are moved to a `drainingWorkers` table. The LB stops sending new traffic, and the Auto Scaler waits for active requests to finish before calling the termination API, guaranteeing zero dropped computations.

#### 6. Auto Scaler & Custom OS Telemetry
Instead of relying on AWS CloudWatch (which has 1-minute resolution limits and added costs), a custom `CPUMonitor` runs on the Web Workers. It samples OS-level Thread CPU loads every 500ms and returns a sliding 90-second average to the Load Balancer every 5 seconds during `/ping` health checks.
* **Metric Score:** The Auto Scaler evaluates the cluster every 15 seconds based on a hybrid formula: `(0.4 * CPU) + (0.6 * Unprocessed Work Units)`.
* **Scale-Out Threshold:** Average Score > 0.7 triggers a new EC2 instance launch.
* **Scale-In Threshold:** Average Score < 0.5 triggers graceful worker draining.

---

### How to build everything

1. Make sure your `JAVA_HOME` environment variable is set to Java 11+ distribution
2. Run `mvn clean package`

### How to run and test locally

Before deploying to AWS, you can test the workloads and instrumentation locally to avoid cloud costs.

#### 1. Running individual workloads via CLI (No Web Server)
You can run each workload directly from the command line after building the project:

* **Fractals:**
  ```bash
  java -cp fractals/target/fractals-1.0.0-SNAPSHOT-jar-with-dependencies.jar pt.ulisboa.tecnico.cnv.fractals.FractalsHandler 800 600 100 ./julia.png
  ```
* **GrayScott:**
  ```bash
  java -cp grayscott/target/grayscott-1.0.0-SNAPSHOT-jar-with-dependencies.jar pt.ulisboa.tecnico.cnv.grayscott.GrayScottHandler 256 10000 0.030 0.062 false stripe grayscott.png
  ```
* **DNA:**
  ```bash
  java -cp dna/target/dna-1.0.0-SNAPSHOT-jar-with-dependencies.jar pt.ulisboa.tecnico.cnv.dna.DnaHandler "human_HBB:ATGGTGCATCTGACTCCTGAGGAGAAGTCTGCCGTTACTGCCCTGTGGGGCAAGGTGAACGTGGATGAAGTTGGTGGTGAGGCCCTGGGCAGGCTGCTGGTGGTCTACCCTTGGACCCAGAGGTTCTTTGAGTCCTTTGGGGATCTGTCCACTCCTGATGCTGTTATGGGCAACCCTAAGGTGAAGGCTCATGGCAAGAAAGTGCTCGGTGCCTTTAGTGATGGCCTGGCTCACCTGGACAACCTCAAGGGCACCTTTGCCACACTGAGTGAGCTGCACTGTGACAAGCTGCACGTGGATCCTGAGAACTTCAGGCTCCTGGGCAACGTGCTGGTCTGTGTGCTGGCCCATCACTTTGGCAAAGAATTCACCCCACCAGTGCAGGCTGCCTATCAGAAAGTGGTGGCTGGTGTGGCTAATGCCCTGGCCCACAAGTATCACTAA" "chimpanzee_HBB:ATGGTGCACCTGACTCCTGAGGAGAAGTCTGCCGTTACTGCCCTGTGGGGCAAGGTGAACGTGGATGAAGTTGGTGGTGAGGCCCTGGGCAGGCTGCTGGTGGTCTACCCTTGGACCCAGAGGTTCTTTGAGTCCTTTGGGGATCTGTCCACTCCTGATGCTGTTATGGGCAACCCTAAGGTGAAGGCTCATGGCAAGAAAGTGCTCGGTGCCTTTAGTGATGGCCTGGCTCACCTGGACAACCTCAAGGGCACCTTTGCCACACTGAGTGAGCTGCACTGTGACAAGCTGCACGTGGATCCTGAGAACTTCAGGCTCCTGGGCAACGTGCTGGTCTGTGTGCTGGCCCATCACTTTGGCAAAGAATTCACCCCACCAGTGCAGGCTGCCTATCAGAAAGTGGTGGCTGGTGTGGCTAATGCCCTGGCCCACAAGTATCACTAA" 5 False
  ```

#### 2. Running the Web Server with Javassist Instrumentation
To test the Load Balancer flow and the complexity metrics (`X-Request-Cost`), run the Web Server with the Javassist agent attached. *Note: You must provide dummy Load Balancer IP and Instance ID arguments.*

```bash
java -javaagent:instrumentation/target/instrumentation-1.0.0-SNAPSHOT.jar=ComplexityEstimator:pt.ulisboa.tecnico.cnv:output \
     -cp webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     pt.ulisboa.tecnico.cnv.webserver.WebServer 127.0.0.1 local-instance-1
```

#### 3. Sending Requests via `curl`
With the Web Server running (in another terminal tab), you can trigger the workloads and read the `X-Request-Cost` metric generated by the Javassist instrumentation:

* **Fractals:**
  ```bash
  curl -s -D - "http://127.0.0.1:8000/fractals?w=800&h=600&iterations=100" -o /dev/null | grep -i "x-request-cost"
  ```
* **GrayScott:**
  *(Use a smaller size locally to avoid waiting too long)*
  ```bash
  curl -s -D - "http://127.0.0.1:8000/grayscott?size=64&maxIterations=1000&f=0.030&k=0.062&stopOnExtinction=false&seedMode=stripe" -o /dev/null | grep -i "x-request-cost"
  ```
* **DNA:**
  ```bash
  SEQ1="human_HBB:ATGGTGCATCTGACTCCTGAGGAGAAGTCTGCCGTTACTGCCCTGTGGGGCAAGGTGAACGTGGATGAAGTTGGTGGTGAGGCCCTGGGCAGGCTGCTGGTGGTCTACCCTTGGACCCAGAGGTTCTTTGAGTCCTTTGGGGATCTGTCCACTCCTGATGCTGTTATGGGCAACCCTAAGGTGAAGGCTCATGGCAAGAAAGTGCTCGGTGCCTTTAGTGATGGCCTGGCTCACCTGGACAACCTCAAGGGCACCTTTGCCACACTGAGTGAGCTGCACTGTGACAAGCTGCACGTGGATCCTGAGAACTTCAGGCTCCTGGGCAACGTGCTGGTCTGTGTGCTGGCCCATCACTTTGGCAAAGAATTCACCCCACCAGTGCAGGCTGCCTATCAGAAAGTGGTGGCTGGTGTGGCTAATGCCCTGGCCCACAAGTATCACTAA"
  SEQ2="chimpanzee_HBB:ATGGTGCACCTGACTCCTGAGGAGAAGTCTGCCGTTACTGCCCTGTGGGGCAAGGTGAACGTGGATGAAGTTGGTGGTGAGGCCCTGGGCAGGCTGCTGGTGGTCTACCCTTGGACCCAGAGGTTCTTTGAGTCCTTTGGGGATCTGTCCACTCCTGATGCTGTTATGGGCAACCCTAAGGTGAAGGCTCATGGCAAGAAAGTGCTCGGTGCCTTTAGTGATGGCCTGGCTCACCTGGACAACCTCAAGGGCACCTTTGCCACACTGAGTGAGCTGCACTGTGACAAGCTGCACGTGGATCCTGAGAACTTCAGGCTCCTGGGCAACGTGCTGGTCTGTGTGCTGGCCCATCACTTTGGCAAAGAATTCACCCCACCAGTGCAGGCTGCCTATCAGAAAGTGGTGGCTGGTGTGGCTAATGCCCTGGCCCACAAGTATCACTAA"
  curl -s -D - "http://127.0.0.1:8000/dna?seq1=$SEQ1&seq2=$SEQ2&minLength=5&stopOnFirst=False" -o /dev/null | grep -i "x-request-cost"
  ```

### How to deploy the Load Balancer

All deployment logic is automated through a set of companion scripts inside the `scripts/` directory.

1. **Configure AWS Details:** Make sure to correctly set up the `scripts/config.sh` file with your AWS credentials and configuration variables. Copy the provided template `scripts/config.sh.example` as a starting guide.
2. **Build and Create AMI:** Traverse to the `scripts/` directory and run `./create-image.sh`. This script will launch a temporary instance, compile the codebase, execute `install-vm.sh` to configure all prerequisites on the machine, create an AWS AMI named *CNV-Image*, grab its ID into `image.id`, and finally terminate the temporary instance.
3. **Deploy the Master Instance (LB + AS):** Execute `./deploy-lb.sh`. This script will:
   * Perform a local build of the `loadbalancer` module.
   * Create the `RequestHistory` DynamoDB table (via `create-table.sh`).
   * Register the 3 AWS Lambda FaaS functions (via `function-register.sh`).
   * Launch your Permanent Master Instance in AWS.
   * SSH into it to install Java 11.
   * Transfer the credentials (`config.sh`), initialization files (`webserver-userdata.sh`), and the actual executable (`.jar`).
   * Boot up the Load Balancer server in the background and pipe its outputs to `master.log`.


**Note #1**: The Load Balancer will automatically bring up worker instances mapping the exact AMI you've just built. It scales outwards and inwards based on system load metrics (as defined in our AutoScaler algorithm).

**Note #2**: The Load Balancer natively performs health checks on active workers every 5 seconds; workers that miss 3 consecutive pings are automatically pulled from the rotation and forcefully terminated.
