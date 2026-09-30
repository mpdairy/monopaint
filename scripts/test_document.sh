#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test_classes=$(mktemp -d)
trap 'rm -rf "$test_classes"' EXIT
javac -d "$test_classes" \
  app/src/main/java/io/github/mpdairy/monopaint/{GrayPalette,DotPattern,ToneDocument,DocumentCodec,DrawingBook,BookCodec,DrawingFiles,RecoveryCodec,ToolSettings,ToolLibrary,BrushDirection,BristleTexture,WetWatercolor,WetWorkBudget,FloodFill,ToneDabs,DrawingStroke,AirbrushStroke}.java \
  app/src/test/java/io/github/mpdairy/monopaint/{LayerChecks,DocumentChecks,ToolChecks,BookChecks,DrawingFilesChecks,BristleChecks,WetWatercolorChecks,FlatWashChecks,CanvasPaintChecks,GradientFillChecks,AirbrushChecks,EraseChecks}.java
javac -d "$test_classes" app/src/main/java/io/github/mpdairy/monopaint/RotationSuggestion.java app/src/test/java/io/github/mpdairy/monopaint/RotationSuggestionChecks.java
java -cp "$test_classes" io.github.mpdairy.monopaint.RotationSuggestionChecks
java -cp "$test_classes" io.github.mpdairy.monopaint.DocumentChecks
java -cp "$test_classes" io.github.mpdairy.monopaint.ToolChecks
java -cp "$test_classes" io.github.mpdairy.monopaint.BristleChecks
java -cp "$test_classes" io.github.mpdairy.monopaint.BookChecks
java -cp "$test_classes" io.github.mpdairy.monopaint.DrawingFilesChecks
java -cp "$test_classes" io.github.mpdairy.monopaint.WetWatercolorChecks
java -cp "$test_classes" io.github.mpdairy.monopaint.FlatWashChecks
java -cp "$test_classes" io.github.mpdairy.monopaint.CanvasPaintChecks

java -cp "$test_classes" io.github.mpdairy.monopaint.LayerChecks
java -cp "$test_classes" io.github.mpdairy.monopaint.GradientFillChecks

java -cp "$test_classes" io.github.mpdairy.monopaint.AirbrushChecks

java -cp "$test_classes" io.github.mpdairy.monopaint.EraseChecks

javac -d "$test_classes" app/src/main/java/io/github/mpdairy/monopaint/CanvasViewport.java app/src/test/java/io/github/mpdairy/monopaint/ViewportChecks.java
java -cp "$test_classes" io.github.mpdairy.monopaint.ViewportChecks
