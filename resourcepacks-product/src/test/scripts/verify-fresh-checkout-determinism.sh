#!/usr/bin/env bash
set -euo pipefail

source_root=${1:-$(git rev-parse --show-toplevel)}
source_head=$(git -C "$source_root" rev-parse HEAD)
version=$(tr -d '\n' < "$source_root/version.txt")
scratch=$(mktemp -d /tmp/resourcepacks-clone-determinism-XXXXXX)
trap 'rm -rf -- "$scratch"' EXIT

for clone_name in same-a same-b changed; do
  git clone --quiet --shared --no-hardlinks --no-checkout "$source_root" "$scratch/$clone_name"
  git -C "$scratch/$clone_name" checkout --quiet --detach "$source_head"
done

build_release() {
  local clone_name=$1
  local commit=$2
  "$scratch/$clone_name/gradlew" \
    -p "$scratch/$clone_name" \
    :resourcepacks-product:buildPackSet \
    -PpackSetVersion="$version" \
    -PprovenanceCommit="$commit" \
    -PprovenanceTag="v$version" \
    -PreleaseOutput="$scratch/output-$clone_name"
}

build_release same-a aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
build_release same-b aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
build_release changed bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb

find "$scratch/output-same-a" -mindepth 1 -maxdepth 1 -printf '%f\n' | sort > "$scratch/names-a"
find "$scratch/output-same-b" -mindepth 1 -maxdepth 1 -printf '%f\n' | sort > "$scratch/names-b"
find "$scratch/output-changed" -mindepth 1 -maxdepth 1 -printf '%f\n' | sort > "$scratch/names-changed"
cmp "$scratch/names-a" "$scratch/names-b"
cmp "$scratch/names-a" "$scratch/names-changed"
test "$(wc -l < "$scratch/names-a")" -eq 4

while IFS= read -r name; do
  cmp "$scratch/output-same-a/$name" "$scratch/output-same-b/$name"
  if [[ $name == manifest.json ]]; then
    if cmp -s "$scratch/output-same-a/$name" "$scratch/output-changed/$name"; then
      echo "changed commit did not change manifest.json" >&2
      exit 1
    fi
  else
    cmp "$scratch/output-same-a/$name" "$scratch/output-changed/$name"
  fi
done < "$scratch/names-a"

sha256sum "$scratch/output-same-a"/*
echo "fresh checkout determinism verified at $source_head"
