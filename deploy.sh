#!/usr/bin/env sh
# Tag the current commit and push it, triggering the Release workflow
# (.github/workflows/release.yml) which builds and publishes the tarball.
set -eu

tag="${1:-}"
if [ -z "$tag" ]; then
  printf 'Release tag (e.g. v0.1.0): '
  read -r tag
fi

case "$tag" in
  v*) ;;
  *) echo "error: tag must start with 'v' (got '$tag')" >&2; exit 2 ;;
esac

notes="${2:-}"
if [ -z "$notes" ]; then
  printf 'Release notes (blank line to finish):\n'
  notes="$(while IFS= read -r line && [ -n "$line" ]; do printf '%s\n' "$line"; done)"
fi

# Annotated tag: the message becomes the GitHub Release body (see release.yml).
git tag -a "$tag" -m "${notes:-$tag}"
git push origin "$tag"
echo "Pushed $tag — watch the Release workflow on GitHub."
