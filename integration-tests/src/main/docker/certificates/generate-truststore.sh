#!/bin/bash
# Script to generate Java truststore for API Sheriff integration testing
# Creates truststore with localhost certificate for proper TLS validation

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CERT_DIR="${SCRIPT_DIR}"
TRUSTSTORE_FILE="${CERT_DIR}/localhost-truststore.p12"
TRUSTSTORE_PASSWORD="localhost-trust"

# The trust material bound to the `it-upstream` TLS profile (ADR-0040), holding ONLY the
# hostname-mismatched upstream certificate. It is a second, separate store rather than another
# entry in localhost-truststore.p12 because the profile's resolved anchors REPLACE a client's trust
# rather than adding to it — so this store is exactly the set the egress clients may verify
# terminated upstreams against, and adding anything else to it widens that set.
#
# Containing only the mismatched certificate is what makes the control a clean single-variable one:
# chain trust succeeds on both legs, so the only thing left that can differ between the verify-on
# and verify-off instances is the hostname check itself. Were the chain untrusted, the verify-on
# leg would fail for two reasons at once and prove neither.
UPSTREAM_TRUSTSTORE_FILE="${CERT_DIR}/upstream-truststore.p12"
UPSTREAM_TRUSTSTORE_PASSWORD="upstream-trust"

echo "Generating Java truststore for API Sheriff integration testing..."
echo "Certificate directory: ${CERT_DIR}"

# Check if certificate exists
if [[ ! -f "${CERT_DIR}/localhost.crt" ]]; then
    echo "Error: localhost.crt not found. Run generate-certificates.sh first."
    exit 1
fi

if [[ ! -f "${CERT_DIR}/upstream-mismatch.crt" ]]; then
    echo "Error: upstream-mismatch.crt not found. Run generate-certificates.sh first."
    exit 1
fi

# Clean up existing truststores
rm -f "${TRUSTSTORE_FILE}"
rm -f "${UPSTREAM_TRUSTSTORE_FILE}"

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

# Build the it-upstream trust store, on the same pattern as the localhost step above.
echo "Creating upstream truststore and importing the hostname-mismatched certificate..."
keytool -importcert \
  -alias upstream-mismatch-ca \
  -file "${CERT_DIR}/upstream-mismatch.crt" \
  -keystore "${UPSTREAM_TRUSTSTORE_FILE}" \
  -storetype PKCS12 \
  -storepass "${UPSTREAM_TRUSTSTORE_PASSWORD}" \
  -noprompt \
  -trustcacerts 2>&1

# Set secure file permissions
echo "Setting secure file permissions..."
chmod 644 "${TRUSTSTORE_FILE}"           # Truststore can be readable
chmod 644 "${UPSTREAM_TRUSTSTORE_FILE}"  # Truststore can be readable

echo "Java truststore generation complete!"