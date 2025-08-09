#!/bin/bash
# Script to generate PEM certificates for JWT Quarkus integration testing
# Generates passwordless localhost certificates for secure container usage

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CERT_DIR="${SCRIPT_DIR}"
CERT_DNAME="CN=localhost, OU=Integration Testing, O=CUI-JWT, L=Berlin, ST=Berlin, C=DE"
CERT_VALIDITY=730
TEMP_KEYSTORE="${CERT_DIR}/temp-keystore.p12"
TEMP_PASSWORD="temp-$(date +%s)"

echo "Generating PEM certificates for JWT integration testing..."
echo "Certificate directory: ${CERT_DIR}"

# Clean up existing certificates
rm -f "${CERT_DIR}/keystore.p12" "${CERT_DIR}/truststore.p12" "${CERT_DIR}/localhost.cer" "${CERT_DIR}/localhost.crt" "${CERT_DIR}/localhost.key"

# Generate temporary keystore with private key and self-signed certificate
echo "Generating temporary keystore..."
keytool -genkeypair \
  -alias localhost \
  -keyalg RSA \
  -keysize 2048 \
  -validity ${CERT_VALIDITY} \
  -dname "${CERT_DNAME}" \
  -ext san=dns:localhost,dns:keycloak,ip:127.0.0.1,ip:0.0.0.0 \
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

echo "PEM certificate generation complete!"
echo "Generated files:"
echo "  - localhost.crt: Certificate in PEM format (public, readable)"
echo "  - localhost.key: Private key in PEM format (restricted access)"
echo ""
echo "Certificate valid for ${CERT_VALIDITY} days (2 years)"
echo "Subject: ${CERT_DNAME}"
echo "SAN: dns:localhost,dns:keycloak,ip:127.0.0.1,ip:0.0.0.0"
echo ""
echo "Security: No passwords required - file permissions provide security"