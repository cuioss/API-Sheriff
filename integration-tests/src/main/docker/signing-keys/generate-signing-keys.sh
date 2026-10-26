#!/bin/bash
# Regenerates the gateway key files the integration stack mounts into its gateway instances at
# /app/signing-keys (see docker-compose.yml): the three signing-key PEM files and the two
# cookie-mode sealing keys. The directory keeps its name although it now holds both kinds.
#
# TEST MATERIAL. The files are committed, world-readable and unencrypted on purpose: the signing
# keys sign only the client assertions and DPoP proofs of the throwaway 'integration' realm, and the
# sealing keys seal only the cookie sessions of the test stack. Never use one of them, or a file this
# script produced, outside this stack.
#
# SIGNING KEYS. Each PEM file holds exactly what oidc.client_authentication.key_file or
# oidc.sender_constraint.key_file accepts: one unencrypted PKCS#8 PRIVATE KEY block followed by the
# matching PUBLIC KEY block, and nothing else. The gateway derives the algorithm and the key id from
# the key, so the gateway configuration names neither. Every key serves ONE purpose: the gateway
# refuses at boot a configuration that names the same key for client authentication and for DPoP,
# so no instance may point both fields at one of these files.
#
#   client-auth-rsa.pem  RSA-2048. The client-authentication key of every key-authenticated compose
#                        gateway (clients integration-client and cookie-refresh-client). It signs
#                        the private_key_jwt client assertion with PS256. All of those instances
#                        mount the same file, so the one JWKS the primary instance publishes at
#                        https://api-sheriff:8443/bff/jwks carries the key each of them signs with.
#   dpop-ec.pem          EC P-256. The DPoP proof key of the instances whose client is
#                        integration-client. Proofs are signed with ES256.
#   dpop-rsa.pem         RSA-2048. The DPoP proof key of the two refresh instances
#                        (api-sheriff-refresh and api-sheriff-cookie-refresh). Proofs are signed with
#                        PS256, so both algorithms run in the native image.
#
# SEALING KEYS. Each .key file holds exactly what oidc.session.key_file accepts: one line of base64
# that decodes to exactly 32 bytes (an AES-256 key), followed by a newline.
#
#   cookie-sealing.key          Shared by api-sheriff-cookie and api-sheriff-cookie-2. Holding the
#                               same key is what lets the second instance unseal a cookie the first
#                               one sealed, which is the statelessness property under test.
#   cookie-refresh-sealing.key  Held by api-sheriff-cookie-refresh alone, so a cookie sealed there
#                               unseals on no other instance.
#
# REGENERATING NEEDS NO REALM CHANGE. Keycloak holds no copy of the client key: the
# key-authenticated clients name a JWKS URL in integration-realm.json and Keycloak fetches the public
# key from the gateway. A DPoP proof carries its own public key. Regenerating a sealing key only ends
# the cookie sessions sealed under the old one: the browser re-authenticates. So a regenerated file
# takes effect with the next stack start and nothing else has to be edited.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WORK_FILE=""

cleanup() {
    [[ -n "${WORK_FILE}" ]] && rm -f "${WORK_FILE}"
    return 0
}
trap cleanup EXIT

# Writes one key file. $1 = file name; the remaining arguments select the key for `openssl genpkey`.
generate_key_file() {
    local target="${SCRIPT_DIR}/$1"
    shift
    WORK_FILE="${target}.private"
    openssl genpkey "$@" -out "${WORK_FILE}"
    {
        cat "${WORK_FILE}"
        openssl pkey -in "${WORK_FILE}" -pubout
    } > "${target}"
    rm -f "${WORK_FILE}"
    WORK_FILE=""
    # The two labels are what the gateway accepts. An openssl build that writes a traditional
    # 'RSA PRIVATE KEY' or 'EC PRIVATE KEY' block would produce a file the gateway refuses at boot,
    # so that is caught here and not in the stack.
    if ! grep -qx -- '-----BEGIN PRIVATE KEY-----' "${target}" \
        || ! grep -qx -- '-----BEGIN PUBLIC KEY-----' "${target}"; then
        echo "❌ ${target} does not hold a PKCS#8 PRIVATE KEY block and a PUBLIC KEY block." >&2
        exit 1
    fi
    # Readable by the container's non-root user. The key protects nothing outside this stack.
    chmod 644 "${target}"
    echo "  - $(basename "${target}")"
}

# Writes one sealing key file. $1 = file name.
generate_sealing_key_file() {
    local target="${SCRIPT_DIR}/$1"
    local decoded_bytes
    openssl rand -base64 32 > "${target}"
    # The gateway refuses at boot a key file that does not decode to exactly 32 bytes, so a short or
    # long key is caught here and not in the stack.
    decoded_bytes="$(openssl base64 -d -A -in "${target}" | wc -c | tr -d '[:space:]')"
    if [[ "${decoded_bytes}" != "32" ]]; then
        echo "❌ ${target} decodes to ${decoded_bytes} bytes, not the 32 an AES-256 sealing key needs." >&2
        exit 1
    fi
    # Readable by the container's non-root user, like the PEM files. The key protects nothing outside
    # this stack.
    chmod 644 "${target}"
    echo "  - $(basename "${target}")"
}

echo "Generating gateway key files for API Sheriff integration testing in ${SCRIPT_DIR}:"
generate_key_file client-auth-rsa.pem -algorithm RSA -pkeyopt rsa_keygen_bits:2048
generate_key_file dpop-ec.pem -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -pkeyopt ec_param_enc:named_curve
generate_key_file dpop-rsa.pem -algorithm RSA -pkeyopt rsa_keygen_bits:2048
generate_sealing_key_file cookie-sealing.key
generate_sealing_key_file cookie-refresh-sealing.key
echo "Key generation complete. No realm change is needed."
