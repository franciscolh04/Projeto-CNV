#!/bin/bash

source config.sh

echo "==================================================="
echo " Step 1: Compile the project with Maven"
echo "==================================================="
cd ../webserver
mvn clean install
cd ../scripts

echo "==================================================="
echo " Step 2: Launch a temporary VM on AWS"
echo "==================================================="
./launch-vm.sh
INSTANCE_IP=$(cat instance.dns)
INSTANCE_ID=$(cat instance.id)

echo "Waiting 40 seconds for the machine to start and open SSH..."
sleep 40

echo "==================================================="
echo " Step 3: Install Java 11 on the machine"
echo "==================================================="
ssh -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH ec2-user@$INSTANCE_IP "sudo yum update -y; sudo yum install java-11-amazon-corretto-devel.x86_64 -y;"

echo "==================================================="
echo " Step 4: Send the Worker executable (.jar)"
echo "==================================================="
scp -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH ../webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar ec2-user@$INSTANCE_IP:/home/ec2-user/

echo "==================================================="
echo " Step 5: Create the Image (AMI) and save the ID"
echo "==================================================="
AMI_ID=$(aws ec2 create-image \
    --instance-id $INSTANCE_ID \
    --name "CNV-Worker-Image-$(date +%s)" \
    --description "Base image for Workers with Java and the JAR" \
    --query 'ImageId' --output text)

echo "Your new image has been started with the ID: $AMI_ID"
echo $AMI_ID > image.id

echo "Waiting for Amazon to finish copying the disk (This may take 2 to 3 minutes)..."
aws ec2 wait image-available --image-ids $AMI_ID
echo "Disk copy completed successfully!"

echo "==================================================="
echo " Step 6: Destroy the temporary VM (Save costs!)"
echo "==================================================="
aws ec2 terminate-instances --instance-ids $INSTANCE_ID

echo "==================================================="
echo " Step 7: Automatically update the Load Balancer file"
echo "==================================================="
MASTER_FILE="../loadbalancer/src/main/java/pt/ulisboa/tecnico/cnv/LoadBalancer.java"

sed -i '' "s/private static final String AMI_ID = \".*\";/private static final String AMI_ID = \"$AMI_ID\";/" $MASTER_FILE

echo "LoadBalancer.java has been updated to use AMI: $AMI_ID"