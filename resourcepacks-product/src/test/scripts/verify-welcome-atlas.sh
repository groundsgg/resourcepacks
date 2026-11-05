#!/usr/bin/env bash
set -euo pipefail
if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "Usage: $0 <minecraft-client.jar> <bitmap.png> [columns]" >&2
  exit 2
fi
client_jar=$(realpath "$1")
logo_png=$(realpath "$2")
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
atlas_size=$("${JAVA_HOME:+$JAVA_HOME/bin/}javap" -classpath "$client_jar" -p -constants net.minecraft.client.gui.font.FontTexture | sed -nE 's/.* int SIZE = ([0-9]+);/\1/p')
[[ "$atlas_size" =~ ^[0-9]+$ ]] || { echo "Native font atlas size could not be established" >&2; exit 1; }
probe_dir=$(mktemp -d /tmp/grounds-font-atlas.XXXXXX)
trap 'rm -rf -- "$probe_dir"' EXIT
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -cp "$client_jar" -d "$probe_dir" "$script_dir/BitmapFontAtlasProbe.java"
(cd -- "$probe_dir" && "${JAVA_HOME:+$JAVA_HOME/bin/}java" -Djava.awt.headless=true -cp "$probe_dir:$client_jar" BitmapFontAtlasProbe "$logo_png" "$atlas_size" "${3:-1}")
