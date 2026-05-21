#!/bin/bash

SEQ1_CONTENT=$(cat sars-10k.fasta)
SEQ1="sars-10k:$SEQ1_CONTENT"

SEQ2_CONTENT=$(cat human-mc-10k.fasta)
SEQ2="human-mc-10k:$SEQ2_CONTENT"

curl "http://localhost:8000/dna?minLength=200&stopOnFirst=False" -G --data-urlencode "seq1=$SEQ1" --data-urlencode "seq2=$SEQ2"
