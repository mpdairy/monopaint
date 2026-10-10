package io.github.mpdairy.monopaint;

import java.util.Arrays;

public final class ExportSizeChecks {
    public static void main(String[] args) {
        ExportSize nomad = ExportSize.screen(1404, 1872);
        check(Arrays.equals(ExportSize.PAGE.of(1800, 2400), new int[]{1800, 2400}), "Page size is unchanged");
        check(Arrays.equals(nomad.of(1920, 2560), new int[]{1404, 1872}), "A full Manta page fills the Nomad");
        check(Arrays.equals(nomad.of(2560, 1920), new int[]{1872, 1404}), "Landscape pages fill the screen turned");
        check(Arrays.equals(nomad.of(1000, 2560), new int[]{731, 1872}), "A narrow page keeps its proportions");
        check(Arrays.equals(nomad.of(1920, 1920), new int[]{1404, 1404}), "A square page fits the shorter side");
        check(Arrays.equals(ExportSize.screen(1920, 2560).of(1404, 1872), new int[]{1920, 2560}), "Nomad pages grow to fill the Manta");
        check(Arrays.equals(ExportSize.width(1404, 1920).of(1920, 2560), new int[]{1404, 1872}), "Custom width sets the height");
        check(Arrays.equals(ExportSize.height(1872, 2560).of(1920, 2560), new int[]{1404, 1872}), "Custom height sets the width");
        check(Arrays.equals(ExportSize.width(960, 1920).of(1000, 600), new int[]{500, 300}), "Other pages scale alike");
        check(Arrays.equals(ExportSize.width(1, 1920).of(1920, 10), new int[]{1, 1}), "Never smaller than a pixel");
        System.out.println("Export size checks passed");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
