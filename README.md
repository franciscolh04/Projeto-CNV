## Nature@Cloud

This project contains the following sub-projects:

1. `fractals` - the Julia Set fractals workload
2. `dna` - the DNA Genome matcher workload
3. `grayscott` - the Gray-Scott reaction-diffusion workload
4. `webserver` - the web server exposing the functionality of the workloads

Refer to the `README.md` files of the sub-projects to get more details about each specific sub-project.

### System Architecture and Cloud Configurations (Checkpoint)

Instead of relying solely on standard AWS-managed components (like AWS ELB and Auto Scaling Groups), we have migrated to a fully programmatic Java-based ecosystem (`LoadBalancer.java`, `AutoScaler.java`). This allows for dynamic, metrics-based scaling using JVM instruction counts.

#### 1. Instrumentation and Metrics Extraction
We utilize a custom Javassist agent (`ComplexityEstimator`) operating at the bytecode level, counting the number of executed JVM instructions per request. To support concurrent processing without metric corruption, stats are isolated using `ThreadLocal`. Upon completion, the count is appended to the HTTP response header `X-Request-Cost`. To prevent integer overflow, **1 Cost Unit = 1,000,000 executed JVM instructions**.

#### 2. Load Balancer configurations (Custom ELB Alternative)
Our custom Java Load Balancer intercepts requests and dynamically distributes them based on the estimated instruction cost of the request.
* **Routing Algorithm (Spreading Policy):** Routes the incoming request to the worker instance with the lowest current accumulated workload (`currentWork`).
* **Cost Estimation:** Uses a Cache (`metricsModelCache`) for known workloads, falling back to static math heuristics ($O(w \cdot h)$ for Fractals, $O(N^2)$ for DNA, $O(s^2 \cdot i)$ for Gray-Scott) if the request is unseen.

#### 3. Auto Scaler configurations (Custom ASG Alternative)
Our Java Auto Scaler runs every 15 seconds to evaluate the cluster's average work load and triggers AWS EC2 APIs accordingly. 
* **Scale-Out Threshold:** Average load > 150,000 metric units (~150 Billion JVM instructions). Triggers a new EC2 instance launch.
* **Scale-In Threshold:** Average load < 20,000 metric units (~20 Billion JVM instructions).
* **Graceful Draining & Termination:** Identifies the idle worker, drains its queues, and terminates it via `terminateInstanceByIp` AWS API.
* **Cooldown / Drain Timeout:** 120 seconds between scaling actions to prevent oscillating instantiations.

*(Note: Future DynamoDB/MSS integration has already been conceptually designed and prototyped via `MSSPoller.java`, which will replace the static heuristics with linear regression models over historical data).*

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
   * Launch your Permanent Master Instance in AWS.
   * SSH into it to install Java 11.
   * Transfer the credentials (`config.sh`), initialization files (`webserver-userdata.sh`), and the actual executable (`.jar`).
   * Boot up the Load Balancer server in the background and pipe its outputs to `master.log`.


**Note #1**: The Load Balancer will automatically bring up worker instances mapping the exact AMI you've just built. It scales outwards and inwards based on system load metrics (as defined in our AutoScaler algorithm).

**Note #2**: The Load Balancer natively performs health checks on active workers every 5 seconds; workers that miss 3 consecutive pings are automatically pulled from the rotation and forcefully terminated.
