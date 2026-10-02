#!/bin/bash
# Regenerates the three signing-key files the integration stack mounts into its gateway instances
# at /app/signing-keys (see docker-compose.yml).
#
# TEST MATERIAL. The files are committed, world-readable and unencrypted on purpose: they sign only
# the client assertions and DPoP proofs of the throwaway 'integration' realm. Never use one of them,
# or a file this script produced, outside this stack.
#
# Each file holds exactly what oidc.client_authentication.key_file and oidc.sender_constraint.key_file
# accept: one unencrypted PKCS#8 PRIVATE KEY block followed by the matching PUBLIC KEY block, and
# nothing else. The gateway derives the algorithm and the key id from the key, so neither is
# configured anywhere.
#
#   client-auth-rsa.pem  RSA-2048. The client-authentication key of every key-authenticated compose
#                        gateway (clients integration-client and cookie-refresh-client). It signs
#                        the private_key_jwt client assertion with PS256. All of those instances
#                        mount the same file, so the one JWKS the primary instance publishes at
#                        https://api-sheriff:8443/auth/jwks carries the key each of them signs with.
#   dpop-ec.pem          EC P-256. The DPoP proof key of the instances whose client is
#                        integration-client. Proofs are signed with ES256.
#   dpop-rsa.pem         RSA-2048. The DPoP proof key of the two refresh instances
#                        (api-sheriff-refresh and api-sheriff-cookie-refresh). Proofs are signed with
#                        PS256, so both algorithms run in the native image.
#
# REGENERATING NEEDS NO REALM CHANGE. Keycloak holds no copy of the client key: the
# key-authenticated clients name a JWKS URL in integration-realm.json and Keycloak fetches the public
# key from the gateway. A DPoP proof carries its own public key. So a regenerated file takes effect
# with the next stack start and nothing else has to be edited.

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

echo "Generating signing keys for API Sheriff integration testing in ${SCRIPT_DIR}:"
generate_key_file client-auth-rsa.pem -algorithm RSA -pkeyopt rsa_keygen_bits:2048
generate_key_file dpop-ec.pem -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -pkeyopt ec_param_enc:named_curve
generate_key_file dpop-rsa.pem -algorithm RSA -pkeyopt rsa_keygen_bits:2048
echo "Signing-key generation complete. No realm change is needed."
