#!/bin/bash
set -euo pipefail

source config.sh

# Use first argument as file prefix
if [ "$#" -lt 1 ]; then
	echo "Usage: $0 <file-prefix>" >&2
	exit 1
fi
FILE="$1"

# Run new $FILE.
aws ec2 run-instances \
	--image-id resolve:ssm:/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
	--instance-type t3.micro \
	--key-name $AWS_KEYPAIR_NAME \
	--security-group-ids $AWS_SECURITY_GROUP \
	--monitoring Enabled=true | jq -r ".Instances[0].InstanceId" > $FILE.id
echo "New instance with id $(cat $FILE.id)."

# Wait for instance to be running.
aws ec2 wait instance-running --instance-ids $(cat $FILE.id)
echo "New instance with id $(cat $FILE.id) is now running."

# Extract DNS nane.
aws ec2 describe-instances \
	--instance-ids $(cat $FILE.id) | jq -r ".Reservations[0].Instances[0].NetworkInterfaces[0].PrivateIpAddresses[0].Association.PublicDnsName" > $FILE.dns
echo "New instance with id $(cat $FILE.id) has address $(cat $FILE.dns)."

# Wait for instance to have SSH ready.
while ! nc -z $(cat $FILE.dns) 22; do
	echo "Waiting for $(cat $FILE.dns):22 (SSH)..."
	sleep 0.5
done
echo "New instance with id $(cat $FILE.id) is ready for SSH access."
