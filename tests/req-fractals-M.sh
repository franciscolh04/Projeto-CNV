#!/bin/bash

IP=${1:-127.0.0.1}

ts=$(date +'%Y%m%d_%H%M%S')

curl -s "http://$IP:8000/fractals?w=4000&h=2000&iterations=1000" | awk -F',' '{print $2}' | tr -d '" \n\r' | base64 -d > "julia_M_${ts}.png"

