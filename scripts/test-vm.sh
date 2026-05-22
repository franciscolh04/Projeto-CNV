#!/bin/bash

source config.sh

TESTS_DIR="../tests"
LB_IP=$(cat masterLB.dns)
PARALLEL_JOBS=${1:-2}

echo "===== CNV Heavy Load Test - Trigger Autoscaling ====="
echo "Target: $LB_IP"
echo "Heavy parallel jobs: $PARALLEL_JOBS"
echo ""

# Health check
echo "[1] Health Check..."
curl -s "http://$LB_IP:8000/ping" > /dev/null || { echo "Load Balancer is down"; exit 1; }
echo "✓ Load Balancer is up"
echo ""

cd "$TESTS_DIR"

echo "[2] Launching $PARALLEL_JOBS heavy workloads in parallel..."
echo "(This will exceed 150k unit threshold to trigger autoscaling)"
echo ""

# Launch heavy XL/L tests in parallel
for i in $(seq 1 $PARALLEL_JOBS); do
    bash req-fractals-XL.sh "$LB_IP" > /dev/null 2>&1 &
    bash req-grayscott-L.sh "$LB_IP" > /dev/null 2>&1 &
    bash req-dna-XL.sh "$LB_IP" > /dev/null 2>&1 &
done

echo "$((PARALLEL_JOBS * 3)) workloads spawned"
echo "Waiting for completion..."
echo ""

# Wait for all jobs
wait

echo "✓ All workloads completed"
echo ""
echo "Check master.log on the load balancer to see autoscaling in action"

