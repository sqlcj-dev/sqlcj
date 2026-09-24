package dev.sqlcj.analysis;

import dev.sqlcj.parser.Query;
import dev.sqlcj.parser.QueryType;
import dev.sqlcj.schema.Schema;
import dev.sqlcj.sql.ParsedSql;
import dev.sqlcj.type.DefaultTypeResolver;
import dev.sqlcj.type.TypeResolver;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.ReturningClause;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public final class QueryAnalyzer {

    private static final String ANONYMOUS_PARAMETER_REJECTION = """
        Anonymous '?' parameters are not supported; use an indexed placeholder such as $1""";

    /**
     * Resolves the Java type of parameter occurrence, which decides whether a
     * repeated placeholder index can share one generated parameter.
     */
    private final TypeResolver typeResolver = new DefaultTypeResolver();

    /**
     * One query source and the name it exposes to column references, which is
     * its alias when present and otherwise its table name.
     */
    private record Source(String name, dev.sqlcj.schema.Table table) {
    }

    /** One column reference resolved against the ordered query sources. */
    private record ResolvedColumn(Source source, dev.sqlcj.schema.Column column) {
    }

    public QueryModel analyze(Query query, ParsedSql parsedSql, Schema schema) {
        requireIndexedPlaceholders(parsedSql);

        Statement statement = parsedSql.statement();

        if (statement instanceof Select select) {
            return analyzeSelect(query, parsedSql, select, schema);
        }

        if (statement instanceof Insert insert) {
            return analyzeInsert(query, parsedSql, insert, schema);
        }

        if (statement instanceof Update update) {
            return analyzeUpdate(query, parsedSql, update, schema);
        }

        if (statement instanceof Delete delete) {
            return analyzeDelete(query, parsedSql, delete, schema);
        }

        throw new UnsupportedOperationException(
            statement.getClass().getSimpleName()
        );
    }

    private QueryModel analyzeSelect(Query query, ParsedSql parsedSql, Select select, Schema schema) {
        requireResultQueryType(query);

        PlainSelect plainSelect = select.getPlainSelect();
        Table table = getTable(plainSelect);

        List<Source> sources = resolveSources(plainSelect, table, schema);

        List<QueryColumn> columns = resolveColumns(plainSelect, sources);

        List<QueryParameter> bindingParameters = resolveBindingParameters(plainSelect, sources);

        return toQueryModel(
            query,
            parsedSql,
            table.getUnquotedName(),
            columns,
            bindingParameters,
            resolveSelectRowTable(plainSelect, sources)
        );
    }

    private QueryModel analyzeInsert(Query query, ParsedSql parsedSql, Insert insert, Schema schema) {
        ReturningClause returningClause = insert.getReturningClause();

        requireWriteQueryType(query, returningClause);

        Table table = insert.getTable();
        Source source = toSource(table, schema);

        List<QueryColumn> columns = List.of();
        String rowTable = null;

        if (returningClause != null) {
            requireSupportedReturningInsert(insert);

            columns = resolveReturningColumns(returningClause, source);
            rowTable = resolveReturningRowTable(returningClause, source);
        }

        return toQueryModel(
            query,
            parsedSql,
            table.getUnquotedName(),
            columns,
            resolveInsertParameters(insert, source.table()),
            rowTable
        );
    }

    private QueryModel analyzeUpdate(Query query, ParsedSql parsedSql, Update update, Schema schema) {
        ReturningClause returningClause = update.getReturningClause();

        requireWriteQueryType(query, returningClause);

        Table table = update.getTable();
        Source source = toSource(table, schema);

        List<QueryColumn> columns = List.of();
        String rowTable = null;

        if (returningClause != null) {
            requireSupportedReturningUpdate(update);

            columns = resolveReturningColumns(returningClause, source);
            rowTable = resolveReturningRowTable(returningClause, source);
        }

        List<QueryParameter> bindingParameters = resolveUpdateSetParameters(update, source.table());

        if (update.getWhere() != null) {
            resolveParameters(update.getWhere(), List.of(source), bindingParameters);
        }

        return toQueryModel(
            query,
            parsedSql,
            table.getUnquotedName(),
            columns,
            bindingParameters,
            rowTable
        );
    }

    private QueryModel analyzeDelete(Query query, ParsedSql parsedSql, Delete delete, Schema schema) {
        ReturningClause returningClause = delete.getReturningClause();

        requireWriteQueryType(query, returningClause);

        Table table = delete.getTable();
        Source source = toSource(table, schema);

        List<QueryColumn> columns = List.of();
        String rowTable = null;

        if (returningClause != null) {
            requireSupportedReturningDelete(delete);

            columns = resolveReturningColumns(returningClause, source);
            rowTable = resolveReturningRowTable(returningClause, source);
        }

        List<QueryParameter> bindingParameters = new ArrayList<>();

        if (delete.getWhere() != null) {
            resolveParameters(delete.getWhere(), List.of(source), bindingParameters);
        }

        return toQueryModel(
            query,
            parsedSql,
            table.getUnquotedName(),
            columns,
            bindingParameters,
            rowTable
        );
    }

    private void requireResultQueryType(Query query) {
        if (!producesResult(query)) {
            throw new UnsupportedOperationException(
                "SELECT queries must be declared as :one, :optional, or :many"
            );
        }
    }

    /**
     * Requires the annotation that matches the write shape: a write without
     * {@code RETURNING} reports an affected-row count, and a returning write
     * produces rows like a read.
     */
    private void requireWriteQueryType(Query query, ReturningClause returningClause) {
        if (returningClause == null) {
            if (query.type() != QueryType.EXEC) {
                throw new UnsupportedOperationException(
                    "Write queries without RETURNING must be declared as :exec"
                );
            }

            return;
        }

        if (!producesResult(query)) {
            throw new UnsupportedOperationException(
                "Write queries with RETURNING must be declared as :one, :optional, or :many"
            );
        }
    }

    /** The annotations that declare a row-producing result shape. */
    private boolean producesResult(Query query) {
        return query.type() == QueryType.ONE
            || query.type() == QueryType.OPTIONAL
            || query.type() == QueryType.MANY;
    }

    /**
     * Resolves the returned columns in declared order against the single write
     * target, expanding a bare {@code *} in schema column order.
     */
    private List<QueryColumn> resolveReturningColumns(ReturningClause returningClause, Source source) {
        if (
            returningClause.getKeyword() != ReturningClause.Keyword.RETURNING
                || returningClause.getDataItems() != null
        ) {
            throw new UnsupportedOperationException(
                "Only a RETURNING clause without data items is supported."
            );
        }

        List<QueryColumn> columns = new ArrayList<>();

        for (SelectItem<?> returningItem : returningClause) {
            if (returningItem.getAlias() != null) {
                throw new UnsupportedOperationException(
                    "RETURNING items must not be aliased."
                );
            }

            columns.addAll(
                resolveReturningItem(returningItem.getExpression(), source)
            );
        }

        return List.copyOf(columns);
    }

    /**
     * Reports the table whose complete row a returning write returns, which is
     * the write target when the clause is exactly a bare {@code *}, and
     * {@code null} for a returned column list. The name is the schema's own
     * spelling of the table, so every query returning that row shares one row
     * identity.
     */
    private String resolveReturningRowTable(ReturningClause returningClause, Source source) {
        if (returningClause.size() != 1) {
            return null;
        }

        return returningClause.getFirst().getExpression() instanceof AllColumns
            ? source.table().name()
            : null;
    }

    /**
     * Resolves one returned item, which is either a bare {@code *} or a direct
     * column of the write target. A computed item has no schema type to
     * generate, so it is rejected instead of analyzed.
     */
    private List<QueryColumn> resolveReturningItem(Expression expression, Source source) {
        if (expression instanceof AllTableColumns) {
            throw new UnsupportedOperationException(
                "Only an unqualified RETURNING * is supported."
            );
        }

        if (expression instanceof AllColumns allColumns) {
            if (allColumns.getExceptColumns() != null || allColumns.getReplaceExpressions() != null) {
                throw new UnsupportedOperationException(
                    "RETURNING * must not be modified."
                );
            }

            return resolveAllColumns(source);
        }

        if (expression instanceof net.sf.jsqlparser.schema.Column column) {
            dev.sqlcj.schema.Column schemaColumn = resolveColumn(column, List.of(source)).column();

            return List.of(
                new QueryColumn(
                    schemaColumn.name(),
                    schemaColumn.type(),
                    schemaColumn.nullable()
                )
            );
        }

        throw new UnsupportedOperationException(
            "Unsupported RETURNING expression: "
                + expression.getClass().getSimpleName()
        );
    }

    /**
     * Rejects the {@code INSERT} forms whose returned rows this subset does not
     * model, so a returning insert never silently analyzes a wider statement.
     */
    private void requireSupportedReturningInsert(Insert insert) {
        if (!(insert.getSelect() instanceof Values)) {
            throw new UnsupportedOperationException(
                "RETURNING requires an INSERT with a single VALUES row."
            );
        }

        if (insert.getConflictTarget() != null || insert.getConflictAction() != null) {
            throw new UnsupportedOperationException(
                "INSERT ... ON CONFLICT is not supported."
            );
        }

        requireNoCommonTableExpressions(insert.getWithItemsList());
    }

    private void requireSupportedReturningUpdate(Update update) {
        if (
            update.getFromItem() != null
                || update.getJoins() != null
                || update.getStartJoins() != null
        ) {
            throw new UnsupportedOperationException(
                "UPDATE ... FROM and joined updates are not supported."
            );
        }

        requireNoCommonTableExpressions(update.getWithItemsList());
    }

    private void requireSupportedReturningDelete(Delete delete) {
        boolean using = delete.getUsingList() != null && !delete.getUsingList().isEmpty();

        if (using || delete.getJoins() != null) {
            throw new UnsupportedOperationException(
                "DELETE ... USING and joined deletes are not supported."
            );
        }

        requireNoCommonTableExpressions(delete.getWithItemsList());
    }

    private void requireNoCommonTableExpressions(List<?> withItems) {
        if (withItems != null && !withItems.isEmpty()) {
            throw new UnsupportedOperationException(
                "Common table expressions are not supported in a returning write."
            );
        }
    }

    /**
     * Resolves the {@code INSERT} parameters by pairing the explicit column
     * list with the single values row, which is also their textual order.
     */
    private List<QueryParameter> resolveInsertParameters(Insert insert, dev.sqlcj.schema.Table table) {
        ExpressionList<net.sf.jsqlparser.schema.Column> columns = insert.getColumns();

        if (columns == null || columns.isEmpty()) {
            throw new UnsupportedOperationException("INSERT requires an explicit column list.");
        }

        ParenthesedExpressionList<?> values = resolveInsertValues(insert);

        if (values.size() != columns.size()) {
            throw new UnsupportedOperationException(
                "INSERT column and value counts must match."
            );
        }

        List<QueryParameter> parameters = new ArrayList<>();

        for (int index = 0; index < columns.size(); index++) {
            if (!(values.get(index) instanceof JdbcParameter parameter)) {
                throw new UnsupportedOperationException(
                    "INSERT values must be indexed placeholders."
                );
            }

            addParameter(
                parameter,
                columns.get(index).getUnquotedColumnName(),
                table,
                parameters
            );
        }

        return parameters;
    }

    private ParenthesedExpressionList<?> resolveInsertValues(Insert insert) {
        Values values = insert.getValues();

        if (values == null || !(values.getExpressions() instanceof ParenthesedExpressionList<?> row)) {
            throw new UnsupportedOperationException(
                "INSERT requires a single VALUES row."
            );
        }

        return row;
    }

    /**
     * Resolves the {@code UPDATE} assignment parameters in source order, which
     * precedes any parameter in the {@code WHERE} expression.
     */
    private List<QueryParameter> resolveUpdateSetParameters(Update update, dev.sqlcj.schema.Table table) {
        List<QueryParameter> parameters = new ArrayList<>();

        for (UpdateSet updateSet : update.getUpdateSets()) {
            if (
                updateSet.getColumns().size() != 1
                    || updateSet.getValues().size() != 1
                    || !(updateSet.getValue(0) instanceof JdbcParameter parameter)
            ) {
                throw new UnsupportedOperationException(
                    "UPDATE assignments must set one column to an indexed placeholder."
                );
            }

            addParameter(
                parameter,
                updateSet.getColumn(0).getUnquotedColumnName(),
                table,
                parameters
            );
        }

        return parameters;
    }

    /**
     * Builds the analyzed model from the parameter occurrences collected in
     * textual order, keeping that order for JDBC binding and exposing one
     * logical parameter per placeholder index.
     */
    private QueryModel toQueryModel(
        Query query,
        ParsedSql parsedSql,
        String tableName,
        List<QueryColumn> columns,
        List<QueryParameter> occurrences,
        String rowTable
    ) {
        List<Integer> bindingParameterIndexes = requireAccountedOccurrences(parsedSql, occurrences);

        return new QueryModel(
            query.name(),
            query.type(),
            tableName,
            parsedSql.parameters().executableSql(),
            bindingParameterIndexes,
            columns,
            toParameters(occurrences),
            rowTable
        );
    }

    /**
     * Requires that the occurrences resolved against the schema are exactly the
     * placeholder tokens the SQL parser reported, in the same textual order, so
     * that every executable {@code ?} position has one typed binding source.
     */
    private List<Integer> requireAccountedOccurrences(ParsedSql parsedSql, List<QueryParameter> occurrences) {
        List<Integer> analyzed = occurrences.stream()
            .map(QueryParameter::index)
            .toList();

        List<Integer> placeholders = parsedSql.parameters().indexes();

        if (!analyzed.equals(placeholders)) {
            throw new UnsupportedOperationException(
                "SQL placeholders %s are not the analyzed parameters %s; a placeholder is in an unsupported location"
                    .formatted(placeholders, analyzed)
            );
        }

        return analyzed;
    }

    /**
     * Retains one parameter per placeholder index in logical index order. A
     * repeated index keeps the name and type of its first occurrence and is
     * accepted only when every occurrence resolves to the same Java type.
     */
    private List<QueryParameter> toParameters(List<QueryParameter> occurrences) {
        Map<Integer, QueryParameter> parametersByIndex = new LinkedHashMap<>();

        for (QueryParameter occurrence : occurrences) {
            QueryParameter parameter = parametersByIndex.putIfAbsent(occurrence.index(), occurrence);

            if (parameter != null) {
                requireSameParameterType(parameter, occurrence);
            }
        }

        List<QueryParameter> parameters = parametersByIndex.values().stream()
            .sorted(Comparator.comparingInt(QueryParameter::index))
            .toList();

        requireContiguousIndexes(parameters);

        return parameters;
    }

    private void requireSameParameterType(QueryParameter parameter, QueryParameter occurrence) {
        String type = typeResolver.resolve(parameter.type());
        String occurrenceType = typeResolver.resolve(occurrence.type());

        if (!type.equals(occurrenceType)) {
            throw new UnsupportedOperationException(
                "Placeholder $%d has conflicting types: %s from '%s' and %s from '%s'"
                    .formatted(
                        parameter.index(),
                        type,
                        parameter.name(),
                        occurrenceType,
                        occurrence.name()
                    )
            );
        }
    }

    private void requireContiguousIndexes(List<QueryParameter> parameters) {
        for (int index = 0; index < parameters.size(); index++) {
            if (parameters.get(index).index() != index + 1) {
                throw new UnsupportedOperationException(
                    "Placeholder indexes must start at $1 without gaps, but were %s"
                        .formatted(
                            parameters.stream()
                                .map(QueryParameter::index)
                                .toList()
                        )
                );
            }
        }
    }

    private Table getTable(PlainSelect plainSelect) {
        if (!(plainSelect.getFromItem() instanceof Table table)) {
            throw new UnsupportedOperationException("Only table sources are supported.");
        }

        return table;
    }

    /**
     * Resolves the ordered query sources from the base table followed by every
     * joined table, rejecting an exposed name that repeats.
     */
    private List<Source> resolveSources(PlainSelect plainSelect, Table table, Schema schema) {
        List<Source> sources = new ArrayList<>();

        addSource(sources, table, schema);

        List<Join> joins = plainSelect.getJoins();

        if (joins == null) {
            return List.copyOf(sources);
        }

        for (Join join : joins) {
            Source joined = addSource(sources, requireSupportedJoin(join), schema);

            requireJoinCondition(join, sources, joined);
        }

        return List.copyOf(sources);
    }

    private Source addSource(List<Source> sources, Table table, Schema schema) {
        Source source = toSource(table, schema);

        boolean duplicate = sources.stream()
            .anyMatch(existing -> existing.name().equalsIgnoreCase(source.name()));

        if (duplicate) {
            throw new IllegalArgumentException(
                "Duplicate source name in query: " + source.name()
            );
        }

        sources.add(source);

        return source;
    }

    private Source toSource(Table table, Schema schema) {
        return new Source(
            table.getAlias() == null
                ? table.getUnquotedName()
                : table.getAlias().getUnquotedName(),
            findTable(schema, table.getUnquotedName())
        );
    }

    /**
     * Accepts only a bare {@code JOIN} or explicit {@code INNER JOIN} of one
     * table source. {@link Join#isInnerJoin()} also reports shapes that this
     * subset excludes, so every excluded modifier is rejected explicitly.
     */
    private Table requireSupportedJoin(Join join) {
        boolean supported = join.isInnerJoin()
            && !join.isSimple()
            && !join.isOuter()
            && !join.isLeft()
            && !join.isRight()
            && !join.isFull()
            && !join.isCross()
            && !join.isNatural()
            && !join.isSemi()
            && !join.isApply()
            && !join.isStraight()
            && !join.isGlobal()
            && !join.isWindowJoin()
            && join.getJoinHint() == null
            && join.getUsingColumns().isEmpty();

        if (!supported) {
            throw new UnsupportedOperationException(
                "Only unmodified INNER JOIN clauses are supported."
            );
        }

        if (!(join.getRightItem() instanceof Table table)) {
            throw new UnsupportedOperationException(
                "Only table join sources are supported."
            );
        }

        return table;
    }

    /**
     * Requires one {@code ON} equality between a qualified column of the joined
     * source and a qualified column of a source introduced earlier.
     */
    private void requireJoinCondition(Join join, List<Source> sources, Source joined) {
        Collection<Expression> onExpressions = join.getOnExpressions();

        if (onExpressions.size() != 1 || !(onExpressions.iterator().next() instanceof EqualsTo equality)) {
            throw new UnsupportedOperationException(
                "A join requires exactly one ON equality."
            );
        }

        Source left = resolveJoinConditionSource(equality.getLeftExpression(), sources);
        Source right = resolveJoinConditionSource(equality.getRightExpression(), sources);

        if ((left == joined) == (right == joined)) {
            throw new UnsupportedOperationException(
                "A join ON equality must compare "
                    + joined.name()
                    + " with an earlier source."
            );
        }
    }

    private Source resolveJoinConditionSource(Expression expression, List<Source> sources) {
        if (!(expression instanceof net.sf.jsqlparser.schema.Column column) || qualifier(column) == null) {
            throw new UnsupportedOperationException(
                "A join ON equality requires qualified columns."
            );
        }

        return resolveColumn(column, sources).source();
    }

    /**
     * Resolves the supported parameters in the textual order in which they are
     * encountered, which is the JDBC binding order of the generated {@code ?}
     * positions.
     */
    private List<QueryParameter> resolveBindingParameters(PlainSelect plainSelect, List<Source> sources) {
        if (plainSelect.getWhere() == null) {
            return List.of();
        }

        List<QueryParameter> parameters = new ArrayList<>();

        resolveParameters(
            plainSelect.getWhere(),
            sources,
            parameters
        );

        return parameters;
    }

    private void resolveParameters(
        Expression expression,
        List<Source> sources,
        List<QueryParameter> parameters
    ) {
        if (expression instanceof AndExpression and) {
            resolveParameters(and.getLeftExpression(), sources, parameters);
            resolveParameters(and.getRightExpression(), sources, parameters);
            return;
        }

        if (expression instanceof OrExpression or) {
            resolveParameters(or.getLeftExpression(), sources, parameters);
            resolveParameters(or.getRightExpression(), sources, parameters);
            return;
        }

        if (expression instanceof ParenthesedExpressionList<?> parentheses) {
            for (Expression nestedExpression : parentheses) {
                resolveParameters(
                    nestedExpression,
                    sources,
                    parameters
                );
            }
            return;
        }

        if (expression instanceof InExpression in) {
            resolveInExpression(in, sources, parameters);
            return;
        }

        if (expression instanceof ComparisonOperator comparison) {
            resolveParameterComparison(
                comparison.getLeftExpression(),
                comparison.getRightExpression(),
                sources,
                parameters
            );
        }
    }

    private void resolveInExpression(InExpression in, List<Source> sources, List<QueryParameter> parameters) {
        if (!(in.getLeftExpression() instanceof net.sf.jsqlparser.schema.Column column)) {
            return;
        }

        dev.sqlcj.schema.Column schemaColumn = resolveColumn(column, sources).column();

        Expression rightExpression = in.getRightExpression();

        if (rightExpression instanceof ExpressionList<?> expressionList) {
            for (Expression expression : expressionList) {
                requireIndexedParameter(expression);

                if (expression instanceof JdbcParameter parameter) {
                    addParameter(parameter, schemaColumn, parameters);
                }
            }

            return;
        }

        resolveInExpression(
            rightExpression,
            schemaColumn,
            sources,
            parameters
        );
    }

    private void resolveInExpression(
        Expression expression,
        dev.sqlcj.schema.Column schemaColumn,
        List<Source> sources,
        List<QueryParameter> parameters
    ) {
        requireIndexedParameter(expression);

        if (expression instanceof JdbcParameter parameter) {
            addParameter(parameter, schemaColumn, parameters);
            return;
        }

        if (expression instanceof AndExpression and) {
            resolveInExpression(
                and.getLeftExpression(),
                schemaColumn,
                sources,
                parameters
            );

            resolveInExpression(
                and.getRightExpression(),
                schemaColumn,
                sources,
                parameters
            );

            return;
        }

        if (expression instanceof OrExpression or) {
            resolveInExpression(
                or.getLeftExpression(),
                schemaColumn,
                sources,
                parameters
            );

            resolveInExpression(
                or.getRightExpression(),
                schemaColumn,
                sources,
                parameters
            );

            return;
        }

        if (expression instanceof ParenthesedExpressionList<?> expressionList) {
            for (Expression nestedExpression : expressionList) {
                requireIndexedParameter(nestedExpression);

                if (nestedExpression instanceof JdbcParameter parameter) {
                    addParameter(parameter, schemaColumn, parameters);
                } else {
                    resolveParameters(
                        nestedExpression,
                        sources,
                        parameters
                    );
                }
            }
        }
    }

    private void resolveParameterComparison(
        Expression left,
        Expression right,
        List<Source> sources,
        List<QueryParameter> parameters
    ) {
        requireIndexedParameter(left);
        requireIndexedParameter(right);

        if (left instanceof net.sf.jsqlparser.schema.Column column && right instanceof JdbcParameter parameter) {
            addParameter(
                parameter,
                resolveColumn(column, sources).column(),
                parameters
            );
            return;
        }

        if (left instanceof JdbcParameter parameter && right instanceof net.sf.jsqlparser.schema.Column column) {
            addParameter(
                parameter,
                resolveColumn(column, sources).column(),
                parameters
            );
        }
    }

    private void addParameter(
        JdbcParameter parameter,
        String columnName,
        dev.sqlcj.schema.Table table,
        List<QueryParameter> parameters
    ) {
        addParameter(
            parameter,
            findColumn(table, columnName),
            parameters
        );
    }

    private void addParameter(
        JdbcParameter parameter,
        dev.sqlcj.schema.Column column,
        List<QueryParameter> parameters
    ) {
        if (!parameter.isUseFixedIndex()) {
            throw new UnsupportedOperationException(ANONYMOUS_PARAMETER_REJECTION);
        }

        parameters.add(
            new QueryParameter(
                parameter.getIndex(),
                column.name(),
                column.type()
            )
        );
    }

    /**
     * Rejects an anonymous placeholder reported anywhere in the SQL source,
     * including a clause this analyzer does not traverse, so that an accepted
     * query never keeps an unbound placeholder in its executable SQL.
     */
    private void requireIndexedPlaceholders(ParsedSql parsedSql) {
        if (parsedSql.parameters().hasAnonymousParameter()) {
            throw new UnsupportedOperationException(ANONYMOUS_PARAMETER_REJECTION);
        }
    }

    /**
     * Rejects a named placeholder where an indexed placeholder is supported, so
     * that an accepted query never keeps an unbound placeholder in its
     * executable SQL.
     */
    private void requireIndexedParameter(Expression expression) {
        if (expression instanceof JdbcNamedParameter named) {
            throw new UnsupportedOperationException(
                "Named parameter ':%s' is not supported; use an indexed placeholder such as $1"
                    .formatted(named.getName())
            );
        }
    }

    /**
     * Resolves the selected columns in declared order, expanding {@code *}
     * across the query sources in their declared order and
     * {@code qualifier.*} across one source, each in schema column order.
     */
    private List<QueryColumn> resolveColumns(PlainSelect plainSelect, List<Source> sources) {
        List<QueryColumn> columns = new ArrayList<>();

        for (SelectItem<?> selectItem : plainSelect.getSelectItems()) {
            Expression expression = selectItem.getExpression();

            if (expression instanceof AllTableColumns allTableColumns) {
                columns.addAll(
                    resolveAllColumns(
                        findSource(
                            sources,
                            allTableColumns.getTable().getUnquotedName()
                        )
                    )
                );

                continue;
            }

            if (expression instanceof AllColumns) {
                for (Source source : sources) {
                    columns.addAll(resolveAllColumns(source));
                }

                continue;
            }

            if (expression instanceof net.sf.jsqlparser.schema.Column column) {
                dev.sqlcj.schema.Column schemaColumn = resolveColumn(column, sources).column();

                columns.add(
                    new QueryColumn(
                        selectedColumnName(selectItem.getAlias(), schemaColumn),
                        schemaColumn.type(),
                        schemaColumn.nullable()
                    )
                );

                continue;
            }

            throw new UnsupportedOperationException(
                "Unsupported SELECT expression: "
                    + expression.getClass().getSimpleName()
            );
        }

        return columns;
    }

    /**
     * Reports the table whose complete row a read returns, which is the single
     * query source when the projection is exactly {@code *} or
     * {@code qualifier.*}, and {@code null} for every other projection. The
     * name is the schema's own spelling of the table, so a query-side alias or
     * spelling never splits one row identity.
     */
    private String resolveSelectRowTable(PlainSelect plainSelect, List<Source> sources) {
        List<SelectItem<?>> selectItems = plainSelect.getSelectItems();

        if (sources.size() != 1 || selectItems.size() != 1) {
            return null;
        }

        return selectItems.getFirst().getExpression() instanceof AllColumns
            ? sources.getFirst().table().name()
            : null;
    }

    /**
     * Names a selected direct column after its explicit alias when the
     * projection declares one, so the alias reaches Java naming. The column's
     * type and nullability still come from the schema column.
     */
    private String selectedColumnName(Alias alias, dev.sqlcj.schema.Column schemaColumn) {
        return alias == null
            ? schemaColumn.name()
            : alias.getUnquotedName();
    }

    /**
     * Resolves one column reference against the ordered query sources. A
     * qualified reference resolves through the exposed source name, and an
     * unqualified reference must be contained by exactly one source.
     */
    private ResolvedColumn resolveColumn(net.sf.jsqlparser.schema.Column column, List<Source> sources) {
        String columnName = column.getUnquotedColumnName();
        String qualifier = qualifier(column);

        if (qualifier != null) {
            Source source = findSource(sources, qualifier);

            return new ResolvedColumn(source, findColumn(source.table(), columnName));
        }

        List<Source> matches = sources.stream()
            .filter(source -> lookupColumn(source.table(), columnName).isPresent())
            .toList();

        if (matches.size() > 1) {
            throw new IllegalArgumentException(
                "Ambiguous column reference '%s' in sources: %s"
                    .formatted(columnName, sourceNames(matches))
            );
        }

        if (matches.isEmpty()) {
            throw new IllegalArgumentException(
                "Column not found in sources %s: %s"
                    .formatted(sourceNames(sources), columnName)
            );
        }

        Source source = matches.getFirst();

        return new ResolvedColumn(source, findColumn(source.table(), columnName));
    }

    private String qualifier(net.sf.jsqlparser.schema.Column column) {
        Table table = column.getTable();

        return table == null || table.getName() == null
            ? null
            : table.getUnquotedName();
    }

    private Source findSource(List<Source> sources, String name) {
        return sources.stream()
            .filter(source -> source.name().equalsIgnoreCase(name))
            .findFirst()
            .orElseThrow(
                () -> new IllegalArgumentException(
                    "Unknown source qualifier in query: " + name
                )
            );
    }

    private String sourceNames(List<Source> sources) {
        return sources.stream()
            .map(Source::name)
            .collect(Collectors.joining(", "));
    }

    private dev.sqlcj.schema.Table findTable(Schema schema, String tableName) {
        return schema.tables().stream()
            .filter(table -> table.name().equalsIgnoreCase(tableName))
            .findFirst()
            .orElseThrow(
                () -> new IllegalArgumentException(
                    "Table not found in schema: " + tableName
                )
            );
    }

    private List<QueryColumn> resolveAllColumns(Source source) {
        return source.table().columns().stream()
            .map(
                column -> new QueryColumn(
                    column.name(),
                    column.type(),
                    column.nullable()
                )
            )
            .toList();
    }

    private dev.sqlcj.schema.Column findColumn(dev.sqlcj.schema.Table table, String columnName) {
        return lookupColumn(table, columnName)
            .orElseThrow(
                () -> new IllegalArgumentException(
                    "Column not found in table "
                        + table.name()
                        + ": "
                        + columnName
                )
            );
    }

    private Optional<dev.sqlcj.schema.Column> lookupColumn(dev.sqlcj.schema.Table table, String columnName) {
        return table.columns().stream()
            .filter(column -> column.name().equalsIgnoreCase(columnName))
            .findFirst();
    }
}
