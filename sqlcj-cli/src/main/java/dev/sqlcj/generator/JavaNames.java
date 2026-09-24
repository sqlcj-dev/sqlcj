package dev.sqlcj.generator;

import dev.sqlcj.analysis.QueryColumn;
import dev.sqlcj.analysis.QueryGroupModel;
import dev.sqlcj.analysis.QueryModel;
import dev.sqlcj.analysis.QueryParameter;
import dev.sqlcj.parser.QueryType;

import javax.lang.model.SourceVersion;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves the Java identifiers of one generated repository.
 *
 * <p>Every generated name is derived from its SQL spelling — the configured
 * group name, a query name, a column name or projection alias, or a parameter's
 * column name — with one deterministic, locale-independent rule set: the SQL
 * name is split into words at every character that is not a letter or a digit,
 * a word written without a lower-case letter is lower-cased so that {@code ID}
 * becomes {@code Id}, and the words are joined as upper camel case for a type
 * and lower camel case for a method, record component, or parameter.
 *
 * <p>Names that collide inside the generated parameter list or result record are
 * disambiguated in their existing SQL order.
 *
 * <p>A query that returns one complete table row is named after that table
 * instead of after itself, so every such query of one group shares one nested
 * row record and one row mapper.
 *
 * <p>Two queries of one group that would generate the same method are rejected
 * instead of being disambiguated, because a repository method is a name the
 * application calls. Two queries whose nested result types differ only by case
 * are rejected for the same reason: their class files share one path on a
 * case-insensitive filesystem. Two tables whose row types are equal ignoring
 * case are rejected on the same grounds.
 */
final class JavaNames {

    private static final SourceVersion SOURCE_VERSION = SourceVersion.RELEASE_21;

    private static final String REPOSITORY_SUFFIX = "Repository";

    private static final String RESULT_SUFFIX = "Result";

    private static final String ROW_SUFFIX = "Row";

    private static final String MAPPER_SUFFIX = "Mapper";

    private static final String ROW_MAPPER_SUFFIX = ROW_SUFFIX + MAPPER_SUFFIX;

    /**
     * Inherited {@link Object} method names that a generated method or record
     * component cannot use.
     */
    private static final Set<String> RESERVED_MEMBER_NAMES = Set.of(
        "clone",
        "finalize",
        "getClass",
        "hashCode",
        "notify",
        "notifyAll",
        "toString",
        "wait"
    );

    /** Generator-owned name referenced by every generated method body. */
    private static final String EXECUTOR_NAME = "executor";

    private final String repositoryClassName;
    private final List<RowNames> rows;
    private final List<QueryNames> queries;

    private JavaNames(String repositoryClassName, List<RowNames> rows, List<QueryNames> queries) {
        this.repositoryClassName = repositoryClassName;
        this.rows = rows;
        this.queries = queries;
    }

    static JavaNames of(QueryGroupModel group) {
        List<String> resultTypeNames = group.queries().stream()
            .map(query -> upperCamelCase(query.name(), null) + RESULT_SUFFIX)
            .toList();

        List<String> methodNames = group.queries().stream()
            .map(query -> methodName(query.name()))
            .toList();

        rejectDuplicateMethodNames(group, methodNames);
        rejectResultTypeNamesDifferingOnlyByCase(group, resultTypeNames);

        Map<String, RowNames> rowsByTable = resolveRows(group, methodNames);

        Set<String> reservedParameterNames = reservedParameterNames(
            methodNames,
            rowsByTable.values()
        );

        List<QueryNames> queries = new ArrayList<>(group.queries().size());

        for (int index = 0; index < group.queries().size(); index++) {
            QueryModel query = group.queries().get(index);
            String methodName = methodNames.get(index);
            RowNames row = rowsByTable.get(query.rowTable());

            queries.add(
                new QueryNames(
                    row == null ? resultTypeNames.get(index) : row.typeName(),
                    methodName,
                    row == null ? methodName + ROW_MAPPER_SUFFIX : row.mapperName(),
                    resolveNames(
                        query.parameters().stream()
                            .map(QueryParameter::name)
                            .toList(),
                        reservedParameterNames,
                        query.name()
                    ),
                    resolveNames(
                        query.columns().stream()
                            .map(QueryColumn::name)
                            .toList(),
                        RESERVED_MEMBER_NAMES,
                        query.name()
                    )
                )
            );
        }

        return new JavaNames(
            upperCamelCase(group.name(), null) + REPOSITORY_SUFFIX,
            List.copyOf(rowsByTable.values()),
            List.copyOf(queries)
        );
    }

    String repositoryClassName() {
        return repositoryClassName;
    }

    /**
     * The shared row records of this repository, in the order the queries first
     * use them.
     */
    List<RowNames> rows() {
        return rows;
    }

    List<QueryNames> queries() {
        return queries;
    }

    /**
     * The resolved Java identifiers of one generated repository method. A query
     * that returns one complete table row names the repository's shared row
     * record and row mapper instead of a record of its own.
     */
    record QueryNames(
        String resultTypeName,
        String methodName,
        String rowMapperName,
        List<String> parameterNames,
        List<String> componentNames
    ) {
    }

    /**
     * The resolved Java identifiers of one shared row record.
     *
     * @param queryIndex the group-relative index of the query that first
     *                   returned this row, whose analyzed columns are the row's
     *                   columns
     */
    record RowNames(
        String typeName,
        String mapperName,
        List<String> componentNames,
        int queryIndex
    ) {
    }

    /**
     * Resolves one row record per table whose complete row the group returns,
     * keyed by the schema table name and ordered by first use.
     */
    private static Map<String, RowNames> resolveRows(QueryGroupModel group, List<String> methodNames) {
        Set<String> queryRowMapperNames = queryRowMapperNames(group, methodNames);
        Set<String> usedRowMapperNames = new HashSet<>();
        Map<String, GeneratedRowType> rowTypesByPortabilityKey = new HashMap<>();
        Map<String, RowNames> rowsByTable = new LinkedHashMap<>();

        for (int index = 0; index < group.queries().size(); index++) {
            QueryModel query = group.queries().get(index);
            String table = query.rowTable();

            if (table == null || rowsByTable.containsKey(table)) {
                continue;
            }

            String typeName = upperCamelCase(table, query.name()) + ROW_SUFFIX;

            rejectRowTypeNameEqualIgnoringCase(rowTypesByPortabilityKey, table, typeName);

            rowsByTable.put(
                table,
                new RowNames(
                    typeName,
                    resolveName(
                        decapitalize(typeName) + MAPPER_SUFFIX,
                        false,
                        queryRowMapperNames,
                        usedRowMapperNames
                    ),
                    resolveNames(
                        query.columns().stream()
                            .map(QueryColumn::name)
                            .toList(),
                        RESERVED_MEMBER_NAMES,
                        query.name()
                    ),
                    index
                )
            );
        }

        return rowsByTable;
    }

    /**
     * Rejects two tables of one group whose row types are equal ignoring case,
     * because the compiled class files of those types are one path on a
     * case-insensitive filesystem and would overwrite each other.
     */
    private static void rejectRowTypeNameEqualIgnoringCase(
        Map<String, GeneratedRowType> rowTypesByPortabilityKey,
        String tableName,
        String typeName
    ) {
        GeneratedRowType previous = rowTypesByPortabilityKey.putIfAbsent(
            typeName.toLowerCase(Locale.ROOT),
            new GeneratedRowType(tableName, typeName)
        );

        if (previous != null) {
            throw new IllegalArgumentException(
                "Tables '%s' and '%s' generate row types that are equal ignoring case: %s and %s"
                    .formatted(
                        previous.tableName(),
                        tableName,
                        previous.typeName(),
                        typeName
                    )
            );
        }
    }

    /** One generated nested row type and the table that generated it. */
    private record GeneratedRowType(String tableName, String typeName) {
    }

    /** The row mapper names of the queries that still generate their own. */
    private static Set<String> queryRowMapperNames(QueryGroupModel group, List<String> methodNames) {
        Set<String> names = new LinkedHashSet<>();

        for (int index = 0; index < group.queries().size(); index++) {
            QueryModel query = group.queries().get(index);

            if (query.rowTable() == null && generatesRowMapper(query)) {
                names.add(methodNames.get(index) + ROW_MAPPER_SUFFIX);
            }
        }

        return names;
    }

    private static boolean generatesRowMapper(QueryModel query) {
        return query.type() == QueryType.ONE || query.type() == QueryType.MANY;
    }

    /**
     * Rejects two queries of one group that generate the same method, naming
     * both queries so the query source can be corrected.
     */
    private static void rejectDuplicateMethodNames(QueryGroupModel group, List<String> methodNames) {
        Map<String, String> queryNamesByMethodName = new HashMap<>();

        for (int index = 0; index < methodNames.size(); index++) {
            String methodName = methodNames.get(index);
            String queryName = group.queries().get(index).name();
            String previous = queryNamesByMethodName.putIfAbsent(methodName, queryName);

            if (previous != null) {
                throw new IllegalArgumentException(
                    "Queries '%s' and '%s' generate the same repository method '%s'"
                        .formatted(previous, queryName, methodName)
                );
            }
        }
    }

    /**
     * Rejects two queries of one group whose nested result types differ only by
     * case, because the compiled class files of those types are one path on a
     * case-insensitive filesystem and would overwrite each other.
     */
    private static void rejectResultTypeNamesDifferingOnlyByCase(
        QueryGroupModel group,
        List<String> resultTypeNames
    ) {
        Map<String, GeneratedResultType> resultTypesByPortabilityKey = new HashMap<>();

        for (int index = 0; index < resultTypeNames.size(); index++) {
            String resultTypeName = resultTypeNames.get(index);
            String queryName = group.queries().get(index).name();

            GeneratedResultType previous = resultTypesByPortabilityKey.putIfAbsent(
                resultTypeName.toLowerCase(Locale.ROOT),
                new GeneratedResultType(queryName, resultTypeName)
            );

            if (previous != null) {
                throw new IllegalArgumentException(
                    "Queries '%s' and '%s' generate result types that differ only by case: %s and %s"
                        .formatted(
                            previous.queryName(),
                            queryName,
                            previous.resultTypeName(),
                            resultTypeName
                        )
                );
            }
        }
    }

    /** One generated nested result type and the query that generated it. */
    private record GeneratedResultType(String queryName, String resultTypeName) {
    }

    /**
     * A method parameter must not shadow a generated name the method body
     * reads, which is the executor field and the repository's row mappers.
     */
    private static Set<String> reservedParameterNames(
        List<String> methodNames,
        Collection<RowNames> rows
    ) {
        Set<String> reserved = new LinkedHashSet<>();

        reserved.add(EXECUTOR_NAME);

        methodNames.stream()
            .map(methodName -> methodName + ROW_MAPPER_SUFFIX)
            .forEach(reserved::add);

        rows.stream()
            .map(RowNames::mapperName)
            .forEach(reserved::add);

        return reserved;
    }

    private static String methodName(String queryName) {
        String name = lowerCamelCase(queryName, null);

        while (isKeyword(name) || RESERVED_MEMBER_NAMES.contains(name)) {
            name += "_";
        }

        return name;
    }

    /**
     * Resolves one generated namespace, keeping the declared order and using
     * stable {@code name1}, {@code name2}, ... suffixes for names that repeat
     * or are reserved.
     */
    private static List<String> resolveNames(
        List<String> sqlNames,
        Set<String> reserved,
        String queryName
    ) {
        List<String> bases = sqlNames.stream()
            .map(sqlName -> memberName(sqlName, queryName))
            .toList();

        Map<String, Long> occurrences = bases.stream()
            .collect(
                Collectors.groupingBy(
                    Function.identity(),
                    Collectors.counting()
                )
            );

        Set<String> used = new HashSet<>();
        List<String> names = new ArrayList<>(bases.size());

        for (String base : bases) {
            names.add(
                resolveName(
                    base,
                    occurrences.get(base) > 1,
                    reserved,
                    used
                )
            );
        }

        return List.copyOf(names);
    }

    private static String resolveName(
        String base,
        boolean repeated,
        Set<String> reserved,
        Set<String> used
    ) {
        if (!repeated && !reserved.contains(base) && !used.contains(base)) {
            used.add(base);
            return base;
        }

        int suffix = 1;
        String candidate = base + suffix;

        while (reserved.contains(candidate) || used.contains(candidate)) {
            suffix++;
            candidate = base + suffix;
        }

        used.add(candidate);

        return candidate;
    }

    /**
     * Names one record component or method parameter after its SQL name,
     * keeping a Java keyword or literal usable by suffixing {@code _}.
     */
    private static String memberName(String sqlName, String queryName) {
        String name = lowerCamelCase(sqlName, queryName);

        return isKeyword(name) ? name + "_" : name;
    }

    /**
     * Joins the words of a SQL name in upper camel case, as in
     * {@code get_author} to {@code GetAuthor} and {@code user_ID} to
     * {@code UserId}.
     */
    private static String upperCamelCase(String sqlName, String queryName) {
        StringBuilder builder = new StringBuilder();

        for (String word : words(sqlName, queryName)) {
            int first = word.codePointAt(0);

            builder
                .appendCodePoint(Character.toUpperCase(first))
                .append(word.substring(Character.charCount(first)));
        }

        return startIdentifier(builder.toString());
    }

    /**
     * Joins the words of a SQL name in lower camel case, as in
     * {@code created_at} to {@code createdAt} and {@code GetAuthor} to
     * {@code getAuthor}.
     */
    private static String lowerCamelCase(String sqlName, String queryName) {
        return decapitalize(upperCamelCase(sqlName, queryName));
    }

    /**
     * Lower-cases the leading upper-case run of an upper-camel-case name. A run
     * of two or more letters that is followed by a lower-case letter keeps its
     * last letter upper-case, because that letter starts the next word:
     * {@code HTTPStatus} becomes {@code httpStatus} while {@code HTTP2Status}
     * becomes {@code http2Status}.
     */
    private static String decapitalize(String name) {
        int end = 0;
        int letters = 0;

        while (end < name.length() && Character.isUpperCase(name.codePointAt(end))) {
            end += Character.charCount(name.codePointAt(end));
            letters++;
        }

        if (letters == 0) {
            return name;
        }

        boolean startsNextWord = letters > 1
            && end < name.length()
            && Character.isLowerCase(name.codePointAt(end));

        int lowerCaseEnd = startsNextWord
            ? name.offsetByCodePoints(end, -1)
            : end;

        return name.substring(0, lowerCaseEnd).toLowerCase(Locale.ROOT)
            + name.substring(lowerCaseEnd);
    }

    /**
     * Splits a SQL name into the words every generated name is built from. A
     * word ends at every character that is not a letter or a digit, and a word
     * written without a lower-case letter is lower-cased so that an acronym
     * such as {@code ID} or {@code URL} becomes one ordinary word.
     */
    private static List<String> words(String sqlName, String queryName) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        int index = 0;

        while (index < sqlName.length()) {
            int codePoint = sqlName.codePointAt(index);
            index += Character.charCount(codePoint);

            if (Character.isLetterOrDigit(codePoint)) {
                word.appendCodePoint(codePoint);
                continue;
            }

            addWord(words, word);
        }

        addWord(words, word);

        if (words.isEmpty()) {
            throw new IllegalArgumentException(
                queryName == null
                    ? "SQL name '%s' has no letter or digit to generate a Java name from"
                        .formatted(sqlName)
                    : "SQL name '%s' of query '%s' has no letter or digit to generate a Java name from"
                        .formatted(sqlName, queryName)
            );
        }

        return words;
    }

    private static void addWord(List<String> words, StringBuilder word) {
        if (word.isEmpty()) {
            return;
        }

        String value = word.toString();

        words.add(
            value.codePoints().anyMatch(Character::isLowerCase)
                ? value
                : value.toLowerCase(Locale.ROOT)
        );

        word.setLength(0);
    }

    /** A name built from a digit-initial SQL name cannot start an identifier. */
    private static String startIdentifier(String name) {
        return Character.isDigit(name.codePointAt(0))
            ? "_" + name
            : name;
    }

    private static boolean isKeyword(String name) {
        return SourceVersion.isKeyword(name, SOURCE_VERSION);
    }
}
