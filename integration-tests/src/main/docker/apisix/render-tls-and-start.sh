#!/bin/sh
# Renders the APISIX route table's TLS entry from the MOUNTED certificate material,
# then hands off to the stock APISIX entrypoint.
#
# WHY THIS EXISTS
# ---------------
# APISIX 3.14 cannot be pointed at a certificate FILE for its TLS listener:
#
#   1. apisix/cli/ops.lua unconditionally overwrites apisix.ssl.ssl_cert and
#      apisix.ssl.ssl_cert_key with its built-in "cert/ssl_PLACE_HOLDER.{crt,key}"
#      on every start, so setting those paths in config.yaml is silently ignored.
#   2. Mounting real material over the placeholder path does not help either: the
#      ssl_client_hello_by_lua phase (apisix/init.lua) ABORTS the handshake with
#      "failed to match any SSL certificate by SNI" before the nginx-level fallback
#      certificate is ever used, so an `ssls` entry is mandatory.
#   3. The `ssls` resource accepts INLINE PEM strings only. This build resolves no
#      `$env://` or `$secret://` references, so the PEM cannot be referenced
#      indirectly.
#
# Committing a second copy of the PEM into apisix.yaml would duplicate the private
# key in the repository and silently drift the moment generate-certificates.sh
# regenerates the material. Instead this script splices the mounted certificate and
# key into the committed route-table template at container start, so
# src/main/docker/certificates stays the single source of truth for all three edges
# (api-sheriff, keycloak, apisix).
set -eu

TEMPLATE="/templates/apisix.yaml"
TARGET="/usr/local/apisix/conf/apisix.yaml"
CERT="/certs/localhost.crt"
KEY="/certs/localhost.key"

for required in "$TEMPLATE" "$CERT" "$KEY"; do
    if [ ! -r "$required" ]; then
        echo "render-tls-and-start.sh: required file is missing or unreadable: $required" >&2
        exit 1
    fi
done

# The standalone config provider requires the literal `#END` marker within the last
# ten bytes of the file, so drop it from the template body and re-append it after
# the rendered ssls block.
sed '/^#END[[:space:]]*$/d' "$TEMPLATE" > "$TARGET"

{
    echo "ssls:"
    echo "  - id: benchmark-tls"
    echo "    snis:"
    # The k6 containers reach this gateway in-network as https://apisix:8443; the
    # host-side readiness probe reaches it as https://localhost:10444. Both SNI
    # values must match or the handshake is aborted before routing.
    echo "      - apisix"
    echo "      - localhost"
    # `sed -n '/^-----BEGIN/,$p'` drops any leading preamble before the first PEM
    # block. localhost.key is emitted by openssl pkcs12, which prefixes the key with
    # "Bag Attributes / friendlyName / localKeyID" lines; APISIX's ssl validator
    # rejects a key whose first line is not a PEM header.
    echo "    cert: |"
    sed -n '/^-----BEGIN/,$p' "$CERT" | sed 's/^/      /'
    echo "    key: |"
    sed -n '/^-----BEGIN/,$p' "$KEY" | sed 's/^/      /'
    echo "#END"
} >> "$TARGET"

exec /docker-entrypoint.sh "$@"
