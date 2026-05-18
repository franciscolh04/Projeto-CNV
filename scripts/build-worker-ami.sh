#!/bin/bash

source config.sh

echo "==================================================="
echo " Step 1: Compile the project with Maven"
echo "==================================================="
cd ..
mvn clean install
cd scripts

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
echo " Step 4: Send the Worker and Agent executables"
echo "==================================================="
scp -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH ../webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar ec2-user@$INSTANCE_IP:/home/ec2-user/
scp -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH ../instrumentation/target/instrumentation-1.0.0-SNAPSHOT.jar ec2-user@$INSTANCE_IP:/home/ec2-user/

echo "==================================================="
echo " Step 5: Create the Image (AMI) and save the ID"
echo "==================================================="
AMI_ID=$(aws ec2 create-image \
    --instance-id $INSTANCE_ID \
    --name "CNV-Worker-Image-$(date +%s)" \
    --description "Base image for Workers with Java and Javassist" \
    --query 'ImageId' --output text)

echo "Your new image has been started with the ID: $AMI_ID"
echo $AMI_ID > image.id

echo "Waiting for Amazon to finish copying the disk (This may take 2 to 3 minutes)..."
aws ec2 wait image-available --image-ids $AMI_ID
echo "Disk copy completed successfully!"

echo "==================================================="
echo " Step 6: Destroy the temporary VM"
echo "==================================================="
aws ec2 terminate-instances --instance-ids $INSTANCE_ID

echo "==================================================="
echo " Step 7: Update configuration file"
echo "==================================================="
CONFIG_FILE="config.sh"
if grep -q "^export CNV_AMI_ID=" $CONFIG_FILE; then
    sed -i '' -E "s/^export CNV_AMI_ID=\".*\"/export CNV_AMI_ID=\"$AMI_ID\"/" $CONFIG_FILE
else
    echo "export CNV_AMI_ID=\"$AMI_ID\"" >> $CONFIG_FILE
fi

echo "config.sh has been updated to use CNV_AMI_ID=\"$AMI_ID\""