#!/bin/bash

# Load credentials and Security Group ID
source config.sh

echo "==================================================="
echo " Configuring Firewall: $AWS_SECURITY_GROUP"
echo "==================================================="

# 1. Open port 22 for SSH access
echo "-> Opening port 22 (SSH)..."
aws ec2 authorize-security-group-ingress \
    --group-id $AWS_SECURITY_GROUP \
    --protocol tcp \
    --port 22 \
    --cidr 0.0.0.0/0 \
    2>/dev/null || echo "   (Warning: Port 22 was already open, ignoring.)"

# 2. Open port 8000 for the Master Server / Load Balancer
echo "-> Opening port 8000 (Web Traffic)..."
aws ec2 authorize-security-group-ingress \
    --group-id $AWS_SECURITY_GROUP \
    --protocol tcp \
    --port 8000 \
    --cidr 0.0.0.0/0 \
    2>/dev/null || echo "   (Warning: Port 8000 was already open, ignoring.)"

echo "==================================================="
echo " SUCCESS! Your firewall is ready to use."
echo "==================================================="