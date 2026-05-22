#!/bin/bash

IP=${1:-127.0.0.1}

ts=$(date +'%Y%m%d_%H%M%S')

curl -s "http://$IP:8000/grayscott?size=256&maxIterations=1000&f=0.030&k=0.062&stopOnExtinction=false&seedMode=stripe" | awk -F',' '{print $2}' | tr -d '" \n\r' | base64 -d > "grayscott_M_${ts}.png"

