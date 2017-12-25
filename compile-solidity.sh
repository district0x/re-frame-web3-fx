#!/usr/bin/env bash
cd resources/

function solc-err-only {
    solc "$@" 2>&1 | grep -A 2 -i "Error"
}

solc-err-only --overwrite --optimize --bin --abi MintableToken.sol -o ./

wc -c MintableToken.bin | awk '{print "MintableToken: " $1}'
