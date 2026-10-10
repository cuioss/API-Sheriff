# grpc-echo — in-repo Quarkus gRPC echo upstream for the integration-test matrix.
#
# The gateway dials this service over the compose network (grpc-echo:9000) for the
# unary / server-streaming / deliberate-failure gRPC IT scenarios. It runs the JVM
# fast-jar of the integration-tests module's echo service.
#
# The jar is built by the HOST reactor, not by this image build: the module's
# `grpc-echo-build` execution of the Quarkus `build` goal is bound into the
# `integration-tests` and `jfr` Maven profiles and writes target/quarkus-app at
# `package`. Without starting the stack, the host command is
#
#   ./mvnw package -Pintegration-tests -DskipTests -pl integration-tests -am
#
# The build context is the integration-tests directory, and this image build only
# copies target/quarkus-app from it. It FAILS when that directory is absent — run
# the Maven line above first. The gateway (api-sheriff) is a `provided` dependency
# of the module, so it is NOT bundled into this echo application.

# ---- Runtime stage: run the host-built echo fast-jar on a minimal JRE -----------
FROM eclipse-temurin:25-jre
LABEL org.opencontainers.image.title="API Sheriff gRPC Echo Upstream"
LABEL org.opencontainers.image.description="In-repo Quarkus gRPC echo service backing the gRPC integration-test matrix"
LABEL org.opencontainers.image.vendor="CUIoss"
LABEL org.opencontainers.image.licenses="Apache-2.0"
LABEL org.opencontainers.image.source="https://github.com/cuioss/API-Sheriff"

WORKDIR /app
# Quarkus fast-jar layout: quarkus-run.jar plus the lib/, app/ and quarkus/ trees.
# Owned by the non-root runtime UID so the JVM can read the app without running as root.
COPY --chown=10001:10001 target/quarkus-app/ /app/

# Separate gRPC server, bound on all interfaces so the gateway can reach it by service name.
EXPOSE 9000

# Drop privileges: run the echo service as a non-root numeric UID (no /etc/passwd entry needed).
USER 10001:10001

ENTRYPOINT ["java", \
    "-Dquarkus.grpc.server.use-separate-server=true", \
    "-Dquarkus.grpc.server.host=0.0.0.0", \
    "-Dquarkus.grpc.server.port=9000", \
    "-Dquarkus.http.host=0.0.0.0", \
    "-jar", "quarkus-run.jar"]
