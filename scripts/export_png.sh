#!/usr/bin/env bash
set -euo pipefail
if (( $# < 3 || $# > 4 )); then
  echo 'Usage: bash scripts/export_png.sh drawing.tsm page-number output.png [calibrated|raw|dots]' >&2
  exit 2
fi
export_repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
export_classes=$(mktemp -d)
trap 'rm -rf "$export_classes"' EXIT
javac -d "$export_classes" \
  "$export_repo_root"/app/src/main/java/dev/tilesmile/supernote/{GrayPalette,DotPattern,ToneDocument,DocumentCodec,DrawingBook,BookCodec}.java \
  "$export_repo_root/scripts/java/ExportPng.java"
java -Djava.awt.headless=true -cp "$export_classes" dev.tilesmile.supernote.ExportPng "$1" "$2" "$3" "${4:-calibrated}"
