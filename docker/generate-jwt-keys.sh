#!/usr/bin/env bash
# Generates the RSA keypair Auth Service uses to sign JWTs (PKCS#8 private / X.509 public,
# matching common.security.PemUtils). Run once before the first `docker compose up` or before
# running auth-service locally with the `docker`/`local` profile. Never commit the output -
# docker/secrets/ is gitignored.
#
# Writes to TWO directories, not one, so the private key is only ever mounted into Auth
# Service's container - every other service only ever verifies tokens, never signs them, and
# has no legitimate reason to be able to read the signing key (see docs/architecture.md
# section 13 - a compromised downstream service must not be able to forge tokens):
#   docker/secrets/jwt-signing/  - private_key.pem + public_key.pem, mounted only into auth-service
#   docker/secrets/jwt-public/   - public_key.pem only, mounted into every other service
#
# (Superseded a single docker/secrets/jwt/ directory that held both keys and was mounted into
# every service - if that directory still exists from before this change, it's safe to delete.)
set -euo pipefail

BASE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/secrets"
SIGNING_DIR="$BASE_DIR/jwt-signing"
PUBLIC_DIR="$BASE_DIR/jwt-public"
mkdir -p "$SIGNING_DIR" "$PUBLIC_DIR"

if [[ -f "$SIGNING_DIR/private_key.pem" ]]; then
  echo "Keys already exist at $SIGNING_DIR - remove them first if you want to regenerate."
  exit 0
fi

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$SIGNING_DIR/private_key.pem"
openssl rsa -pubout -in "$SIGNING_DIR/private_key.pem" -out "$SIGNING_DIR/public_key.pem"
cp "$SIGNING_DIR/public_key.pem" "$PUBLIC_DIR/public_key.pem"

# openssl genpkey defaults to 600 (owner-only). The container reads this file as its own
# non-root `orderflow` user (see auth-service/Dockerfile), a different UID than whatever
# generated the key, so a bind mount of an unreadable-by-others file fails with
# "Permission denied" on a real Linux Docker host - invisible on this Windows machine, since
# Docker Desktop's volume mounting doesn't enforce Unix file permissions the same way, but it
# broke every run in CI. These are throwaway dev/CI keys, never committed, regenerated per
# environment, so relaxing to world-readable is fine.
chmod 644 "$SIGNING_DIR/private_key.pem" "$SIGNING_DIR/public_key.pem" "$PUBLIC_DIR/public_key.pem"

echo "Generated dev JWT keypair in $SIGNING_DIR (private+public) and $PUBLIC_DIR (public only)"
