#!/bin/bash
set -euo pipefail

source config.sh

REMOTE_HOST=$(cat instance.dns)

# Install java
ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" ec2-user@"$REMOTE_HOST" \
    "sudo yum update -y && sudo yum install -y java-11-amazon-corretto-devel.x86_64"

# Install Web Server and Javassist
scp -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" "$DIR/../webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar" ec2-user@"$REMOTE_HOST":/home/ec2-user/
scp -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" "$DIR/../instrumentation/target/instrumentation-1.0.0-SNAPSHOT.jar" ec2-user@"$REMOTE_HOST":/home/ec2-user/

# Create logs directory
ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" ec2-user@"$REMOTE_HOST" \
    "mkdir -p /home/ec2-user/logs && chmod 755 /home/ec2-user/logs"

echo "Installation complete. AMI is ready for deployment."