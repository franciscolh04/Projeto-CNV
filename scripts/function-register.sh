#!/bin/bash

source config.sh

aws iam create-role \
	--role-name lambda-role \
	--assume-role-policy-document '{"Version": "2012-10-17","Statement": [{ "Effect": "Allow", "Principal": {"Service": "lambda.amazonaws.com"}, "Action": "sts:AssumeRole"}]}'

sleep 8

aws iam attach-role-policy \
	--role-name lambda-role \
	--policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole

sleep 8

aws lambda create-function \
	--function-name fractals-lambda \
	--zip-file fileb://../fractals/target/fractals-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
	--handler pt.ulisboa.tecnico.cnv.fractals.FractalsHandler::handleRequest \
	--runtime java11 \
	--timeout 60 \
	--memory-size 1024 \
	--role arn:aws:iam::$AWS_ACCOUNT_ID:role/lambda-role

aws lambda create-function \
	--function-name dna-lambda \
	--zip-file fileb://../dna/target/dna-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
	--handler pt.ulisboa.tecnico.cnv.dna.DnaHandler::handleRequest \
	--runtime java11 \
	--timeout 60 \
	--memory-size 1024 \
	--role arn:aws:iam::$AWS_ACCOUNT_ID:role/lambda-role

aws lambda create-function \
	--function-name grayscott-lambda \
	--zip-file fileb://../grayscott/target/grayscott-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
	--handler pt.ulisboa.tecnico.cnv.grayscott.GrayScottHandler::handleRequest \
	--runtime java11 \
	--timeout 60 \
	--memory-size 1024 \
	--role arn:aws:iam::$AWS_ACCOUNT_ID:role/lambda-role
