#!/usr/bin/env bash
# Prepare the host directory that docker-compose.jfr.yml bind-mounts at /tmp/jfr-output.
#
# The JFR image runs as uid 1001, and the Dockerfile's own `chmod 777 /tmp/jfr-output` does NOT
# survive the mount — a bind mount shadows the image's directory with the host's, ownership
# included. Whichever `docker compose` command touches the service first creates the missing host
# directory as root:root 0755, after which uid 1001 cannot write and the gateway dies at startup
# with "Could not start recording, not able to write to file /tmp/jfr-output/api-sheriff-profile.jfr".
# That is a startup failure, not a degraded profile: the JFR lane never comes up at all.
#
# So this must run before ANY compose command that resolves the jfr overlay. There are two such
# entry points and each is genuinely the first toucher on its own path, which is why both call
# this one script rather than open-coding it:
#   - the `jfr` Maven profile, whose `docker-build-jfr` execution composes the overlay to build;
#   - start-integration-container.sh, for a lane that composes the overlay without that execution
#     having run (the benchmark lane reuses an already-present api-sheriff:jfr image).
#
# Mode 1777, not 0777, for the same reason the /logs mount uses it: the sticky bit keeps the world
# write from also being a world DELETE of the recording this lane exists to produce.
set -euo pipefail

JFR_TARGET_DIR="${1:?usage: prepare-jfr-output-dir.sh <integration-tests-dir>}/target/jfr-recordings"

mkdir -p "${JFR_TARGET_DIR}"
chmod 1777 "${JFR_TARGET_DIR}"
echo "📁 JFR recordings will be written to: ${JFR_TARGET_DIR}/api-sheriff-profile.jfr"
