package io.github.mpdairy.monopaint;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;

/** Relative paths within a drawing library folder (see {@link DrawingStorage}). */
final class DrawingFiles {
    static final String EXTENSION = ".mpaint";
    /** Drawings were .tsm files before 0.96; {@link #upgradeLegacyNames} renames them. */
    static final String LEGACY_EXTENSION = ".tsm";
    private final File root;

    DrawingFiles(File root) { this.root = root; }

    static boolean validName(String name) {
        return name != null && name.length() > 0 && name.length() <= 64
                && name.equals(name.trim()) && name.matches("[\\p{L}\\p{N} _-]+")
                && !name.equals("_recovery");
    }

    static boolean validPath(String path) {
        if (path == null || path.isEmpty() || path.length() > 1024) return false;
        for (String part : path.split("/", -1)) if (!validName(part)) return false;
        return true;
    }

    static String child(String folder, String name) { return folder.isEmpty() ? name : folder + "/" + name; }
    static String parent(String path) { int slash = path.lastIndexOf('/'); return slash < 0 ? "" : path.substring(0, slash); }
    static String name(String path) { return path.substring(path.lastIndexOf('/') + 1); }

    private File contained(File file) throws IOException {
        if (!file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator))
            throw new IOException("Invalid painting location");
        return file;
    }

    File folder(String path) throws IOException {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create painting folder");
        if (path.isEmpty()) return root;
        if (!validPath(path)) throw new IOException("Invalid folder name");
        File folder = contained(new File(root, path));
        if (!folder.isDirectory()) throw new IOException("Folder no longer exists");
        return folder;
    }

    File drawing(String path) throws IOException {
        if (!validPath(path)) throw new IOException("Invalid painting name");
        return contained(new File(folder(parent(path)), name(path) + EXTENSION));
    }

    File recovery() throws IOException { return new File(folder(""), "_recovery" + EXTENSION); }

    void createFolder(String parent, String name) throws IOException {
        if (!validName(name) || !validPath(child(parent, name))) throw new IOException("Invalid folder name");
        if (!contained(new File(folder(parent), name)).mkdir())
            throw new IOException("Folder already exists or could not be created");
    }

    Entry[] list(String path) throws IOException {
        File[] files = folder(path).listFiles();
        if (files == null) throw new IOException("Could not read painting folder");
        ArrayList<Entry> entries = new ArrayList<>();
        for (File file : files) {
            String name = file.getName();
            if (file.isDirectory() && validName(name)) {
                contained(file);
                entries.add(new Entry(name, true));
            } else if (file.isFile() && name.endsWith(EXTENSION) && !name.equals("_recovery" + EXTENSION)) {
                String title = name.substring(0, name.length() - EXTENSION.length());
                if (validName(title)) { contained(file); entries.add(new Entry(title, false)); }
            }
        }
        entries.sort(Comparator.comparing((Entry entry) -> !entry.folder)
                .thenComparing(entry -> entry.name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(entry -> entry.name));
        return entries.toArray(new Entry[0]);
    }

    /** Named drawings in the whole library, not counting the recovery file. */
    int drawingCount() throws IOException { return root.isDirectory() ? drawingCount("") : 0; }
    private int drawingCount(String path) throws IOException {
        int count = 0;
        for (Entry entry : list(path)) count += entry.folder ? drawingCount(child(path, entry.name)) : 1;
        return count;
    }

    /**
     * Renames .tsm drawings, their atomic-save backups and the recovery file to .mpaint in place.
     * Nothing is copied or deleted: a drawing whose new name is taken gets an unused name, and
     * an older recovery file beside a current one is left as it is.
     */
    void upgradeLegacyNames() throws IOException { if (root.isDirectory()) upgradeLegacyNames(""); }
    private void upgradeLegacyNames(String path) throws IOException {
        File folder = folder(path);
        File[] files = folder.listFiles();
        if (files == null) throw new IOException("Could not read painting folder");
        java.util.LinkedHashSet<String> legacy = new java.util.LinkedHashSet<>();
        for (File file : files) {
            String name = file.getName();
            if (file.isDirectory() && validName(name)) upgradeLegacyNames(child(path, name));
            else if (name.endsWith(LEGACY_EXTENSION) || name.endsWith(LEGACY_EXTENSION + ".bak"))
                legacy.add(name.substring(0, name.lastIndexOf(LEGACY_EXTENSION)));
        }
        for (String name : legacy) {
            boolean recovery = path.isEmpty() && name.equals("_recovery");
            if (!recovery && !validName(name)) continue;
            File destination = recovery ? recovery() : drawing(child(path, name));
            if (exists(destination)) {
                if (recovery) continue;
                destination = drawing(unusedName(path, name));
            }
            rename(new File(folder, name + LEGACY_EXTENSION + ".bak"), new File(destination.getPath() + ".bak"));
            rename(new File(folder, name + LEGACY_EXTENSION), destination);
        }
    }
    private static void rename(File from, File to) throws IOException {
        if (from.exists() && !from.renameTo(to)) throw new IOException("Could not rename " + from.getName());
    }

    boolean sameLibrary(DrawingFiles other) throws IOException {
        return root.getCanonicalPath().equals(other.root.getCanonicalPath());
    }

    /** A drawing name in {@code folder} that nothing uses yet: {@code base}, then "base 2", "base 3"… */
    String unusedName(String folder, String base) throws IOException {
        for (int n = 1; ; n++) {
            String suffix = n == 1 ? "" : " " + n;
            String path = child(folder, base.substring(0, Math.min(base.length(), 64 - suffix.length())).trim() + suffix);
            if (!exists(drawing(path))) return path;
        }
    }

    /** Whether a drawing file is present, counting an atomic save's backup left by an interrupted write. */
    static boolean exists(File file) {
        return file.exists() || new File(file.getPath() + ".bak").exists();
    }

    static final class Entry {
        final String name;
        final boolean folder;
        Entry(String name, boolean folder) { this.name = name; this.folder = folder; }
    }
}
