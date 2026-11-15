#!/usr/bin/env bash
# Uso: ./build_and_test.sh [ruta/a/un.apk]
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out
javac -d out src/aapatch/*.java test-src/aapatch/SelfTest.java
java -cp out aapatch.SelfTest
if [ "${1:-}" ]; then
  mkdir -p work
  java -cp out aapatch.Cli patch "$1" work/patched.apk --keystore work/ks.p12
  python3 -I tools/verify_v2.py work/patched.apk       # pip install cryptography
  python3 -I tools/check_patched.py work/patched.apk   # pip install androguard
fi
