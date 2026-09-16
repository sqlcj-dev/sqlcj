package dev.sqlcj.generator;

import dev.sqlcj.analysis.QueryColumn;
import dev.sqlcj.analysis.QueryModel;
import dev.sqlcj.analysis.QueryParameter;

import javax.lang.model.SourceVersion;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves the Java identifiers of one generated query class.
 *
 * <p>SQL names that are already valid, non-reserved Java identifiers keep their
 * spelling. Every other name is normalized deterministically, and names that
 * collide inside the generated parameter list or result record are
 * disambiguated in their existing SQL order.
 */
final class JavaNames {

    private static final SourceVersion SOURCE_VERSION = SourceVersion.RELEASE_21;

    /**
     * Simple type names referenced by generated source, plus the Java 21
     * identifiers that cannot name a type.
     */
    private static final Set<String> RESERVED_CLASS_NAMES = Set.of(
        "QueryExecutor",
        "RowMapper",
        "List",
        "BigDecimal",
        "LocalDate",
        "LocalDateTime",
        "Boolean",
        "Integer",
        "Long",
        "Short",
        "String",
        "permits",
        "record",
        "sealed",
        "var",
        "yield"
    );

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

    /** Generator-owned names referenced by the generated method body. */
    private static final Set<String> RESERVED_PARAMETER_NAMES = Set.of(
        "executor",
        "ROW_MAPPER"
    );

    private final String className;
    private final String methodName;
    private final List<String> parameterNames;
    private final List<String> componentNames;

    private JavaNames(
        String className,
        String methodName,
        List<String> parameterNames,
        List<String> componentNames
    ) {
        this.className = className;
        this.methodName = methodName;
        this.parameterNames = parameterNames;
        this.componentNames = componentNames;
    }

    static JavaNames of(QueryModel query) {
        String className = className(query.name());

        return new JavaNames(
            className,
            methodName(className),
            resolveNames(
                query.parameters().stream()
                    .map(QueryParameter::name)
                    .toList(),
                RESERVED_PARAMETER_NAMES
            ),
            resolveNames(
                query.columns().stream()
                    .map(QueryColumn::name)
                    .toList(),
                RESERVED_MEMBER_NAMES
            )
        );
    }

    String className() {
        return className;
    }

    String resultTypeName() {
        return className + "Result";
    }

    String methodName() {
        return methodName;
    }

    List<String> parameterNames() {
        return parameterNames;
    }

    List<String> componentNames() {
        return componentNames;
    }

    private static String className(String queryName) {
        String name = normalize(queryName);

        while (RESERVED_CLASS_NAMES.contains(name)) {
            name += "_";
        }

        return name;
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
