package io.github.mpdairy.monopaint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Only Device names tablets in code; everything else asks Device about a named fact. Comments
 * may still record where a behavior was observed, and strings may keep stored keys.
 */
final class DeviceChecks {
    private static final Pattern MODEL = Pattern.compile("(?i)nomad|manta");
    private static final Pattern COMMENTS_AND_STRINGS =
            Pattern.compile("//[^\\n]*|/\\*.*?\\*/|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'", Pattern.DOTALL);

    public static void main(String[] args) throws IOException {
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(Paths.get("app/src/main"))) {
            sources = walk.filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".c"))
                    .filter(p -> !p.getFileName().toString().equals("Device.java")).collect(Collectors.toList());
        }
        check(!sources.isEmpty(), "Sources found");
        for (Path source : sources) {
            String code = COMMENTS_AND_STRINGS.matcher(new String(Files.readAllBytes(source), StandardCharsets.UTF_8)).replaceAll(" ");
            check(!MODEL.matcher(code).find(), source + " asks Device about a capability rather than naming a tablet");
        }
        System.out.println("Device checks passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
