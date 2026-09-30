package io.github.mpdairy.monopaint;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;

/** Relative paths within the private drawing library. */
final class DrawingFiles {
    private final File root;

    DrawingFiles(File files) { root = new File(files, "drawings"); }

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
            throw new IOException("Invalid drawing location");
        return file;
    }

    File folder(String path) throws IOException {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create drawing folder");
        if (path.isEmpty()) return root;
        if (!validPath(path)) throw new IOException("Invalid folder name");
        File folder = contained(new File(root, path));
        if (!folder.isDirectory()) throw new IOException("Folder no longer exists");
        return folder;
    }

    File drawing(String path) throws IOException {
        if (!validPath(path)) throw new IOException("Invalid drawing name");
        return contained(new File(folder(parent(path)), name(path) + ".tsm"));
    }

    File recovery() throws IOException { return new File(folder(""), "_recovery.tsm"); }

    void createFolder(String parent, String name) throws IOException {
        if (!validName(name) || !validPath(child(parent, name))) throw new IOException("Invalid folder name");
        if (!contained(new File(folder(parent), name)).mkdir())
            throw new IOException("Folder already exists or could not be created");
    }

    Entry[] list(String path) throws IOException {
        File[] files = folder(path).listFiles();
        if (files == null) throw new IOException("Could not read drawing folder");
        ArrayList<Entry> entries = new ArrayList<>();
        for (File file : files) {
            String name = file.getName();
            if (file.isDirectory() && validName(name)) {
                contained(file);
                entries.add(new Entry(name, true));
            } else if (file.isFile() && name.endsWith(".tsm") && !name.equals("_recovery.tsm")) {
                String title = name.substring(0, name.length() - 4);
                if (validName(title)) { contained(file); entries.add(new Entry(title, false)); }
            }
        }
        entries.sort(Comparator.comparing((Entry entry) -> !entry.folder)
                .thenComparing(entry -> entry.name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(entry -> entry.name));
        return entries.toArray(new Entry[0]);
    }

    static final class Entry {
        final String name;
        final boolean folder;
        Entry(String name, boolean folder) { this.name = name; this.folder = folder; }
    }
}
