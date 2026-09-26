#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test_classes=$(mktemp -d)
trap 'rm -rf "$test_classes"' EXIT
javac -d "$test_classes" \
  app/src/main/java/dev/tilesmile/supernote/{GrayPalette,DotPattern,ToneDocument,DocumentCodec,DrawingBook,BookCodec,ToolSettings,ToolLibrary,BrushDirection,BristleTexture,WetWatercolor,WetWorkBudget,FloodFill,ToneDabs}.java \
  app/src/test/java/dev/tilesmile/supernote/{DocumentChecks,ToolChecks,BookChecks,BristleChecks,WetWatercolorChecks,FlatWashChecks,CanvasPaintChecks}.java
java -cp "$test_classes" dev.tilesmile.supernote.DocumentChecks
java -cp "$test_classes" dev.tilesmile.supernote.ToolChecks
java -cp "$test_classes" dev.tilesmile.supernote.BristleChecks
java -cp "$test_classes" dev.tilesmile.supernote.BookChecks
java -cp "$test_classes" dev.tilesmile.supernote.WetWatercolorChecks
java -cp "$test_classes" dev.tilesmile.supernote.FlatWashChecks
java -cp "$test_classes" dev.tilesmile.supernote.CanvasPaintChecks
