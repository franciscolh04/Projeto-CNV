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

# Setup web server to start on instance launch via systemd.
cmd="echo '[Unit]
Description=CNV WebServer
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=ec2-user
WorkingDirectory=/home/ec2-user
ExecStart=/bin/sh -c '\''

TOKEN=\$(/usr/bin/curl -X PUT \"http://169.254.169.254/latest/api/token\" \
-H \"X-aws-ec2-metadata-token-ttl-seconds: 21600\" -s)

INSTANCE_ID=\$(/usr/bin/curl \
-H \"X-aws-ec2-metadata-token: \$TOKEN\" \
-s http://169.254.169.254/latest/meta-data/instance-id)

exec /usr/bin/java \
-jar /home/ec2-user/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
$MASTER_PRIVATE_IP \$INSTANCE_ID >> /home/ec2-user/logs/webserver.log 2>&1
'\''
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target' | sudo tee /etc/systemd/system/webserver.service

sudo systemctl daemon-reload
sudo systemctl enable webserver.service
sudo systemctl start webserver.service"

ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" ec2-user@"$REMOTE_HOST" "$cmd"