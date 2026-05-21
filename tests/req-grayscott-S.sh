#!/bin/bash

ts=$(date +'%Y%m%d_%H%M%S')

curl -s "http://127.0.0.1:8000/grayscott?size=1024&maxIterations=10000&f=0.230&k=0.062&stopOnExtinction=true&seedMode=ring" | awk -F',' '{print $2}' | tr -d '" \n\r' | base64 -d > "grayscott_S_${ts}.png"

