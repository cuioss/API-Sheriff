#!/bin/bash
# Generate the self-signed TLS material and the BFF signing keys this compose sample runs on.
#
# ###############################################################################################
# #                                                                                             #
# #   SAMPLE MATERIAL ONLY — NEVER USE THE OUTPUT OF THIS SCRIPT IN PRODUCTION.                 #
# #                                                                                             #
# #   It mints a self-signed certificate with no CA behind it, and writes the private key next  #
# #   to it, unencrypted, in a directory the compose stack mounts. It does the same for the two #
# #   keys the gateway's BFF signs with. That is the right trade for a sample you bring up and  #
# #   tear down on one machine, and the wrong one for anything else. A real deployment supplies #
# #   certificate, key and signing keys from its own PKI and mounts them at the same paths —    #
# #   the gateway does not care where they came from.                                           #
# #                                                                                             #
# ###############################################################################################
#
# NO KEY MATERIAL IS COMMITTED. This script is the source of truth; the .gitignore beside it keeps
# every artifact the script produces out of the repository. Run it once before the first bring-up.
#
# Usage:
#   generate-certificates.sh               Regenerate the WHOLE set, always (the operator's command).
#   generate-certificates.sh --if-missing  Leave a complete set untouched; regenerate the WHOLE set
#                                          when any one of the five artifacts is missing. This is
#                                          what the deployment module's -Pcompose-sample profile
#                                          calls, so a fresh checkout gets its material while an
#                                          operator's existing set is never rotated behind their back.
#   generate-certificates.sh --check       Generate nothing. Print the names of the missing artifacts
#                                          on one line and exit 1 when any is missing; exit 0 silently
#                                          when the set is complete. start-sample.sh's preflight calls
#                                          this, so the bring-up reads "complete" from ARTIFACTS below
#                                          instead of keeping a second copy of the list.
#
# "The whole set" in both generating modes, never a partial regeneration: the certificate, its key
# and the truststore are one decision — a store anchoring a certificate Keycloak no longer serves
# fails the JWKS fetch — so a missing member is answered by replacing every member.

set -euo pipefail

CERT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

case "$#:${1:-}" in
    0:) MODE=always ;;
    1:--if-missing) MODE=if-missing ;;
    1:--check) MODE=check ;;
    *)
        echo "Usage: ${0##*/} [--if-missing | --check]" >&2
        exit 2
        ;;
esac

# CN=localhost so a browser on the host addresses the gateway as https://localhost:8443. The SAN list
# is what actually matters to a modern client, and it carries the host-facing name AND every compose
# service name a TLS client dials inside the stack:
#
#   keycloak        the gateway reaches the IdP as `keycloak` on the internal network (JWKS fetch and
#                   the BFF back channel), so a certificate without that name fails hostname
#                   verification there.
#   api-sheriff     Keycloak fetches the BFF's client JWKS from https://api-sheriff:8443/bff/jwks in
#                   the base variant, with hostname verification on.
#   tls-terminator  the same fetch in the plain-HTTP variant goes through the terminating hop at
#                   https://tls-terminator:8443/bff/jwks, because the gateway itself serves no TLS there.
CERT_DNAME="CN=localhost, OU=Compose Sample, O=API-Sheriff, L=Berlin, ST=Berlin, C=DE"
CERT_SAN="dns:localhost,dns:keycloak,dns:api-sheriff,dns:demo-api,dns:tls-terminator,ip:127.0.0.1"
CERT_VALIDITY=365

TEMP_KEYSTORE="${CERT_DIR}/temp-keystore.p12"
TEMP_PASSWORD="temp-$(date +%s)"

# The truststore behind gateway.yaml's `sample-idp` TLS profile, bound by
# sample-idp-trust.properties. Both values below MUST match that file: it names this store's
# container-side path and its password. The store holds exactly ONE entry — the certificate generated
# here, which Keycloak serves — so the JWKS fetch and the BFF back channel trust the sample IdP and
# nothing else. It contains no private key; the password only guards the store's integrity.
TRUSTSTORE="${CERT_DIR}/sample-idp-truststore.p12"
TRUSTSTORE_PASSWORD="sample-idp-trust"

# The BFF's two signing keys. Each file holds exactly what oidc.client_authentication.key_file and
# oidc.sender_constraint.key_file accept: one unencrypted PKCS#8 PRIVATE KEY block followed by the
# matching PUBLIC KEY block, and nothing else. The gateway derives the algorithm and the key id from
# the key, so gateway.yaml names neither.
#
#   client-auth.pem  RSA-2048. Signs the private_key_jwt client assertion with PS256.
#   dpop.pem         EC P-256. Signs the DPoP proofs that bind the BFF's tokens, with ES256.
#
# REGENERATING THEM NEEDS NO REALM CHANGE. Keycloak holds no copy of the client key: sample-client
# names a JWKS URL in sample-realm.json and Keycloak fetches the public half from the gateway's
# /bff/jwks. A DPoP proof carries its own public key. A regenerated pair takes effect with the next
# gateway start and nothing else has to be edited.
CLIENT_AUTH_KEY="${CERT_DIR}/client-auth.pem"
DPOP_KEY="${CERT_DIR}/dpop.pem"

# The set --check and --if-missing check, and the set a run produces. One list, so neither check
# can disagree with the output about what "complete" means.
ARTIFACTS=(
    "${CERT_DIR}/localhost.crt"
    "${CERT_DIR}/localhost.key"
    "${TRUSTSTORE}"
    "${CLIENT_AUTH_KEY}"
    "${DPOP_KEY}"
)

MISSING=()
for artifact in "${ARTIFACTS[@]}"; do
    [[ -f "${artifact}" ]] || MISSING+=("$(basename "${artifact}")")
done

if [[ "${MODE}" == "check" ]]; then
    if (( ${#MISSING[@]} == 0 )); then
        exit 0
    fi
    echo "${MISSING[*]}"
    exit 1
fi

if [[ "${MODE}" == "if-missing" ]]; then
    if (( ${#MISSING[@]} == 0 )); then
        echo "The sample's certificate material and signing keys are complete in ${CERT_DIR};"
        echo "nothing was regenerated. Run without --if-missing to replace the whole set."
        exit 0
    fi
    echo "Missing: ${MISSING[*]} — regenerating the whole set."
fi

# The private half of a signing key is written to this intermediate file before it is joined with its
# public half. It is named *-private.pem so the .gitignore's *.pem covers a run interrupted mid-key.
WORK_FILE=""
# A signing-key file is assembled under this scratch name and moved into place only after its format
# check passes. Also *.pem, so the .gitignore covers it.
ASSEMBLED_FILE=""

# Remove the intermediates on EVERY exit path, not just the success one. Under `set -e` a failing
# keytool or openssl aborts the script before any explicit cleanup, which would otherwise strand a
# PKCS#12 file, a bare private key or a half-assembled key file in a directory an operator is told to
# treat as generated-and-disposable. The trap is armed here, immediately after all three intermediate
# paths are defined, so no
# failure between this line and the end of the script can escape it.
cleanup() {
    rm -f "${TEMP_KEYSTORE}"
    [[ -n "${WORK_FILE}" ]] && rm -f "${WORK_FILE}"
    [[ -n "${ASSEMBLED_FILE}" ]] && rm -f "${ASSEMBLED_FILE}"
    return 0
}
trap cleanup EXIT

echo "Generating SAMPLE self-signed TLS material and BFF signing keys for the API Sheriff compose sample..."
echo "  Directory: ${CERT_DIR}"

# Start from a clean slate so a re-run never leaves a half-replaced set behind. The truststore goes
# too: a store still holding the PREVIOUS certificate would make the JWKS fetch reject the new one.
rm -f "${ARTIFACTS[@]}" "${TEMP_KEYSTORE}"

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

# The gateway verifies Keycloak's certificate on the JWKS fetch and the back channel with full chain
# AND hostname validation — nothing here relaxes either. It can succeed only because this store
# anchors the exact self-signed certificate Keycloak serves, and that certificate's SAN names
# `keycloak`, the host the gateway dials. Same keytool pattern as the integration-test stack's
# generate-truststore.sh.
echo "  Building the sample-idp truststore (the certificate only, no key)..."
keytool -importcert \
  -alias sample-idp \
  -file "${CERT_DIR}/localhost.crt" \
  -keystore "${TRUSTSTORE}" \
  -storetype PKCS12 \
  -storepass "${TRUSTSTORE_PASSWORD}" \
  -noprompt

# Writes one signing-key file: the private key, then its public half. $1 = target path; the remaining
# arguments select the key for `openssl genpkey`. Same pattern as the integration-test stack's
# generate-signing-keys.sh.
generate_key_file() {
    local target="$1"
    shift
    WORK_FILE="${target%.pem}-private.pem"
    ASSEMBLED_FILE="${target%.pem}-assembled.pem"
    openssl genpkey "$@" -out "${WORK_FILE}"
    {
        cat "${WORK_FILE}"
        openssl pkey -in "${WORK_FILE}" -pubout
    } > "${ASSEMBLED_FILE}"
    rm -f "${WORK_FILE}"
    WORK_FILE=""
    # The two labels are what the gateway accepts. An openssl build that writes a traditional
    # 'RSA PRIVATE KEY' or 'EC PRIVATE KEY' block would produce a file the gateway refuses at boot,
    # so that is caught here and not in the stack. The file is assembled under a scratch name and only
    # moved to its real name once it passes: --if-missing and --check test presence alone, so a failed
    # or interrupted run must never leave a bad key at the name those checks treat as complete.
    if ! grep -qx -- '-----BEGIN PRIVATE KEY-----' "${ASSEMBLED_FILE}" \
        || ! grep -qx -- '-----BEGIN PUBLIC KEY-----' "${ASSEMBLED_FILE}"; then
        echo "❌ ${target} does not hold a PKCS#8 PRIVATE KEY block and a PUBLIC KEY block." >&2
        exit 1
    fi
    mv -f "${ASSEMBLED_FILE}" "${target}"
    ASSEMBLED_FILE=""
}

echo "  Generating the BFF client-authentication key (RSA-2048, PS256)..."
generate_key_file "${CLIENT_AUTH_KEY}" -algorithm RSA -pkeyopt rsa_keygen_bits:2048
echo "  Generating the BFF DPoP key (EC P-256, ES256)..."
generate_key_file "${DPOP_KEY}" -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -pkeyopt ec_param_enc:named_curve

# EVERY file is world-readable, the keys deliberately so. Read this before "tightening" it:
#
# The two containers that read these files run as DIFFERENT non-root uids — the gateway as
# nonroot (65532, from its Dockerfile) and Keycloak as 1000 — while these files are owned by
# whichever host user ran this script. Neither uid can be matched from the host without root, so
# mode 600 makes a key unreadable inside the container and the gateway dies at boot with
# `AccessDeniedException: /app/certificates/localhost.key` (or client-auth.pem, or dpop.pem).
# World-read is what makes a non-root, read_only, cap_drop-ALL container able to serve TLS and sign
# its client assertions at all.
#
# That trade is acceptable here and ONLY here: these keys are self-signed or unregistered anywhere
# but the sample realm, git-ignored, regenerated on demand and never leave this machine. They protect
# nothing, so exposing them to other local accounts costs nothing. A real deployment does the
# opposite — it supplies key material from its own PKI with restrictive ownership matched to the
# runtime uid, mounts the signing keys into the gateway only, and mounts them at these same paths.
#
# (integration-tests/src/main/docker/certificates/generate-certificates.sh still chmods its key to
# 600, but that mode is nominal: those certificates are COMMITTED, and git records only the exec
# bit, so a fresh checkout materialises them world-readable anyway. The sample commits no key
# material, so the mode it is created with is the mode the container actually sees.)
chmod 644 "${CERT_DIR}/localhost.crt"
chmod 644 "${CERT_DIR}/localhost.key"
chmod 644 "${CLIENT_AUTH_KEY}"
chmod 644 "${DPOP_KEY}"
# The truststore holds only the public certificate, so world-read costs nothing and is required for
# the same uid-mismatch reason as above.
chmod 644 "${TRUSTSTORE}"

# No explicit removal of the intermediates here — the EXIT trap armed above owns that, on this path
# and on every failure path alike. A second copy would only be a second thing to keep in step.

echo ""
echo "Done. Generated (all git-ignored):"
echo "  localhost.crt              the certificate, mounted into api-sheriff and keycloak"
echo "  localhost.key              the private key  — sample only, never leaves this machine"
echo "  sample-idp-truststore.p12  the gateway's trust anchor for Keycloak (certificate only)"
echo "  client-auth.pem            the BFF's private_key_jwt signing key (RSA, PS256) — sample only"
echo "  dpop.pem                   the BFF's DPoP proof key (EC P-256, ES256) — sample only"
echo ""
echo "Valid ${CERT_VALIDITY} days. Subject: ${CERT_DNAME}"
echo "SAN: ${CERT_SAN}"
echo ""
echo "Regenerating the signing keys needs no realm change: Keycloak fetches their public half"
echo "from the gateway's /bff/jwks."
echo ""
echo "Because the certificate is self-signed, every client must be told to trust it, e.g. from"
echo "deployment/compose-sample:"
echo "  curl --cacert docker/certificates/localhost.crt https://localhost:8443/api"
echo "A browser will show a warning. That is correct, and it is the reason this is a sample."
