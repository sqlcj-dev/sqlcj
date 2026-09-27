package dev.sqlcj.compiler;

import dev.sqlcj.config.Config;
import dev.sqlcj.config.SqlConfig;
import dev.sqlcj.io.DefaultFileLoader;
import dev.sqlcj.io.FileLoader;
import dev.sqlcj.io.FileSystemReason;
import dev.sqlcj.parser.DefaultQueryParser;
import dev.sqlcj.parser.Query;
import dev.sqlcj.parser.QueryParser;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class DefaultSourceLoader implements SourceLoader {

    private static final String SQL_SUFFIX = ".sql";

    /** A Flyway versioned migration file, such as {@code V1_1__add_index.sql}. */
    private static final Pattern VERSIONED = Pattern.compile("V(\\d+([._]\\d+)*)__.*\\.sql");

    /** A Flyway undo migration file, such as {@code U1_1__add_index.sql}. */
    private static final Pattern UNDO = Pattern.compile("U(\\d+([._]\\d+)*)__.*\\.sql");

    /** One part of a migration version, separated by {@code .} or {@code _}. */
    private static final Pattern VERSION_SEPARATOR = Pattern.compile("[._]");

    private final FileLoader fileLoader = new DefaultFileLoader();
    private final QueryParser queryParser = new DefaultQueryParser();

    /**
     * Loads every configured entry as its own query group. A query name is
     * scoped to the group that declares it, so two groups may name the same
     * query.
     */
    @Override
    public List<Source> load(Config config) {
        List<Source> sources = new ArrayList<>();

        for (SqlConfig sqlConfig : config.sql()) {
            Path queriesPath = Path.of(sqlConfig.queries());

            List<SchemaFile> schemaFiles = loadSchema(sqlConfig.schema());
            List<Query> queries = parse(read(queriesPath, "queries"), queriesPath);

            sources.add(
                new Source(
                    sqlConfig.name(),
                    schemaFiles,
                    queriesPath,
                    queries
                )
            );
        }

        return sources;
    }

    /**
     * Loads the schema files of one entry in the order they apply: the
     * configured paths in declared order, each directory expanded in place into
     * its own migration files.
     */
    private List<SchemaFile> loadSchema(List<String> configuredPaths) {
        List<SchemaFile> schemaFiles = new ArrayList<>();

        for (String configuredPath : configuredPaths) {
            Path path = Path.of(configuredPath);

            if (Files.isDirectory(path)) {
                for (Path file : migrationFiles(path)) {
                    schemaFiles.add(new SchemaFile(file, read(file, "schema")));
                }
            } else {
                schemaFiles.add(new SchemaFile(path, read(path, "schema")));
            }
        }

        return List.copyOf(schemaFiles);
    }

    /**
     * The {@code .sql} files one directory contributes, in Flyway migration
     * order: versioned files by version, then every other file by name. The
     * order never depends on how the filesystem lists the directory.
     */
    private List<Path> migrationFiles(Path directory) {
        List<Path> candidates = list(directory).stream()
            .filter(Files::isRegularFile)
            .filter(path -> fileName(path).endsWith(SQL_SUFFIX))
            .filter(path -> !UNDO.matcher(fileName(path)).matches())
            .sorted(Comparator.comparing(this::fileName))
            .toList();

        if (candidates.isEmpty()) {
            throw new CompilationException(
                "Invalid schema source %s: directory contains no .sql files"
                    .formatted(directory)
            );
        }

        List<Path> versioned = candidates.stream()
            .filter(path -> version(path) != null)
            .sorted(Comparator.comparing(this::version, this::compareVersions))
            .toList();

        checkDuplicateVersions(directory, versioned);

        List<Path> files = new ArrayList<>(versioned);

        candidates.stream()
            .filter(path -> version(path) == null)
            .forEach(files::add);

        return files;
    }

    private List<Path> list(Path directory) {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.toList();
        } catch (IOException e) {
            throw new CompilationException(
                "Cannot read schema source: %s: %s"
                    .formatted(directory, FileSystemReason.of(e)),
                e
            );
        }
    }

    /** The declared version of a versioned migration file, or {@code null}. */
    private String version(Path path) {
        Matcher matcher = VERSIONED.matcher(fileName(path));

        return matcher.matches() ? matcher.group(1) : null;
    }

    /**
     * Compares two migration versions part by part as numbers, counting a
     * missing trailing part as zero, so that {@code 1} precedes {@code 1.1} and
     * {@code 2} precedes {@code 10}.
     */
    private int compareVersions(String left, String right) {
        String[] leftParts = VERSION_SEPARATOR.split(left);
        String[] rightParts = VERSION_SEPARATOR.split(right);

        for (int i = 0; i < Math.max(leftParts.length, rightParts.length); i++) {
            int comparison = part(leftParts, i).compareTo(part(rightParts, i));

            if (comparison != 0) {
                return comparison;
            }
        }

        return 0;
    }

    private BigInteger part(String[] parts, int index) {
        return index < parts.length ? new BigInteger(parts[index]) : BigInteger.ZERO;
    }

    /**
     * Rejects two versioned files of one directory that declare the same
     * version, because neither order of the two is the applied order.
     */
    private void checkDuplicateVersions(Path directory, List<Path> versioned) {
        for (int i = 1; i < versioned.size(); i++) {
            Path previous = versioned.get(i - 1);
            Path current = versioned.get(i);

            if (compareVersions(version(previous), version(current)) == 0) {
                throw new CompilationException(
                    "Invalid schema source %s: duplicate migration version in %s and %s"
                        .formatted(directory, fileName(previous), fileName(current))
                );
            }
        }
    }

    private String fileName(Path path) {
        return path.getFileName().toString();
    }

    private String read(Path path, String kind) {
        try {
            return fileLoader.read(path);
        } catch (IOException e) {
            throw new CompilationException(
                "Cannot read %s source: %s: %s"
                    .formatted(kind, path, FileSystemReason.of(e)),
                e
            );
        }
    }

    private List<Query> parse(String querySource, Path queriesPath) {
        try {
            return queryParser.parse(querySource);
        } catch (IllegalArgumentException e) {
            throw new CompilationException(
                "Invalid query source %s: %s".formatted(queriesPath, e.getMessage()),
                e
            );
        }
    }
}
