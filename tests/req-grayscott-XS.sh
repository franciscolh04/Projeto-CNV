#!/bin/bash

ts=$(date +'%Y%m%d_%H%M%S')

curl -s "http://127.0.0.1:8000/grayscott?size=256&maxIterations=100&f=0.030&k=0.062&stopOnExtinction=true&seedMode=center" | awk -F',' '{print $2}' | tr -d '" \n\r' | base64 -d > "grayscott_XS_${ts}.png"

