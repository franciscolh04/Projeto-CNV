#!/bin/bash

# 1. Load local AWS variables and keys
source config.sh

echo "==================================================="
echo " Step 1: Compile the Master Node with the new AMI_ID"
echo "==================================================="
cd ../loadbalancer
mvn clean install
cd ../scripts

echo "==================================================="
echo " Step 2: Launch the EC2 instance (The Permanent Master)"
echo "==================================================="
# We use the base script from the labs to launch the machine
./launch-vm.sh masterLB
MASTER_PUBLIC_DNS=$(cat masterLB.dns)
MASTER_INSTANCE_ID=$(cat masterLB.id)

echo "Waiting 40 seconds for the Master to start and open SSH..."
sleep 40

echo "==================================================="
echo " Step 3: Get the Master's Private IP"
echo "==================================================="
# The magic of AWS CLI: extract only the Private IP of the machine we just created
MASTER_PRIVATE_IP=$(aws ec2 describe-instances \
    --instance-ids $MASTER_INSTANCE_ID \
    --query 'Reservations[0].Instances[0].PrivateIpAddress' \
    --output text)

echo "Public IP (For your Browser): $MASTER_PUBLIC_DNS"
echo "Private IP (For the internal network): $MASTER_PRIVATE_IP"

CONFIG_FILE="config.sh"
sed -i "/export MASTER_PRIVATE_IP=/d" $CONFIG_FILE
echo "export MASTER_PRIVATE_IP=\"$MASTER_PRIVATE_IP\"" >> $CONFIG_FILE

echo "==================================================="
echo " Step 4: Create The AMI"
echo "==================================================="
./create-image.sh

echo "==================================================="
echo " Step 5: Install Java 11 on the Master"
echo "==================================================="
ssh -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH ec2-user@$MASTER_PUBLIC_DNS "sudo yum update -y; sudo yum install java-11-amazon-corretto-devel.x86_64 -y;"

echo "==================================================="
echo " Step 6: Send the Executable and AWS Credentials"
echo "==================================================="
# Ensure that env vars are set
CONFIG_FILE="config.sh"
sed -i "/export CNV_AMI_ID=/d" $CONFIG_FILE
sed -i "/export CNV_SEC_GROUP_ID=/d" $CONFIG_FILE
echo "export CNV_AMI_ID=\"$(cat image.id)\"" >> $CONFIG_FILE
echo "export CNV_SEC_GROUP_ID=\"$AWS_SECURITY_GROUP\"" >> $CONFIG_FILE

# 1. Send the Java application
scp -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH ../loadbalancer/target/loadbalancer-1.0.0-SNAPSHOT-jar-with-dependencies.jar ec2-user@$MASTER_PUBLIC_DNS:/home/ec2-user/
# 2. Send config.sh so the Auto Scaler has permissions to act
scp -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH config.sh ec2-user@$MASTER_PUBLIC_DNS:/home/ec2-user/

echo "==================================================="
echo " Step 7: Start the System in the Background"
echo "==================================================="
# The 'nohup' command ensures that the server continues to run even when you close your terminal.
# The output is saved in the master.log file
# Do control-c when The echo Step 6 is printed in terminal
ssh -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH ec2-user@$MASTER_PUBLIC_DNS "source config.sh && nohup java -cp loadbalancer-1.0.0-SNAPSHOT-jar-with-dependencies.jar pt.ulisboa.tecnico.cnv.loadbalancer.LoadBalancer $MASTER_PRIVATE_IP > master.log 2>&1 &"

echo "==================================================="
echo " SUCCESS! Your Load Balancer and Auto Scaler are online! (AMI also created)"
echo " "
echo " Test in your browser:"
echo " http://$MASTER_PUBLIC_DNS:8000/fractals?w=400&h=400&n=100"
echo " "
echo " To see the logs in real time, SSH in and do:"
echo " tail -f master.log"
echo "==================================================="