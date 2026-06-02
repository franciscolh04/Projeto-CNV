#!/bin/bash

# Test configurations
TESTS_DIR="tests"
AGENTS=("ComplexityEstimator")

# Execute HTTP request and log custom metrics
run_request() {
    local WORKLOAD=$1
    local PARAMS=$2
    local CSV_FILE=$3
    local URL="http://127.0.0.1:8000/$WORKLOAD?$PARAMS"

    # Fetch latency and dump HTTP headers
    LATENCY=$(curl -s -w "%{time_total}" -D headers.txt -o /dev/null "$URL")
    
    # Extract standard metric header
    METRIC_VAL=$(grep -i "X-Request-Cost" headers.txt | awk '{print $2}' | tr -d '\r' | head -n 1)

    # Fallback to zero on empty response
    if [ -z "$METRIC_VAL" ]; then
        METRIC_VAL=0
    fi

    # Record data point
    echo "$WORKLOAD,\"$PARAMS\",$METRIC_VAL,$LATENCY" >> "$CSV_FILE"
    echo "  -> Completed: ${LATENCY}s | Cost: $METRIC_VAL"
}

# Terminate active JVM instances
killall java 2>/dev/null

for AGENT in "${AGENTS[@]}"; do
    echo "========================================================="
    echo "STARTING GRID SEARCH FOR: $AGENT"
    echo "========================================================="
    
    CSV_FILE="results_${AGENT}.csv"
    
    # Setup CSV header
    echo "workload,params,metric,time" > "$CSV_FILE"
    
    # Launch backend with Javassist agent configuration
    echo "Starting JVM with agent..."
    java -cp "webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar" \
        -javaagent:"instrumentation/target/instrumentation-1.0.0-SNAPSHOT.jar"=${AGENT}:pt.ulisboa.tecnico.cnv.fractals,pt.ulisboa.tecnico.cnv.grayscott,pt.ulisboa.tecnico.cnv.dna:output \
        pt.ulisboa.tecnico.cnv.webserver.WebServer 127.0.0.1 local-test-instance > server.log 2>&1 &
    
    SERVER_PID=$!
    
    # Wait for init
    sleep 6

    # Health check
    if ! curl -s --max-time 2 "http://127.0.0.1:8000/ping" > /dev/null; then
        echo "ERROR: Server failed to start! Check server.log."
        kill -9 $SERVER_PID 2>/dev/null
        exit 1
    fi

    echo "Server is running perfectly (PID: $SERVER_PID)"

    # Workload: Gray-Scott parameter sweep
    echo ">> Testing Gray-Scott..."
    for FK_PAIR in "0.030:0.062" "0.010:0.050"; do
        F=$(echo "$FK_PAIR" | cut -d: -f1)
        K=$(echo "$FK_PAIR" | cut -d: -f2)
        
        for SIZE in 128 256 512; do
            for ITERS in 100 1000 1500 5000; do
                for EXT in true false; do
                    for MODE in center ring stripe; do
                        echo "  -> Sending: [grayscott] Size=$SIZE Iters=$ITERS F=$F K=$K Ext=$EXT Mode=$MODE"
                        run_request "grayscott" "size=$SIZE&maxIterations=$ITERS&f=$F&k=$K&stopOnExtinction=$EXT&seedMode=$MODE" "$CSV_FILE"
                    done
                done
            done
        done
    done

    # Workload: Julia Fractal parameter sweep
    echo ">> Testing Fractals..."
    for DIM in "400:300" "800:600" "1920:1080" "7500:6000"; do
        W=$(echo "$DIM" | cut -d: -f1)
        H=$(echo "$DIM" | cut -d: -f2)
        
        for ITERS in 100 500 1000 1000000; do
             echo "  -> Sending: [fractals] W=$W H=$H Iters=$ITERS"
             run_request "fractals" "w=$W&h=$H&iterations=$ITERS" "$CSV_FILE"
        done
    done

    # Workload: DNA Sequence Matcher sweep
    echo ">> Testing DNA..."
    DNA_PAIRS=(
        "human-mc-10k.fasta:sars-10k.fasta" 
        "genome-salmonella-enterica-20k.fasta:genome-klebsiella-pneumoniae-20k.fasta"
        "genome-escherichia-coli-25k.fasta:genome-salmonella-enterica-25k.fasta"
    )

    for PAIR in "${DNA_PAIRS[@]}"; do
        FILE1=$(echo "$PAIR" | cut -d: -f1)
        FILE2=$(echo "$PAIR" | cut -d: -f2)
        
        for MIN_LEN in 5 10 250; do
            for STOP in True False; do
                echo "  -> Sending: [dna] Files=$FILE1 vs $FILE2 MinLen=$MIN_LEN Stop=$STOP"
                
                # Encode file bodies safely into query string
                LATENCY=$(curl -G -s -w "%{time_total}" -D headers.txt -o /dev/null \
                    --data-urlencode "seq1@$TESTS_DIR/$FILE1" \
                    --data-urlencode "seq2@$TESTS_DIR/$FILE2" \
                    --data-urlencode "minLength=$MIN_LEN" \
                    --data-urlencode "stopOnFirst=$STOP" \
                    "http://127.0.0.1:8000/dna")
                
                METRIC_VAL=$(grep -i "X-Request-Cost" headers.txt | awk '{print $2}' | tr -d '\r' | head -n 1)
                if [ -z "$METRIC_VAL" ]; then METRIC_VAL=0; fi
                
                echo "dna,\"files=$FILE1|$FILE2&minLength=$MIN_LEN&stopOnFirst=$STOP\",$METRIC_VAL,$LATENCY" >> "$CSV_FILE"
                echo "     [Done] Time: ${LATENCY}s | Cost: $METRIC_VAL"
            done
        done
    done

    # Teardown current cluster node
    echo "Stopping JVM ($SERVER_PID)..."
    kill $SERVER_PID
    sleep 3
done

echo "Test suite completed successfully! Check the new results_${AGENT}.csv file."