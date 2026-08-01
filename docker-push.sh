#!/usr/bin/env bash
set -euo pipefail

# Build and push the firebirdsql-monitoring Docker image to Docker Hub.
#
# Usage:
#   ./docker-push.sh [EXTRA_TAG]
#
# Arguments:
#   EXTRA_TAG  (optional) An additional tag to push alongside 'latest',
#              e.g. a stable release name or semantic version.
#
# Required environment variable:
#   DOCKER_TOKEN  - Docker Hub access token for wolfgangreder

DOCKER_USER="wolfgangreder"
DOCKER_REGISTRY="docker.io"
BUILDER_NAME="firebirdsql-monitoring-multiplatform"
EXTRA_TAG="${1:-}"

if [[ -z "${DOCKER_TOKEN:-}" ]]; then
  echo "Error: DOCKER_TOKEN environment variable is not set." >&2
  exit 1
fi

# Read version from gradle.properties
PROJECT_VERSION=$(grep '^projectVersion=' gradle.properties | cut -d= -f2)
RELEASE_VERSION="${PROJECT_VERSION%-SNAPSHOT}"

# 'latest' and version tag are always added; append extra tag when supplied.
ADDITIONAL_TAGS="latest,${RELEASE_VERSION}"
if [[ -n "${EXTRA_TAG}" ]]; then
  ADDITIONAL_TAGS="${ADDITIONAL_TAGS},${EXTRA_TAG}"
fi

echo "Logging in to ${DOCKER_REGISTRY} as ${DOCKER_USER}..."
echo "${DOCKER_TOKEN}" | docker login "${DOCKER_REGISTRY}" --username "${DOCKER_USER}" --password-stdin

trap 'echo "Logging out from ${DOCKER_REGISTRY}..."; docker logout "${DOCKER_REGISTRY}"' EXIT

# Ensure a multi-platform buildx builder is available.
# The default "docker" driver does not support multi-platform builds;
# the "docker-container" driver is required.
if ! docker buildx inspect "${BUILDER_NAME}" &>/dev/null; then
  echo "Creating multi-platform buildx builder '${BUILDER_NAME}'..."
  docker buildx create --name "${BUILDER_NAME}" --driver docker-container --bootstrap
fi
docker buildx use "${BUILDER_NAME}"

echo "Building and pushing multi-platform Docker image (linux/amd64, linux/arm64)..."
echo "Tags: ${ADDITIONAL_TAGS} (plus version tag from project)"
./gradlew build \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.push=true \
  -Dquarkus.docker.buildx.platform=linux/amd64,linux/arm64 \
  -Dquarkus.container-image.additional-tags="${ADDITIONAL_TAGS}"

echo "Done. Image pushed to ${DOCKER_REGISTRY}/${DOCKER_USER}/at.or.reder.firebird-monitoring"
