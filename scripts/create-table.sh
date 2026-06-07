#!/bin/bash 

TABLE=RequestHistory

aws dynamodb describe-table --table-name $TABLE >/dev/null 2>&1

if [ $? -ne 0 ]; then
  aws dynamodb create-table \
    --table-name $TABLE \
    --attribute-definitions AttributeName=requestId,AttributeType=S \
    --key-schema AttributeName=requestId,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST
fi

aws dynamodb wait table-exists --table-name $TABLE
aws dynamodb describe-table --table-name $TABLE