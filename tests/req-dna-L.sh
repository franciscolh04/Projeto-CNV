#!/bin/bash

SEQ1_CONTENT=$(cat genome-klebsiella-pneumoniae-20k.fasta)
SEQ1="klebsiella-pneumoniae-20k:$SEQ1_CONTENT"

SEQ2_CONTENT=$(cat genome-salmonella-enterica-20k.fasta)
SEQ2="salmonella-enterica-20k:$SEQ2_CONTENT"

curl "http://localhost:8000/dna?minLength=200&stopOnFirst=False" -G --data-urlencode "seq1=$SEQ1" --data-urlencode "seq2=$SEQ2"
