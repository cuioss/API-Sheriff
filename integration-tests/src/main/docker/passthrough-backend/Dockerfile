# passthrough-backend — TLS target of the integration stack's accept-time SNI passthrough relay.
#
# An integration-test FIXTURE only: it is built by docker-compose.yml for the integration stack and
# is never shipped, published or referenced by any production image or deployment.
#
# The image differs from the pinned nginx base in exactly one way: openssl is installed here, at
# BUILD time, rather than by the container's entrypoint at every start. Start-up therefore fetches
# nothing from a package index. The CERTIFICATE is still generated at container start, by the
# entrypoint docker-compose.yml declares for this service, so every container gets fresh self-signed
# key material that chains to no committed truststore — the property the egress chain-trust control
# relies on.
#
# The FROM line carries the nginx pin of the integration surface: the same content digest
# docker-compose.yml, docker-compose.benchmark.yml and BffTlsRelaxationIT.PROXY_IMAGE bind. Move all
# of them together; the tag is kept for readability only.
#
# The process user is left at the base image's default, as before this image existed: the
# entrypoint writes the generated key pair and the rendered server block below /etc/nginx at start.
FROM nginx:1.27-alpine@sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10
LABEL org.opencontainers.image.title="API Sheriff passthrough-backend test fixture"
LABEL org.opencontainers.image.description="nginx with openssl preinstalled, backing the integration-test TLS passthrough relay"
LABEL org.opencontainers.image.vendor="CUIoss"
LABEL org.opencontainers.image.licenses="Apache-2.0"
LABEL org.opencontainers.image.source="https://github.com/cuioss/API-Sheriff"

RUN apk add --no-cache openssl

EXPOSE 8443
