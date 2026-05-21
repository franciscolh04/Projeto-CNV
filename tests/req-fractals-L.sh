#!/bin/bash

ts=$(date +'%Y%m%d_%H%M%S')

curl -s "http://127.0.0.1:8000/fractals?w=6000&h=6000&iterations=100000" | awk -F',' '{print $2}' | tr -d '" \n\r' | base64 -d > "julia_L_${ts}.png"
