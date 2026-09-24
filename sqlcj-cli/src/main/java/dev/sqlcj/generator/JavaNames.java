package dev.sqlcj.generator;

import dev.sqlcj.analysis.QueryColumn;
import dev.sqlcj.analysis.QueryGroupModel;
import dev.sqlcj.analysis.QueryModel;
import dev.sqlcj.analysis.QueryParameter;

import javax.lang.model.SourceVersion;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
 * <p>The configured group name is used unchanged as the repository-name prefix,
 * because configuration already requires it to be a Java identifier. SQL names
 * that are already valid, non-reserved Java identifiers keep their spelling.
 * Every other name is normalized deterministically, and names that collide
 * inside the generated parameter list or result record are disambiguated in
 * their existing SQL order.
 *
 * <p>Two queries of one group that would generate the same method are rejected
 * instead of being disambiguated, because a repository method is a name the
 * application calls. Two queries whose nested result types differ only by case
 * are rejected for the same reason: their class files share one path on a
 * case-insensitive filesystem.
 */
final class JavaNames {

    private static final SourceVersion SOURCE_VERSION = SourceVersion.RELEASE_21;

    private static final String REPOSITORY_SUFFIX = "Repository";

    private static final String RESULT_SUFFIX = "Result";

    private static final String ROW_MAPPER_SUFFIX = "RowMapper";

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
    private final List<QueryNames> queries;

    private JavaNames(String repositoryClassName, List<QueryNames> queries) {
        this.repositoryClassName = repositoryClassName;
        this.queries = queries;
    }

    static JavaNames of(QueryGroupModel group) {
        List<String> resultTypeNames = group.queries().stream()
            .map(query -> className(query.name()) + RESULT_SUFFIX)
            .toList();

        List<String> methodNames = group.queries().stream()
            .map(query -> methodName(className(query.name())))
            .toList();

        rejectDuplicateMethodNames(group, methodNames);
        rejectResultTypeNamesDifferingOnlyByCase(group, resultTypeNames);

        Set<String> reservedParameterNames = reservedParameterNames(methodNames);

        List<QueryNames> queries = new ArrayList<>(group.queries().size());

        for (int index = 0; index < group.queries().size(); index++) {
            QueryModel query = group.queries().get(index);
            String methodName = methodNames.get(index);

            queries.add(
                new QueryNames(
                    resultTypeNames.get(index),
                    methodName,
                    methodName + ROW_MAPPER_SUFFIX,
                    resolveNames(
                        query.parameters().stream()
                            .map(QueryParameter::name)
                            .toList(),
                        reservedParameterNames
                    ),
                    resolveNames(
                        query.columns().stream()
                            .map(QueryColumn::name)
                            .toList(),
                        RESERVED_MEMBER_NAMES
                    )
                )
            );
        }

        return new JavaNames(
            group.name() + REPOSITORY_SUFFIX,
            List.copyOf(queries)
        );
    }

    String repositoryClassName() {
        return repositoryClassName;
    }

    List<QueryNames> queries() {
        return queries;
    }

    /** The resolved Java identifiers of one generated repository method. */
    record QueryNames(
        String resultTypeName,
        String methodName,
        String rowMapperName,
        List<String> parameterNames,
        List<String> componentNames
    ) {
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
    private static Set<String> reservedParameterNames(List<String> methodNames) {
        Set<String> reserved = new LinkedHashSet<>();

        reserved.add(EXECUTOR_NAME);

        methodNames.stream()
            .map(methodName -> methodName + ROW_MAPPER_SUFFIX)
            .forEach(reserved::add);

        return reserved;
    }

    private static String className(String queryName) {
        return normalize(queryName);
    }

    private static String methodName(String className) {
        String name = decapitalize(className);

        while (isKeyword(name) || RESERVED_MEMBER_NAMES.contains(name)) {
            name += "_";
        }

        return name;
    }

    private static String decapitalize(String className) {
        int codePoint = className.codePointAt(0);

        return new StringBuilder()
            .appendCodePoint(Character.toLowerCase(codePoint))
            .append(className.substring(Character.charCount(codePoint)))
            .toString();
    }

    /**
     * Resolves one generated namespace, keeping the declared order and using
     * stable {@code name1}, {@code name2}, ... suffixes for names that repeat
     * or are reserved.
     */
    private static List<String> resolveNames(List<String> sqlNames, Set<String> reserved) {
        List<String> bases = sqlNames.stream()
            .map(JavaNames::normalize)
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
     * Turns a SQL name into a valid Java identifier, preserving names that are
     * already valid and non-reserved.
     */
    private static String normalize(String sqlName) {
        if (SourceVersion.isIdentifier(sqlName) && !isKeyword(sqlName)) {
            return sqlName;
        }

        StringBuilder builder = new StringBuilder();
        boolean invalidRun = false;
        int index = 0;

        while (index < sqlName.length()) {
            int codePoint = sqlName.codePointAt(index);
            index += Character.charCount(codePoint);

            if (Character.isJavaIdentifierPart(codePoint)) {
                if (invalidRun) {
                    builder.append('_');
                    invalidRun = false;
                }

                builder.appendCodePoint(codePoint);
                continue;
            }

            invalidRun = true;
        }

        if (invalidRun) {
            builder.append('_');
        }

        if (builder.isEmpty() || !Character.isJavaIdentifierStart(builder.codePointAt(0))) {
            builder.insert(0, '_');
        }

        String name = builder.toString();

        return isKeyword(name) ? name + "_" : name;
    }

    private static boolean isKeyword(String name) {
        return SourceVersion.isKeyword(name, SOURCE_VERSION);
    }
}
