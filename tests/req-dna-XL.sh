#!/bin/bash

IP=${1:-localhost}

SEQ1_CONTENT=$(cat genome-escherichia-coli-25k.fasta)
SEQ1="escherichia-coli-25k:$SEQ1_CONTENT"

SEQ2_CONTENT=$(cat genome-salmonella-enterica-25k.fasta)
SEQ2="salmonella-enterica-25k:$SEQ2_CONTENT"

curl "http://$IP:8000/dna?minLength=250&stopOnFirst=False" -G --data-urlencode "seq1=$SEQ1" --data-urlencode "seq2=$SEQ2"
