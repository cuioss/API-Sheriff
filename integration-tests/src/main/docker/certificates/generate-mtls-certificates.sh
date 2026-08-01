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
#
# The three retained artifacts are TRACKED in git, so regenerating them unconditionally would rewrite
# tracked files with fresh random key material on every integration-test run and leave the working
# tree dirty for reasons unrelated to the change in flight. This script therefore regenerates only
# when the material is absent or close to expiry, mirroring the sibling scripts/build-native-if-
# needed.sh guard bound to the same pre-integration-test phase. Force a regeneration with --force or
# MTLS_CERTS_FORCE=true.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CERT_DIR="${SCRIPT_DIR}"
VALIDITY=730
SUBJ_SUFFIX="/OU=Integration Testing/O=API-Sheriff/L=Berlin/ST=Berlin/C=DE"

# Regenerate when the trust anchor expires within this window. The material is issued with a 730-day
# validity, so a 30-day margin is ample and still stops a stale checkout silently running with
# expired certificates.
RENEWAL_MARGIN_SECONDS=$((30 * 24 * 60 * 60))

# The artifacts this script retains (see the trailing cleanup): the trust anchor plus the two client
# keystores. All three are produced by a single generation pass, so any one of them missing means the
# whole set must be rebuilt.
RETAINED_ARTIFACTS=(
    "${CERT_DIR}/mtls-client-ca.crt"
    "${CERT_DIR}/mtls-client.p12"
    "${CERT_DIR}/mtls-wrong.p12"
)

FORCE=false
if [[ "${1:-}" == "--force" || "${MTLS_CERTS_FORCE:-}" == "true" ]]; then
    FORCE=true
fi

# Sets REGENERATION_REASON and returns 0 when the material must be rebuilt, 1 when it is usable as-is.
regeneration_needed() {
    if [[ "${FORCE}" == "true" ]]; then
        REGENERATION_REASON="forced"
        return 0
    fi

    local artifact
    for artifact in "${RETAINED_ARTIFACTS[@]}"; do
        if [[ ! -f "${artifact}" ]]; then
            REGENERATION_REASON="missing $(basename "${artifact}")"
            return 0
        fi
    done

    # All three artifacts are issued in one pass with the same validity, so the CA certificate's
    # expiry is representative of the set — and unlike the PKCS#12 keystores it can be inspected
    # without a password. A non-zero exit here also covers unreadable/corrupt material, which is
    # why the reason below names those causes too rather than asserting expiry. The two keystores
    # are checked for PRESENCE ONLY: they are tracked in git, so a truncated one is not a
    # normal-operation state, and when it does occur MtlsHandshakeIT fails loudly on keystore load
    # with --force as the one-step recovery. Nothing here may claim more than it checked.
    if ! openssl x509 -checkend "${RENEWAL_MARGIN_SECONDS}" -noout -in "${CERT_DIR}/mtls-client-ca.crt" >/dev/null; then
        REGENERATION_REASON="mtls-client-ca.crt unreadable, unparseable, or expiring within $((RENEWAL_MARGIN_SECONDS / 86400)) days"
        return 0
    fi

    return 1
}

if ! regeneration_needed; then
    echo "mTLS client material: all three artifacts present, mtls-client-ca.crt readable and not expiring within $((RENEWAL_MARGIN_SECONDS / 86400)) days - skipping regeneration."
    echo "  (pass --force, or set MTLS_CERTS_FORCE=true, to regenerate deliberately)"
    exit 0
fi

echo "Generating mTLS client certificate material in ${CERT_DIR} (${REGENERATION_REASON})..."

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
