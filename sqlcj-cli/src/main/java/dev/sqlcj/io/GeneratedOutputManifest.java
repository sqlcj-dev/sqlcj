package dev.sqlcj.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Records the generated files of one successful run in {@code java.out} and
 * deletes the files a previous run recorded that this run did not produce.
 *
 * <p>Every entry is one generated file path relative to the output directory,
 * with {@code /} between its name elements, so a manifest written on one
 * filesystem reads the same on another.
 */
public final class GeneratedOutputManifest {

    /** The manifest file name, inside the output directory. */
    public static final String FILE_NAME = "sqlcj-manifest.txt";

    /**
     * The entries of the manifest a previous run wrote, or no entry when the
     * output directory holds no manifest.
     */
    public List<String> read(Path outputDirectory) throws IOException {
        Path manifestPath = manifestPath(outputDirectory);

        if (!Files.exists(manifestPath)) {
            return List.of();
        }

        try {
            return List.copyOf(
                Files.readAllLines(manifestPath, StandardCharsets.UTF_8)
            );
        } catch (IOException e) {
            throw new IOException("Cannot read output manifest: " + manifestPath, e);
        }
    }

    /**
     * Deletes every generated file the previous manifest listed that this run
     * did not produce, and nothing else. An entry that does not name a regular
     * file inside the output directory is left alone.
     */
    public void deleteStale(
        Path outputDirectory,
        List<String> previousEntries,
        List<Path> generatedPaths
    ) throws IOException {
        List<String> producedEntries = entries(generatedPaths);

        for (String previousEntry : previousEntries) {
            if (producedEntries.contains(previousEntry)) {
                continue;
            }

            deleteStaleEntry(outputDirectory, previousEntry, generatedPaths);
        }
    }

    /** Records the generated paths of one successful run, one entry per line. */
    public void write(Path outputDirectory, List<Path> generatedPaths) throws IOException {
        Path manifestPath = manifestPath(outputDirectory);

        StringBuilder content = new StringBuilder();

        entries(generatedPaths)
            .stream()
            .sorted()
            .forEach(entry -> content.append(entry).append('\n'));

        try {
            Files.writeString(
                manifestPath,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException e) {
            throw new IOException("Cannot write output manifest: " + manifestPath, e);
        }
    }

    /**
     * Deletes one stale entry, skipping an entry that does not name a regular
     * file inside the output directory and an entry that names a file this run
     * wrote, which a rename differing only by case produces on a
     * case-insensitive filesystem.
     */
    private void deleteStaleEntry(
        Path outputDirectory,
        String entry,
        List<Path> generatedPaths
    ) throws IOException {
        Path relativePath = relativePath(entry);

        if (relativePath == null) {
            return;
        }

        Path file = outputDirectory.resolve(relativePath);

        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }

            if (isGenerated(file, outputDirectory, generatedPaths)) {
                return;
            }

            Files.delete(file);
        } catch (IOException e) {
            throw new IOException("Cannot delete stale generated file: " + file, e);
        }
    }

    /**
     * The relative path of one manifest entry, or {@code null} when the entry
     * is not a normalized relative path that stays inside the output
     * directory.
     */
    private Path relativePath(String entry) {
        if (entry.isEmpty()) {
            return null;
        }

        Path path;

        try {
            path = Path.of(entry);
        } catch (InvalidPathException e) {
            return null;
        }

        if (path.getRoot() != null) {
            return null;
        }

        if (path.getName(0).toString().equals("..")) {
            return null;
        }

        if (!entry.equals(entry(path.normalize()))) {
            return null;
        }

        return path;
    }

    private boolean isGenerated(
        Path file,
        Path outputDirectory,
        List<Path> generatedPaths
    ) throws IOException {
        for (Path generatedPath : generatedPaths) {
            if (Files.isSameFile(file, outputDirectory.resolve(generatedPath))) {
                return true;
            }
        }

        return false;
    }

    private List<String> entries(List<Path> generatedPaths) {
        List<String> entries = new ArrayList<>(generatedPaths.size());

        for (Path generatedPath : generatedPaths) {
            entries.add(entry(generatedPath));
        }

        return entries;
    }

    /** One entry, joining the path's name elements with {@code /}. */
    private String entry(Path path) {
        StringBuilder entry = new StringBuilder();

        for (Path name : path) {
            if (!entry.isEmpty()) {
                entry.append('/');
            }

            entry.append(name);
        }

        return entry.toString();
    }

    private Path manifestPath(Path outputDirectory) {
        return outputDirectory.resolve(FILE_NAME);
    }
}
