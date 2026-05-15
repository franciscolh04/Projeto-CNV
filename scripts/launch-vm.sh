#!/bin/bash

source config.sh

# Run new instance.
aws ec2 run-instances \
	--image-id resolve:ssm:/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
	--instance-type t3.micro \
	--key-name $AWS_KEYPAIR_NAME \
	--security-group-ids sg-01cf5faef7b0d263e \
	--monitoring Enabled=true \
    --query 'Instances[0].InstanceId' --output text > instance.id

echo "New instance with id $(cat instance.id)."

# Wait for instance to be running.
aws ec2 wait instance-running --instance-ids $(cat instance.id)
echo "New instance with id $(cat instance.id) is now running."

# Extract Public IP
aws ec2 describe-instances \
	--instance-ids $(cat instance.id) \
    --query 'Reservations[0].Instances[0].PublicIpAddress' \
    --output text > instance.dns

echo "New instance with id $(cat instance.id) has address $(cat instance.dns)."

# Wait for instance to have SSH ready.
while ! nc -z $(cat instance.dns) 22; do
	echo "Waiting for $(cat instance.dns):22 (SSH)..."
	sleep 1
done
echo "New instance with id $(cat instance.id) is ready for SSH access."