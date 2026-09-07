#!/usr/bin/env bash
# Generates the RSA keypair the Auth Service uses to sign JWTs (PKCS#8 private / X.509 public,
# matching common.security.PemUtils). Run once before the first `docker compose up` or before
# running auth-service locally with the `docker`/`local` profile. Never commit the output -
# docker/secrets/ is gitignored.
set -euo pipefail

OUT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/secrets/jwt"
mkdir -p "$OUT_DIR"

if [[ -f "$OUT_DIR/private_key.pem" ]]; then
  echo "Keys already exist at $OUT_DIR - remove them first if you want to regenerate."
  exit 0
fi

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$OUT_DIR/private_key.pem"
openssl rsa -pubout -in "$OUT_DIR/private_key.pem" -out "$OUT_DIR/public_key.pem"

echo "Generated dev JWT keypair in $OUT_DIR"
