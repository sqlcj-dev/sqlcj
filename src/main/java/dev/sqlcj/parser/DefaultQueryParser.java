package dev.sqlcj.parser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class DefaultQueryParser implements QueryParser {

    private static final String HEADER_PREFIX = "-- name:";

    @Override
    public List<Query> parse(String source) {
        List<Query> queries = new ArrayList<>();

        String currentName = null;
        QueryType currentType = null;
        StringBuilder builder = new StringBuilder();

        for (String line : source.lines().toList()) {

            if (line.startsWith(HEADER_PREFIX)) {

                if (currentName != null) {
                    queries.add(buildQuery(builder, currentName, currentType));
                }

                QueryHeader header = parseHeader(line);

                currentName = header.name();
                currentType = header.type();

                builder.setLength(0);
                continue;
            }

            if (currentName != null) {
                builder.append(line)
                        .append(System.lineSeparator());
            }
        }

        if (currentName != null) {
            queries.add(buildQuery(builder, currentName, currentType));
        }

        Set<String> names = new HashSet<>();
        for (Query query : queries) {
            if (!names.add(query.name())) {
                throw new IllegalArgumentException(
                        "Duplicate query: " + query.name()
                );
            }
        }

        return queries;
    }

    private Query buildQuery(StringBuilder builder, String currentName, QueryType currentType) {
        String sql = builder.toString().trim();

        if (sql.isBlank()) {
            throw new IllegalArgumentException(
                    "Query '" + currentName + "' has no SQL"
            );
        }

        return new Query(
                currentName,
                currentType,
                sql
        );
    }

    private QueryHeader parseHeader(String line) {
        String header = line.substring(HEADER_PREFIX.length()).trim();
        String[] parts = header.split("\\s+");

        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    "Invalid query header: " + line
            );
        }

        String name = parts[0];
        QueryType type = QueryType.from(parts[1]);
        return new QueryHeader(name, type);
    }
}
