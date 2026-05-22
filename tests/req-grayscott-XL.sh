#!/bin/bash

IP=${1:-127.0.0.1}

ts=$(date +'%Y%m%d_%H%M%S')

curl -s "http://$IP:8000/grayscott?size=512&maxIterations=1500&f=0.030&k=0.062&stopOnExtinction=false&seedMode=ring" | awk -F',' '{print $2}' | tr -d '" \n\r' | base64 -d > "grayscott_XL_${ts}.png"

