#!/bin/bash
# Generate the self-signed TLS material this compose sample runs on.
#
# ###############################################################################################
# #                                                                                             #
# #   SAMPLE MATERIAL ONLY — NEVER USE THE OUTPUT OF THIS SCRIPT IN PRODUCTION.                 #
# #                                                                                             #
# #   It mints a self-signed certificate with no CA behind it, and writes the private key next  #
# #   to it, unencrypted, in a directory the compose stack mounts. That is the right trade for  #
# #   a sample you bring up and tear down on one machine, and the wrong one for anything else.  #
# #   A real deployment supplies certificate and key from its own PKI and mounts them at the    #
# #   same two paths — the gateway does not care where they came from.                          #
# #                                                                                             #
# ###############################################################################################
#
# NO KEY MATERIAL IS COMMITTED. This script is the source of truth; the .gitignore beside it keeps
# every artifact the script produces out of the repository. Run it once before the first bring-up.

set -euo pipefail

CERT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# CN=localhost so a browser on the host addresses the gateway as https://localhost:8443. The SAN list
# is what actually matters to a modern client, and it carries BOTH the host-facing name and the two
# compose service names — the gateway reaches Keycloak as `keycloak` on the internal network, so a
# certificate without that name would fail hostname verification on the JWKS fetch.
CERT_DNAME="CN=localhost, OU=Compose Sample, O=API-Sheriff, L=Berlin, ST=Berlin, C=DE"
CERT_SAN="dns:localhost,dns:keycloak,dns:api-sheriff,dns:demo-api,ip:127.0.0.1"
CERT_VALIDITY=365

TEMP_KEYSTORE="${CERT_DIR}/temp-keystore.p12"
TEMP_PASSWORD="temp-$(date +%s)"

# Remove the intermediate keystore on EVERY exit path, not just the success one. Under `set -e` a
# failing keytool or openssl aborts the script before the explicit cleanup at the end, which would
# otherwise strand a PKCS#12 file holding the private key in a directory an operator is told to
# treat as generated-and-disposable. The trap is armed here, immediately after the path is defined,
# so no failure between this line and the cleanup can escape it.
trap 'rm -f "${TEMP_KEYSTORE}"' EXIT

echo "Generating SAMPLE self-signed TLS material for the API Sheriff compose sample..."
echo "  Directory: ${CERT_DIR}"

# Start from a clean slate so a re-run never leaves a half-replaced pair behind.
rm -f "${CERT_DIR}/localhost.crt" "${CERT_DIR}/localhost.key" "${TEMP_KEYSTORE}"

echo "  Generating key pair and self-signed certificate..."
keytool -genkeypair \
  -alias localhost \
  -keyalg RSA \
  -keysize 2048 \
  -validity "${CERT_VALIDITY}" \
  -dname "${CERT_DNAME}" \
  -ext "san=${CERT_SAN}" \
  -keystore "${TEMP_KEYSTORE}" \
  -storetype PKCS12 \
  -storepass "${TEMP_PASSWORD}" \
  -keypass "${TEMP_PASSWORD}"

echo "  Exporting the certificate (PEM)..."
keytool -exportcert \
  -alias localhost \
  -file "${CERT_DIR}/localhost.crt" \
  -keystore "${TEMP_KEYSTORE}" \
  -storetype PKCS12 \
  -storepass "${TEMP_PASSWORD}" \
  -rfc

echo "  Exporting the private key (PEM, unencrypted)..."
openssl pkcs12 -in "${TEMP_KEYSTORE}" \
  -passin pass:"${TEMP_PASSWORD}" \
  -nodes \
  -nocerts \
  -out "${CERT_DIR}/localhost.key"

# BOTH files are world-readable, and the key deliberately so. Read this before "tightening" it:
#
# The two containers that mount this directory run as DIFFERENT non-root uids — the gateway as
# nonroot (65532, from its Dockerfile) and Keycloak as 1000 — while these files are owned by
# whichever host user ran this script. Neither uid can be matched from the host without root, so
# mode 600 makes the key unreadable inside the container and the gateway dies at boot with
# `AccessDeniedException: /app/certificates/localhost.key`. World-read is what makes a non-root,
# read_only, cap_drop-ALL container able to serve TLS at all.
#
# That trade is acceptable here and ONLY here: this key is self-signed, git-ignored, regenerated on
# demand and never leaves this machine. It protects nothing, so exposing it to other local accounts
# costs nothing. A real deployment does the opposite — it supplies key material from its own PKI
# with restrictive ownership matched to the runtime uid, and mounts it at these same two paths.
#
# (integration-tests/src/main/docker/certificates/generate-certificates.sh still chmods its key to
# 600, but that mode is nominal: those certificates are COMMITTED, and git records only the exec
# bit, so a fresh checkout materialises them world-readable anyway. The sample commits no key
# material, so the mode it is created with is the mode the container actually sees.)
chmod 644 "${CERT_DIR}/localhost.crt"
chmod 644 "${CERT_DIR}/localhost.key"

# No explicit keystore removal here — the EXIT trap armed above owns that, on this path and on every
# failure path alike. A second copy would only be a second thing to keep in step.

echo ""
echo "Done. Generated (both git-ignored):"
echo "  localhost.crt  the certificate, mounted into api-sheriff and keycloak"
echo "  localhost.key  the private key  — sample only, never leaves this machine"
echo ""
echo "Valid ${CERT_VALIDITY} days. Subject: ${CERT_DNAME}"
echo "SAN: ${CERT_SAN}"
echo ""
echo "Because the certificate is self-signed, every client must be told to accept it:"
echo "  curl -k https://localhost:8443/api"
echo "A browser will show a warning. That is correct, and it is the reason this is a sample."
