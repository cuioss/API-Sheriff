#!/bin/bash
# Script to generate Java truststore for JWT Quarkus integration testing
# Creates truststore with localhost certificate for proper TLS validation

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CERT_DIR="${SCRIPT_DIR}"
TRUSTSTORE_FILE="${CERT_DIR}/localhost-truststore.p12"
TRUSTSTORE_PASSWORD="localhost-trust"

echo "Generating Java truststore for JWT integration testing..."
echo "Certificate directory: ${CERT_DIR}"

# Check if certificate exists
if [[ ! -f "${CERT_DIR}/localhost.crt" ]]; then
    echo "Error: localhost.crt not found. Run generate-certificates.sh first."
    exit 1
fi

# Clean up existing truststore
rm -f "${TRUSTSTORE_FILE}"

# Import certificate into truststore
echo "Creating truststore and importing localhost certificate..."
keytool -importcert \
  -alias localhost-ca \
  -file "${CERT_DIR}/localhost.crt" \
  -keystore "${TRUSTSTORE_FILE}" \
  -storetype PKCS12 \
  -storepass "${TRUSTSTORE_PASSWORD}" \
  -noprompt \
  -trustcacerts 2>&1

# Set secure file permissions
echo "Setting secure file permissions..."
chmod 644 "${TRUSTSTORE_FILE}"  # Truststore can be readable

echo "Java truststore generation complete!"