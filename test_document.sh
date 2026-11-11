#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test_classes=$(mktemp -d)
trap 'rm -rf "$test_classes"' EXIT
javac -d "$test_classes" \
  app/src/main/java/io/github/mpdairy/monopaint/{GrayPalette,DotPattern,ToneDocument,DocumentCodec,DrawingBook,BookCodec,DrawingFiles,RecoveryCodec,ToolSettings,ToolLibrary,BrushDirection,BristleTexture,WetWatercolor,WetWorkBudget,FloodFill,ToneDabs,DrawingStroke,ToolStroke,ShapeStroke,ShapeGeometry,DirtyRegions,PaintLoad,BrushInk,WatercolorWash,FloatingSelection}.java \
  app/src/test/java/io/github/mpdairy/monopaint/{LayerChecks,MaskChecks,DocumentChecks,ToolChecks,BookChecks,DrawingFilesChecks,BristleChecks,WetWatercolorChecks,FlatWashChecks,CanvasPaintChecks,GradientFillChecks,AirbrushChecks,EraseChecks,ShapeChecks,PaintLoadChecks,CanvasSizeChecks,SelectionChecks}.java
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
java -cp "$test_classes" io.github.mpdairy.monopaint.MaskChecks
java -cp "$test_classes" io.github.mpdairy.monopaint.GradientFillChecks

java -cp "$test_classes" io.github.mpdairy.monopaint.AirbrushChecks

java -cp "$test_classes" io.github.mpdairy.monopaint.EraseChecks

javac -d "$test_classes" app/src/main/java/io/github/mpdairy/monopaint/CanvasViewport.java app/src/test/java/io/github/mpdairy/monopaint/ViewportChecks.java
java -cp "$test_classes" io.github.mpdairy.monopaint.ViewportChecks
javac -d "$test_classes" app/src/main/java/io/github/mpdairy/monopaint/FingerTwist.java app/src/test/java/io/github/mpdairy/monopaint/FingerTwistChecks.java
java -cp "$test_classes" io.github.mpdairy.monopaint.FingerTwistChecks
# The native renderer's turned-page sampling is plain C, so it is checked on the desktop too.
cc -std=c11 -O2 -Wall -Wextra -Werror -ffp-contract=off -Iapp/src/main/cpp -o "$test_classes/viewport_turn_checks" app/src/test/cpp/viewport_turn_checks.c -lm
"$test_classes/viewport_turn_checks"

java -cp "$test_classes" io.github.mpdairy.monopaint.ShapeChecks
java -cp "$test_classes" io.github.mpdairy.monopaint.SelectionChecks

java -cp "$test_classes" io.github.mpdairy.monopaint.PaintLoadChecks

java -cp "$test_classes" io.github.mpdairy.monopaint.CanvasSizeChecks
javac -d "$test_classes" app/src/main/java/io/github/mpdairy/monopaint/ExportSize.java app/src/test/java/io/github/mpdairy/monopaint/ExportSizeChecks.java
java -cp "$test_classes" io.github.mpdairy.monopaint.ExportSizeChecks
javac -d "$test_classes" app/src/main/java/io/github/mpdairy/monopaint/MultiFingerTap.java app/src/test/java/io/github/mpdairy/monopaint/MultiFingerTapChecks.java
java -cp "$test_classes" io.github.mpdairy.monopaint.MultiFingerTapChecks

javac -d "$test_classes" app/src/main/java/io/github/mpdairy/monopaint/EdgeSwipe.java app/src/test/java/io/github/mpdairy/monopaint/EdgeSwipeChecks.java
java -cp "$test_classes" io.github.mpdairy.monopaint.EdgeSwipeChecks

javac -d "$test_classes" app/src/test/java/io/github/mpdairy/monopaint/DeviceChecks.java
java -cp "$test_classes" io.github.mpdairy.monopaint.DeviceChecks
