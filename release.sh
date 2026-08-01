#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# release.sh – automated release workflow for firebirdsql-monitoring
#
# Steps performed:
#   1.  Commit & push current branch (if there is anything pending)
#   2.  Create release branch  release/<version>
#   3.  Check out that branch
#   4.  Strip -SNAPSHOT from projectVersion in gradle.properties
#   5.  Clean build + full test suite
#   6.  Commit version bump & create tag <version>
#   7.  Push branch and tag to origin
#   8.  Check out the tag (detached HEAD)
#   9.  Clean compile + push multi-platform Docker image (linux/amd64, linux/arm64)
#
# Rollback behaviour:
#   - If any step from 2 to 6 fails the release branch and tag (if already
#     created) are deleted locally and gradle.properties is restored so the
#     working tree is left exactly as it was before the script ran.
#   - Once the branch and tag have been pushed (step 7) they are kept even on
#     subsequent failures (e.g. a docker push failure), because remote state
#     is not automatically revertible.
#
# Prerequisites:
#   - DOCKER_TOKEN env var must be set (Docker Hub access token for wolfgangreder)
#   - git, docker (with buildx), and a working JDK 25+ must be on PATH
#   - SSH agent must be running with the commit/tag signing key loaded
#     (commit.gpgsign=true and tag.gpgsign=true are set in git config)
#
# Usage:
#   DOCKER_TOKEN=<token> ./release.sh
# ---------------------------------------------------------------------------
set -euo pipefail

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------
GRADLE_PROPS="gradle.properties"
DOCKER_USER="wolfgangreder"
DOCKER_REGISTRY="docker.io"
BUILDER_NAME="frodo-multiplatform"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
die()  { echo "ERROR: $*" >&2; exit 1; }
info() { echo ""; echo "==> $*"; }

# ---------------------------------------------------------------------------
# Rollback state  (mutated as the script progresses)
# ---------------------------------------------------------------------------
ORIGINAL_BRANCH=""
BRANCH_CREATED=false
TAG_CREATED=false
PROPS_MODIFIED=false

do_rollback() {
  local exit_code=$?
  trap - ERR  # prevent recursive invocation

  echo ""
  echo "ERROR: step failed (exit ${exit_code}) – rolling back local changes..."

  # Return to the original branch before touching anything else
  if [[ -n "${ORIGINAL_BRANCH}" ]]; then
    git checkout "${ORIGINAL_BRANCH}" 2>/dev/null || true
  fi

  # Restore gradle.properties when the change was only in the working tree
  # (i.e. modified but not yet committed on the release branch)
  if ${PROPS_MODIFIED} && ! ${TAG_CREATED}; then
    git restore "${GRADLE_PROPS}" 2>/dev/null || true
    echo "  Restored ${GRADLE_PROPS} to snapshot version"
  fi
  # When TAG_CREATED is true the version bump was already committed; checking
  # out ORIGINAL_BRANCH above already reverted the file, nothing else needed.

  # Delete local tag (not yet on remote)
  if ${TAG_CREATED}; then
    git tag -d "${TAG}" 2>/dev/null && echo "  Deleted local tag '${TAG}'" || true
  fi

  # Delete local release branch (not yet on remote)
  if ${BRANCH_CREATED}; then
    git branch -D "${BRANCH}" 2>/dev/null && echo "  Deleted local branch '${BRANCH}'" || true
  fi

  echo "  Rollback complete – working tree restored to '${ORIGINAL_BRANCH}'"
  exit "${exit_code}"
}

# Active until the branch and tag have been pushed (step 7)
trap 'do_rollback' ERR

# ---------------------------------------------------------------------------
# Derive versions
# ---------------------------------------------------------------------------
SNAPSHOT_VERSION=$(grep '^projectVersion=' "${GRADLE_PROPS}" | cut -d= -f2)
[[ -z "${SNAPSHOT_VERSION}" ]] && die "Could not read projectVersion from ${GRADLE_PROPS}"

RELEASE_VERSION="${SNAPSHOT_VERSION%-SNAPSHOT}"
[[ "${RELEASE_VERSION}" == "${SNAPSHOT_VERSION}" ]] \
  && die "projectVersion '${SNAPSHOT_VERSION}' does not end in -SNAPSHOT – already released?"

BRANCH="release/${RELEASE_VERSION}"
TAG="${RELEASE_VERSION}"

echo "Snapshot version : ${SNAPSHOT_VERSION}"
echo "Release version  : ${RELEASE_VERSION}"
echo "Release branch   : ${BRANCH}"
echo "Tag              : ${TAG}"

# Bail out early if tag or branch already exists
git rev-parse --verify "refs/tags/${TAG}" &>/dev/null \
  && die "Tag '${TAG}' already exists – aborting"
git rev-parse --verify "refs/heads/${BRANCH}" &>/dev/null \
  && die "Branch '${BRANCH}' already exists locally – aborting"

# Pre-flight: verify the SSH signing key is loaded so git commits and tags
# never stall waiting for a passphrase (commit.gpgsign=true, tag.gpgsign=true).
SIGNING_KEY=$(git config --get user.signingkey 2>/dev/null || true)
if [[ -n "${SIGNING_KEY}" ]]; then
  SIGNING_KEY_PATH="${SIGNING_KEY/#\~/$HOME}"   # expand leading ~
  if ! ssh-add -L 2>/dev/null | grep -qF "$(ssh-keygen -y -f "${SIGNING_KEY_PATH}" 2>/dev/null || true)"; then
    die "SSH signing key '${SIGNING_KEY}' is not loaded in the agent. Run: ssh-add ${SIGNING_KEY}"
  fi
  echo "SSH signing key loaded: ${SIGNING_KEY}"
fi

# ---------------------------------------------------------------------------
# Step 1 – commit & push current branch (if needed)
# ---------------------------------------------------------------------------
info "Step 1: commit & push current branch (if needed)"

ORIGINAL_BRANCH=$(git rev-parse --abbrev-ref HEAD)
[[ "${ORIGINAL_BRANCH}" == "HEAD" ]] && die "Repository is in detached HEAD state – cannot proceed"

# Commit any pending changes
if [[ -n "$(git status --porcelain)" ]]; then
  echo "  Uncommitted changes found – committing..."
  git add -A
  git commit -m "chore: pre-release cleanup on ${ORIGINAL_BRANCH}"
else
  echo "  Working tree is clean – nothing to commit"
fi

# Push if the branch has an upstream; create tracking upstream if not
if git rev-parse --abbrev-ref "@{u}" &>/dev/null 2>&1; then
  AHEAD=$(git rev-list "@{u}..HEAD" --count)
  if [[ "${AHEAD}" -gt 0 ]]; then
    echo "  ${AHEAD} unpushed commit(s) – pushing..."
    git push origin "${ORIGINAL_BRANCH}"
  else
    echo "  Branch is up-to-date with remote – nothing to push"
  fi
else
  echo "  No upstream configured – pushing and setting upstream..."
  git push -u origin "${ORIGINAL_BRANCH}"
fi

# ---------------------------------------------------------------------------
# Step 2 & 3 – create and check out the release branch
# ---------------------------------------------------------------------------
info "Step 2-3: create and check out branch '${BRANCH}'"
git checkout -b "${BRANCH}"
BRANCH_CREATED=true

# ---------------------------------------------------------------------------
# Step 4 – strip -SNAPSHOT from projectVersion
# ---------------------------------------------------------------------------
info "Step 4: set projectVersion to '${RELEASE_VERSION}'"
sed -i "s/^projectVersion=.*/projectVersion=${RELEASE_VERSION}/" "${GRADLE_PROPS}"
PROPS_MODIFIED=true
grep '^projectVersion=' "${GRADLE_PROPS}"   # confirm

# ---------------------------------------------------------------------------
# Step 5 – clean build + full test suite
# ---------------------------------------------------------------------------
info "Step 5: clean build and test"
./gradlew clean build

# ---------------------------------------------------------------------------
# Step 6 – commit version bump and create tag
# ---------------------------------------------------------------------------
info "Step 6: commit version bump and create tag '${TAG}'"
git add "${GRADLE_PROPS}"
git commit -m "release: ${RELEASE_VERSION}"
TAG_CREATED=true
git tag -m "release: ${RELEASE_VERSION}" "${TAG}"

# ---------------------------------------------------------------------------
# Step 7 – push branch and tag
# ---------------------------------------------------------------------------
info "Step 7: push branch '${BRANCH}' and tag '${TAG}'"
git push -u origin "${BRANCH}"
git push origin "${TAG}"

# Branch and tag are now on the remote – disable the rollback trap.
# A failure from here on (e.g. docker push) will NOT revert git state.
trap - ERR

# ---------------------------------------------------------------------------
# Step 8 – check out the tag
# ---------------------------------------------------------------------------
info "Step 8: check out tag '${TAG}'"
git checkout "${TAG}"

# ---------------------------------------------------------------------------
# Step 9 – build and push multi-platform Docker image
# ---------------------------------------------------------------------------
info "Step 9: build and push Docker image"

[[ -z "${DOCKER_TOKEN:-}" ]] && die "DOCKER_TOKEN environment variable is not set"

echo "  Logging in to ${DOCKER_REGISTRY} as ${DOCKER_USER}..."
echo "${DOCKER_TOKEN}" | docker login "${DOCKER_REGISTRY}" \
  --username "${DOCKER_USER}" --password-stdin

trap 'echo ""; echo "Logging out from ${DOCKER_REGISTRY}..."; docker logout "${DOCKER_REGISTRY}"' EXIT

if ! docker buildx inspect "${BUILDER_NAME}" &>/dev/null; then
  echo "  Creating multi-platform buildx builder '${BUILDER_NAME}'..."
  docker buildx create --name "${BUILDER_NAME}" --driver docker-container --bootstrap
fi
docker buildx use "${BUILDER_NAME}"

# Tests were already run in step 5 on the same source; skip them here.
./gradlew clean assemble \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.push=true \
  -Dquarkus.docker.buildx.platform=linux/amd64,linux/arm64 \
  "-Dquarkus.container-image.additional-tags=latest,${RELEASE_VERSION}"

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------
echo ""
echo "Release ${RELEASE_VERSION} complete!"
echo "  Branch : ${BRANCH}"
echo "  Tag    : ${TAG}"
echo "  Image  : ${DOCKER_REGISTRY}/${DOCKER_USER}/at.or.reder.firebirdsql-monitoring:${RELEASE_VERSION}"
