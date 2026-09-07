#!/bin/bash
# Script to generate PEM certificates for API Sheriff integration testing
# Generates passwordless localhost certificates for secure container usage

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CERT_DIR="${SCRIPT_DIR}"
CERT_DNAME="CN=localhost, OU=Integration Testing, O=API-Sheriff, L=Berlin, ST=Berlin, C=DE"
CERT_VALIDITY=730
TEMP_KEYSTORE="${CERT_DIR}/temp-keystore.p12"
TEMP_PASSWORD="temp-$(date +%s)"

# The deliberately hostname-mismatched upstream pair backing the egress hostname-verification
# matched control (ADR-0040). It is served by the `mismatched-tls-backend` nginx service, which the
# two api-sheriff-egress-verify-* gateways dial under exactly that name — and the SAN below names
# `upstream-mismatch` and nothing else, so the presented certificate does not cover the dialled
# name. THAT non-overlap is the entire fixture: it is what makes the verify-on instance refuse the
# upstream and the verify-off instance reach it.
#
# Do NOT add `mismatched-tls-backend` (or any name the compose network resolves to that service) to
# this SAN list. Doing so makes the certificate valid for the dialled name, both legs of the control
# then succeed, and the negative leg silently stops testing anything — the failure mode is a green
# suite, not a red one.
UPSTREAM_MISMATCH_DNAME="CN=upstream-mismatch, OU=Integration Testing, O=API-Sheriff, L=Berlin, ST=Berlin, C=DE"
UPSTREAM_MISMATCH_SAN="dns:upstream-mismatch"
UPSTREAM_MISMATCH_KEYSTORE="${CERT_DIR}/temp-upstream-mismatch.p12"

echo "Generating PEM certificates for API Sheriff integration testing..."
echo "Certificate directory: ${CERT_DIR}"

# Clean up existing certificates
rm -f "${CERT_DIR}/keystore.p12" "${CERT_DIR}/truststore.p12" "${CERT_DIR}/localhost.cer" "${CERT_DIR}/localhost.crt" "${CERT_DIR}/localhost.key"
rm -f "${CERT_DIR}/upstream-mismatch.crt" "${CERT_DIR}/upstream-mismatch.key"

# Generate temporary keystore with private key and self-signed certificate
echo "Generating temporary keystore..."
keytool -genkeypair \
  -alias localhost \
  -keyalg RSA \
  -keysize 2048 \
  -validity ${CERT_VALIDITY} \
  -dname "${CERT_DNAME}" \
  -ext san=dns:localhost,dns:keycloak,dns:api-sheriff,ip:127.0.0.1,ip:0.0.0.0 \
  -keystore "${TEMP_KEYSTORE}" \
  -storetype PKCS12 \
  -storepass "${TEMP_PASSWORD}" \
  -keypass "${TEMP_PASSWORD}" 2>&1

# Export certificate in PEM format
echo "Exporting certificate in PEM format..."
keytool -exportcert \
  -alias localhost \
  -file "${CERT_DIR}/localhost.crt" \
  -keystore "${TEMP_KEYSTORE}" \
  -storetype PKCS12 \
  -storepass "${TEMP_PASSWORD}" \
  -rfc 2>&1

# Export private key in PEM format using openssl
echo "Exporting private key in PEM format..."
openssl pkcs12 -in "${TEMP_KEYSTORE}" \
  -passin pass:"${TEMP_PASSWORD}" \
  -nodes \
  -nocerts \
  -out "${CERT_DIR}/localhost.key" 2>&1

# Set secure file permissions
echo "Setting secure file permissions..."
chmod 644 "${CERT_DIR}/localhost.crt"  # Certificate is public
chmod 600 "${CERT_DIR}/localhost.key"  # Private key is restricted

# Clean up temporary keystore
rm -f "${TEMP_KEYSTORE}"

# --- The deliberately hostname-mismatched upstream pair (ADR-0040) ------------------------------
# Same shape as the localhost step above — temporary keystore, PEM certificate export, PEM key
# export, permissions, cleanup — differing only in the DNAME and the SAN, which name a host nothing
# in the compose topology dials.
echo "Generating temporary keystore for the hostname-mismatched upstream..."
keytool -genkeypair \
  -alias upstream-mismatch \
  -keyalg RSA \
  -keysize 2048 \
  -validity ${CERT_VALIDITY} \
  -dname "${UPSTREAM_MISMATCH_DNAME}" \
  -ext san=${UPSTREAM_MISMATCH_SAN} \
  -keystore "${UPSTREAM_MISMATCH_KEYSTORE}" \
  -storetype PKCS12 \
  -storepass "${TEMP_PASSWORD}" \
  -keypass "${TEMP_PASSWORD}" 2>&1

echo "Exporting hostname-mismatched certificate in PEM format..."
keytool -exportcert \
  -alias upstream-mismatch \
  -file "${CERT_DIR}/upstream-mismatch.crt" \
  -keystore "${UPSTREAM_MISMATCH_KEYSTORE}" \
  -storetype PKCS12 \
  -storepass "${TEMP_PASSWORD}" \
  -rfc 2>&1

echo "Exporting hostname-mismatched private key in PEM format..."
openssl pkcs12 -in "${UPSTREAM_MISMATCH_KEYSTORE}" \
  -passin pass:"${TEMP_PASSWORD}" \
  -nodes \
  -nocerts \
  -out "${CERT_DIR}/upstream-mismatch.key" 2>&1

chmod 644 "${CERT_DIR}/upstream-mismatch.crt"  # Certificate is public
chmod 600 "${CERT_DIR}/upstream-mismatch.key"  # Private key is restricted

rm -f "${UPSTREAM_MISMATCH_KEYSTORE}"

echo "PEM certificate generation complete!"
echo "Generated files:"
echo "  - localhost.crt: Certificate in PEM format (public, readable)"
echo "  - localhost.key: Private key in PEM format (restricted access)"
echo "  - upstream-mismatch.crt: Hostname-mismatched upstream certificate (public, readable)"
echo "  - upstream-mismatch.key: Hostname-mismatched upstream private key (restricted access)"
echo ""
echo "Certificate valid for ${CERT_VALIDITY} days (2 years)"
echo "Subject: ${CERT_DNAME}"
echo "SAN: dns:localhost,dns:keycloak,dns:api-sheriff,ip:127.0.0.1,ip:0.0.0.0"
echo ""
echo "Mismatched upstream subject: ${UPSTREAM_MISMATCH_DNAME}"
echo "Mismatched upstream SAN: ${UPSTREAM_MISMATCH_SAN} (deliberately NOT mismatched-tls-backend)"
echo ""
echo "Security: No passwords required - file permissions provide security"