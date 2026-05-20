#!/bin/bash
# This script is meant to be passed as userdata when launching worker nodes
# It contains the variable logic that shouldn't be in the AMI
# The $MASTER_PRIVATE_IP placeholder will be replaced by LaunchInstance.java

# Get instance metadata
TOKEN=$(/usr/bin/curl -X PUT "http://169.254.169.254/latest/api/token" \
    -H "X-aws-ec2-metadata-token-ttl-seconds: 21600" -s)

INSTANCE_ID=$(/usr/bin/curl \
    -H "X-aws-ec2-metadata-token: $TOKEN" \
    -s http://169.254.169.254/latest/meta-data/instance-id)

# Launch the webserver with Javassist instrumentation agent
exec /usr/bin/java \
    -javaagent:/home/ec2-user/instrumentation-1.0.0-SNAPSHOT.jar=pt.ulisboa.tecnico.cnv.javassist.tools.ComplexityEstimator:pt.ulisboa.tecnico.cnv:output \
    -cp /home/ec2-user/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
    pt.ulisboa.tecnico.cnv.webserver.WebServer \
    "$MASTER_PRIVATE_IP" "$INSTANCE_ID" \
    >> /home/ec2-user/logs/webserver.log 2>&1
