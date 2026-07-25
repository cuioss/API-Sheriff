#!/bin/bash
# Generate the client-certificate material for the mTLS integration-test instance (api-sheriff-mtls).
#
# Produces, next to the existing server material:
#   - mtls-client-ca.crt : the TRUSTED client CA (self-signed). Mounted into api-sheriff-mtls as
#                          tls.mtls.client_ca — the terminated listener's client-auth trust anchor.
#   - mtls-client.p12     : a client identity (leaf signed by the trusted CA, chain includes the CA),
#                          password 'localhost-trust'. MtlsHandshakeIT presents it and the handshake
#                          MUST complete.
#   - mtls-wrong.p12      : a client identity signed by a DIFFERENT, untrusted CA, password
#                          'wrong-trust'. MtlsHandshakeIT presents it and the handshake MUST be
#                          rejected, proving the trust anchor is enforced (not merely that a cert
#                          was presented).
#
# Test-only material: the shipped default profile trusts no such CA. Passwords are non-secret test
# fixtures matching the MtlsHandshakeIT / Failsafe defaults.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CERT_DIR="${SCRIPT_DIR}"
VALIDITY=730
SUBJ_SUFFIX="/OU=Integration Testing/O=API-Sheriff/L=Berlin/ST=Berlin/C=DE"

echo "Generating mTLS client certificate material in ${CERT_DIR}..."

# Clean any prior material so regeneration is deterministic.
rm -f "${CERT_DIR}"/mtls-client-ca.crt "${CERT_DIR}"/mtls-client-ca.key \
      "${CERT_DIR}"/mtls-client.crt "${CERT_DIR}"/mtls-client.key "${CERT_DIR}"/mtls-client.csr \
      "${CERT_DIR}"/mtls-client.p12 \
      "${CERT_DIR}"/mtls-wrong-ca.crt "${CERT_DIR}"/mtls-wrong-ca.key \
      "${CERT_DIR}"/mtls-wrong.crt "${CERT_DIR}"/mtls-wrong.key "${CERT_DIR}"/mtls-wrong.csr \
      "${CERT_DIR}"/mtls-wrong.p12 \
      "${CERT_DIR}"/mtls-client-ca.srl "${CERT_DIR}"/mtls-wrong-ca.srl

# --- Trusted client CA -------------------------------------------------------------------------
openssl req -x509 -newkey rsa:2048 -nodes -days ${VALIDITY} \
  -keyout "${CERT_DIR}/mtls-client-ca.key" -out "${CERT_DIR}/mtls-client-ca.crt" \
  -subj "/CN=mtls-client-ca${SUBJ_SUFFIX}" \
  -addext "basicConstraints=critical,CA:TRUE" \
  -addext "keyUsage=critical,keyCertSign,cRLSign"

# --- Trusted client leaf (signed by the trusted CA) --------------------------------------------
openssl req -newkey rsa:2048 -nodes \
  -keyout "${CERT_DIR}/mtls-client.key" -out "${CERT_DIR}/mtls-client.csr" \
  -subj "/CN=mtls-client${SUBJ_SUFFIX}"
openssl x509 -req -in "${CERT_DIR}/mtls-client.csr" -days ${VALIDITY} \
  -CA "${CERT_DIR}/mtls-client-ca.crt" -CAkey "${CERT_DIR}/mtls-client-ca.key" -CAcreateserial \
  -extfile <(printf "keyUsage=critical,digitalSignature\nextendedKeyUsage=clientAuth\n") \
  -out "${CERT_DIR}/mtls-client.crt"
openssl pkcs12 -export -name mtls-client \
  -inkey "${CERT_DIR}/mtls-client.key" -in "${CERT_DIR}/mtls-client.crt" \
  -certfile "${CERT_DIR}/mtls-client-ca.crt" \
  -out "${CERT_DIR}/mtls-client.p12" -passout pass:localhost-trust

# --- Foreign (untrusted) CA + client leaf ------------------------------------------------------
openssl req -x509 -newkey rsa:2048 -nodes -days ${VALIDITY} \
  -keyout "${CERT_DIR}/mtls-wrong-ca.key" -out "${CERT_DIR}/mtls-wrong-ca.crt" \
  -subj "/CN=mtls-wrong-ca${SUBJ_SUFFIX}" \
  -addext "basicConstraints=critical,CA:TRUE" \
  -addext "keyUsage=critical,keyCertSign,cRLSign"
openssl req -newkey rsa:2048 -nodes \
  -keyout "${CERT_DIR}/mtls-wrong.key" -out "${CERT_DIR}/mtls-wrong.csr" \
  -subj "/CN=mtls-wrong${SUBJ_SUFFIX}"
openssl x509 -req -in "${CERT_DIR}/mtls-wrong.csr" -days ${VALIDITY} \
  -CA "${CERT_DIR}/mtls-wrong-ca.crt" -CAkey "${CERT_DIR}/mtls-wrong-ca.key" -CAcreateserial \
  -extfile <(printf "keyUsage=critical,digitalSignature\nextendedKeyUsage=clientAuth\n") \
  -out "${CERT_DIR}/mtls-wrong.crt"
openssl pkcs12 -export -name mtls-wrong \
  -inkey "${CERT_DIR}/mtls-wrong.key" -in "${CERT_DIR}/mtls-wrong.crt" \
  -certfile "${CERT_DIR}/mtls-wrong-ca.crt" \
  -out "${CERT_DIR}/mtls-wrong.p12" -passout pass:wrong-trust

# Retain only the runtime artifacts: the trusted CA anchor (mounted into the mTLS instance) and the
# two client keystores (read by the Failsafe-side MtlsHandshakeIT). Drop keys/CSRs/serials and the
# untrusted CA — none are needed at runtime.
rm -f "${CERT_DIR}"/mtls-client-ca.key "${CERT_DIR}"/mtls-client.key "${CERT_DIR}"/mtls-client.csr \
      "${CERT_DIR}"/mtls-client.crt \
      "${CERT_DIR}"/mtls-wrong-ca.key "${CERT_DIR}"/mtls-wrong-ca.crt \
      "${CERT_DIR}"/mtls-wrong.key "${CERT_DIR}"/mtls-wrong.csr "${CERT_DIR}"/mtls-wrong.crt \
      "${CERT_DIR}"/mtls-client-ca.srl "${CERT_DIR}"/mtls-wrong-ca.srl

chmod 644 "${CERT_DIR}/mtls-client-ca.crt" "${CERT_DIR}/mtls-client.p12" "${CERT_DIR}/mtls-wrong.p12"

echo "mTLS client material generated:"
echo "  - mtls-client-ca.crt (trust anchor, mounted as tls.mtls.client_ca)"
echo "  - mtls-client.p12    (trusted client identity, password localhost-trust)"
echo "  - mtls-wrong.p12     (foreign-CA client identity, password wrong-trust)"
