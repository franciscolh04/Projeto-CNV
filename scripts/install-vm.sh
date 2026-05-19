#!/bin/bash

source config.sh

# Install java.
cmd="sudo yum update -y; sudo yum install java-11-amazon-corretto-devel.x86_64 -y;"
ssh -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH ec2-user@$(cat instance.dns) $cmd

# Install Web Server and Javassist
scp -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH $DIR../webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar ec2-user@$(cat instance.dns):/home/ec2-user/
scp -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH $DIR../instrumentation/target/instrumentation-1.0.0-SNAPSHOT.jar ec2-user@$(cat instance.dns):/home/ec2-user/

# Setup web server to start on instance launch via systemd.
cmd="echo '[Unit]
Description=CNV WebServer
After=network.target

[Service]
Type=simple
User=ec2-user
WorkingDirectory=/home/ec2-user
ExecStart=/usr/bin/java -jar /home/ec2-user/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target' | sudo tee /etc/systemd/system/webserver.service; sudo systemctl daemon-reload; sudo systemctl enable webserver.service; sudo systemctl start webserver.service"
ssh -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH ec2-user@$(cat instance.dns) $cmd

