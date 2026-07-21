# grpc-echo — in-repo Quarkus gRPC echo upstream for the integration-test matrix.
#
# The gateway dials this service over the compose network (grpc-echo:9000) for the
# unary / server-streaming / deliberate-failure gRPC IT scenarios. It is built as a
# JVM fast-jar of the integration-tests module's echo service via the `grpc-echo`
# Maven profile, which is the only place the module's Quarkus `build` goal is enabled
# (the normal build only runs gRPC proto codegen). The build context is the repository
# root so `-am` can build the reactor; the gateway (api-sheriff) is a `provided`
# dependency, so it is NOT bundled into this echo application.

# ---- Build stage: produce the echo fast-jar ------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN ./mvnw -B -e -DskipTests -pl integration-tests -am -Pgrpc-echo clean package

# ---- Runtime stage: run the echo fast-jar on a minimal JRE ---------------------
FROM eclipse-temurin:25-jre
LABEL org.opencontainers.image.title="API Sheriff gRPC Echo Upstream"
LABEL org.opencontainers.image.description="In-repo Quarkus gRPC echo service backing the gRPC integration-test matrix"
LABEL org.opencontainers.image.vendor="CUIoss"
LABEL org.opencontainers.image.licenses="Apache-2.0"
LABEL org.opencontainers.image.source="https://github.com/cuioss/API-Sheriff"

WORKDIR /app
# Quarkus fast-jar layout: quarkus-run.jar plus the lib/, app/ and quarkus/ trees.
COPY --from=build /src/integration-tests/target/quarkus-app/ /app/

# Separate gRPC server, bound on all interfaces so the gateway can reach it by service name.
EXPOSE 9000

ENTRYPOINT ["java", \
    "-Dquarkus.grpc.server.use-separate-server=true", \
    "-Dquarkus.grpc.server.host=0.0.0.0", \
    "-Dquarkus.grpc.server.port=9000", \
    "-Dquarkus.http.host=0.0.0.0", \
    "-jar", "quarkus-run.jar"]
