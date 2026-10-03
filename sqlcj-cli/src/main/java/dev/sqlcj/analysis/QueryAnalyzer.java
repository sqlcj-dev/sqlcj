package dev.sqlcj.analysis;

import dev.sqlcj.parser.Query;
import dev.sqlcj.parser.QueryType;
import dev.sqlcj.schema.ColumnType;
import dev.sqlcj.schema.Schema;
import dev.sqlcj.schema.parser.ColumnTypeMapping;
import dev.sqlcj.sql.ParsedSql;
import dev.sqlcj.type.DefaultTypeResolver;
import dev.sqlcj.type.TypeResolver;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.MultiPartName;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.ReturningClause;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.Index;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.ConflictActionType;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.insert.InsertConflictAction;
import net.sf.jsqlparser.statement.insert.InsertConflictTarget;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.Offset;
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
     * The rejection of a placeholder written with a colon or ampersand that is
     * not a supported named placeholder, which is written with the placeholder
     * as the query spells it.
     */
    private static final String NAMED_PLACEHOLDER_REJECTION = """
        Named placeholder '%s' is not supported; write an unquoted name of letters, digits, and \
        underscores directly after ':', such as :userId""";

    private static final String MIXED_PLACEHOLDER_REJECTION = """
        Indexed '$N' and named ':name' placeholders must not be mixed in one query""";

    /** The character that opens a supported named placeholder. */
    private static final String NAME_PREFIX = ":";

    /** The parameter name of a {@code LIMIT} row count placeholder. */
    private static final String LIMIT_PARAMETER_NAME = "limit";

    /** The parameter name of an {@code OFFSET} value placeholder. */
    private static final String OFFSET_PARAMETER_NAME = "offset";

    /**
     * The prefix of the name an indexed placeholder takes where no column of
     * the query names it, which a cast types wherever it appears.
     */
    private static final String INDEXED_PARAMETER_NAME_PREFIX = "param";

    /**
     * The qualifier an {@code ON CONFLICT DO UPDATE} assignment writes to read
     * a column of the row the insert proposed, compared ignoring case as
     * PostgreSQL resolves it.
     */
    private static final String EXCLUDED_QUALIFIER = "EXCLUDED";

    /**
     * Resolves the Java type of parameter occurrence, which decides whether a
     * repeated placeholder index can share one generated parameter.
     */
    private final TypeResolver typeResolver = new DefaultTypeResolver();

    /** Resolves the type a cast states, as a schema column declares its own. */
    private final ColumnTypeMapping columnTypeMapping = new ColumnTypeMapping();

    /**
     * One query source and the name it exposes to column references, which is
     * its alias when present and otherwise its table name. A left-joined
     * source contributes no row when the join finds no match, so every column
     * read from it is nullable regardless of its schema declaration.
     */
    private record Source(String name, dev.sqlcj.schema.Table table, boolean leftJoined) {
    }

    /** One column reference resolved against the ordered query sources. */
    private record ResolvedColumn(Source source, dev.sqlcj.schema.Column column) {
    }

    /**
     * One placeholder occurrence, resolved to the logical parameter it binds
     * and, for a named placeholder, to the name that parameter carries.
     */
    private record Placeholder(int index, String name) {

        /**
         * The name of the parameter this occurrence binds, which a named
         * placeholder states itself and every other placeholder takes from the
         * column or clause it belongs to.
         */
        private String nameOr(String clauseName) {
            return name == null
                ? clauseName
                : name;
        }

        /** The placeholder as the query spells it. */
        private String spelled() {
            return name == null
                ? "$" + index
                : NAME_PREFIX + name;
        }
    }

    /**
     * One placeholder occurrence a cast types, and the type that cast states.
     */
    private record CastPlaceholder(Placeholder placeholder, ColumnTypeMapping.MappedType type) {
    }

    /**
     * The compiled placeholders of one query and the parameter occurrences
     * analyzed against them, collected in the textual order of the executable
     * {@code ?} positions.
     */
    private static final class Placeholders {

        /** The compiled placeholder names in logical parameter order. */
        private final List<String> names;

        /** The schema whose declared enum types a cast may name. */
        private final Schema schema;

        /** Resolves the type a cast states. */
        private final ColumnTypeMapping columnTypeMapping;

        private final List<QueryParameter> occurrences = new ArrayList<>();

        private Placeholders(
            List<String> names,
            Schema schema,
            ColumnTypeMapping columnTypeMapping
        ) {
            this.names = names;
            this.schema = schema;
            this.columnTypeMapping = columnTypeMapping;
        }

        /**
         * Reports the placeholder an expression is, and {@code null} when the
         * expression is absent or binds none. A named placeholder the compiler
         * did not replace is rejected as the query spells it, which keeps every
         * accepted occurrence bound even though such a placeholder is normally
         * already rejected from the compiler's report.
         */
        private Placeholder of(Expression expression) {
            if (expression instanceof JdbcParameter parameter) {
                if (!parameter.isUseFixedIndex()) {
                    throw new UnsupportedOperationException(ANONYMOUS_PARAMETER_REJECTION);
                }

                return new Placeholder(parameter.getIndex(), null);
            }

            if (expression instanceof JdbcNamedParameter named) {
                return new Placeholder(requireCompiledName(named), named.getName());
            }

            return null;
        }

        /**
         * Reports the placeholder a cast types, which is the placeholder the
         * cast states as its direct operand, and {@code null} when the
         * expression is not such a cast. A cast that declares a row type
         * instead of a type name states no column type, so it types no
         * placeholder.
         *
         * <p>The cast states the parameter's type, so a type the schema's
         * column type mapping does not map is rejected here, where the
         * placeholder it would have typed is known.
         */
        private CastPlaceholder ofCast(Expression expression) {
            if (!(expression instanceof CastExpression cast) || cast.getColDataType() == null) {
                return null;
            }

            Placeholder placeholder = of(cast.getLeftExpression());

            if (placeholder == null) {
                return null;
            }

            ColumnTypeMapping.MappedType type = columnTypeMapping.map(
                cast.getColDataType(),
                schema.enums()
            );

            if (!type.mapped()) {
                throw new UnsupportedOperationException(
                    "Placeholder %s has unsupported cast type %s"
                        .formatted(placeholder.spelled(), type.unsupportedType())
                );
            }

            return new CastPlaceholder(placeholder, type);
        }

        private int requireCompiledName(JdbcNamedParameter named) {
            int index = NAME_PREFIX.equals(named.getParameterCharacter())
                ? names.indexOf(named.getName())
                : -1;

            if (index < 0) {
                throw new UnsupportedOperationException(
                    NAMED_PLACEHOLDER_REJECTION.formatted(
                        named.getParameterCharacter() + named.getName()
                    )
                );
            }

            return index + 1;
        }

        private void add(QueryParameter occurrence) {
            occurrences.add(occurrence);
        }

        private List<QueryParameter> occurrences() {
            return occurrences;
        }
    }

    public QueryModel analyze(Query query, ParsedSql parsedSql, Schema schema) {
        requireSupportedPlaceholders(parsedSql);

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

        List<QueryColumn> columns = resolveColumns(plainSelect, sources, schema);

        Placeholders placeholders = toPlaceholders(parsedSql, schema);

        resolveBindingParameters(plainSelect, sources, placeholders);

        return toQueryModel(
            query,
            parsedSql,
            table.getUnquotedName(),
            columns,
            placeholders,
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

        Placeholders placeholders = toPlaceholders(parsedSql, schema);

        resolveInsertParameters(insert, source.table(), placeholders);
        resolveConflictParameters(insert, source.table(), placeholders);

        return toQueryModel(
            query,
            parsedSql,
            table.getUnquotedName(),
            columns,
            placeholders,
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

        Placeholders placeholders = toPlaceholders(parsedSql, schema);

        resolveUpdateSetParameters(update.getUpdateSets(), source.table(), placeholders);

        if (update.getWhere() != null) {
            resolveParameters(update.getWhere(), List.of(source), placeholders);
        }

        return toQueryModel(
            query,
            parsedSql,
            table.getUnquotedName(),
            columns,
            placeholders,
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

        Placeholders placeholders = toPlaceholders(parsedSql, schema);

        if (delete.getWhere() != null) {
            resolveParameters(delete.getWhere(), List.of(source), placeholders);
        }

        return toQueryModel(
            query,
            parsedSql,
            table.getUnquotedName(),
            columns,
            placeholders,
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
                    requireSupportedType(schemaColumn),
                    schemaColumn.nullable(),
                    schemaColumn.enumType(),
                    schemaColumn.array()
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
     * list with the single values row, which is also their textual order. A
     * value that binds no placeholder reaches the database as written, so only
     * its target column must exist.
     */
    private void resolveInsertParameters(
        Insert insert,
        dev.sqlcj.schema.Table table,
        Placeholders placeholders
    ) {
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

        for (int index = 0; index < columns.size(); index++) {
            String columnName = columns.get(index).getUnquotedColumnName();

            resolveColumnValue(
                values.get(index),
                findColumn(table, columnName),
                placeholders
            );
        }
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
     * Resolves the assignment parameters of an {@code UPDATE} or of an
     * {@code ON CONFLICT DO UPDATE} action in source order, which precedes any
     * parameter in the {@code WHERE} expression. An assigned value that binds
     * no placeholder reaches the database as written, so only its target
     * column must exist.
     */
    private void resolveUpdateSetParameters(
        List<UpdateSet> updateSets,
        dev.sqlcj.schema.Table table,
        Placeholders placeholders
    ) {
        for (UpdateSet updateSet : updateSets) {
            if (updateSet.getColumns().size() != 1 || updateSet.getValues().size() != 1) {
                throw new UnsupportedOperationException(
                    "UPDATE assignments must set one column at a time."
                );
            }

            String columnName = updateSet.getColumn(0).getUnquotedColumnName();

            resolveColumnValue(
                updateSet.getValue(0),
                findColumn(table, columnName),
                placeholders
            );
        }
    }

    /**
     * Resolves the conflict clause of an insert, whose placeholders are written
     * after the {@code VALUES} placeholders and therefore bind after them. Only
     * a target of plain column names of the inserted table is modelled;
     * PostgreSQL still decides which unique index that target matches.
     */
    private void resolveConflictParameters(
        Insert insert,
        dev.sqlcj.schema.Table table,
        Placeholders placeholders
    ) {
        InsertConflictTarget target = insert.getConflictTarget();
        InsertConflictAction action = insert.getConflictAction();

        if (target == null && action == null) {
            return;
        }

        resolveConflictTarget(target, table);

        if (action == null || action.getConflictActionType() != ConflictActionType.DO_UPDATE) {
            return;
        }

        if (action.getWhereExpression() != null) {
            throw new UnsupportedOperationException(
                "ON CONFLICT DO UPDATE ... WHERE is not supported."
            );
        }

        List<UpdateSet> updateSets = action.getUpdateSets();

        resolveUpdateSetParameters(updateSets, table, placeholders);
        resolveExcludedColumns(updateSets, table);
    }

    /**
     * Resolves the conflict target against the inserted table. A target this
     * subset does not model — an absent one, a named constraint, or an element
     * that is an expression or carries a collation or an operator class —
     * decides the conflict by something the analyzed model does not describe.
     */
    private void resolveConflictTarget(
        InsertConflictTarget target,
        dev.sqlcj.schema.Table table
    ) {
        boolean columnNames = target != null
            && target.getConstraintName() == null
            && target.getIndexElements().stream().noneMatch(
                element -> element.isExpression()
                    || element.getCollation() != null
                    || element.getOperatorClass() != null
            );

        if (!columnNames) {
            throw new UnsupportedOperationException(
                "ON CONFLICT requires a target of column names, such as ON CONFLICT (id)."
            );
        }

        if (target.getWhereExpression() != null) {
            throw new UnsupportedOperationException(
                "ON CONFLICT target predicates are not supported."
            );
        }

        for (Index.ColumnParams element : target.getIndexElements()) {
            findColumn(table, MultiPartName.unquote(element.getColumnName()));
        }
    }

    /**
     * Resolves the column each {@code DO UPDATE} value that is exactly
     * {@code EXCLUDED.column} reads from the row the insert proposed, which is
     * a column of the inserted table. A value that merely contains such a
     * reference is not analyzed, as no other computed write value is.
     */
    private void resolveExcludedColumns(
        List<UpdateSet> updateSets,
        dev.sqlcj.schema.Table table
    ) {
        for (UpdateSet updateSet : updateSets) {
            if (
                updateSet.getValue(0) instanceof net.sf.jsqlparser.schema.Column column
                    && EXCLUDED_QUALIFIER.equalsIgnoreCase(qualifier(column))
            ) {
                findColumn(table, column.getUnquotedColumnName());
            }
        }
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
        Placeholders placeholders,
        String rowTable
    ) {
        List<QueryParameter> occurrences = placeholders.occurrences();

        List<Integer> bindingParameterIndexes = requireAccountedOccurrences(parsedSql, occurrences);

        return new QueryModel(
            query.name(),
            query.type(),
            tableName,
            parsedSql.parameters().executableSql(),
            bindingParameterIndexes,
            columns,
            toParameters(occurrences, parsedSql.parameters().names()),
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

        List<Integer> compiled = parsedSql.parameters().indexes();
        List<String> names = parsedSql.parameters().names();

        if (!analyzed.equals(compiled)) {
            throw new UnsupportedOperationException(
                "SQL placeholders %s are not the analyzed parameters %s; a placeholder is in an unsupported location"
                    .formatted(
                        describePlaceholders(compiled, names),
                        describePlaceholders(analyzed, names)
                    )
            );
        }

        return analyzed;
    }

    /**
     * Writes a list of logical parameter numbers as the query spells its
     * placeholders, which is {@code :name} for a named query and the number
     * itself for an indexed one.
     */
    private String describePlaceholders(List<Integer> indexes, List<String> names) {
        if (names.isEmpty()) {
            return indexes.toString();
        }

        return indexes.stream()
            .map(index -> describePlaceholder(index, names))
            .collect(Collectors.joining(", ", "[", "]"));
    }

    /** Writes one logical parameter as the query spells its placeholder. */
    private String describePlaceholder(int index, List<String> names) {
        return names.isEmpty()
            ? "$" + index
            : NAME_PREFIX + names.get(index - 1);
    }

    /**
     * Retains one parameter per placeholder index in logical index order. A
     * repeated index keeps the name and type of its first occurrence and is
     * accepted only when every occurrence resolves to the same Java type.
     */
    private List<QueryParameter> toParameters(List<QueryParameter> occurrences, List<String> names) {
        Map<Integer, QueryParameter> parametersByIndex = new LinkedHashMap<>();

        for (QueryParameter occurrence : occurrences) {
            QueryParameter parameter = parametersByIndex.putIfAbsent(occurrence.index(), occurrence);

            if (parameter != null) {
                requireSameParameterType(parameter, occurrence, names);
            }
        }

        List<QueryParameter> parameters = parametersByIndex.values().stream()
            .sorted(Comparator.comparingInt(QueryParameter::index))
            .toList();

        requireContiguousIndexes(parameters);

        return parameters;
    }

    /**
     * Requires two occurrences of one placeholder index to have the same type.
     * An enum occurrence is the same type only as an occurrence of the same
     * enum type, because each enum type generates a Java type of its own; every
     * other occurrence is compared by the Java type it resolves to. An array
     * occurrence is a list of its element's Java type, so it is never the same
     * type as an occurrence of that element.
     */
    private void requireSameParameterType(
        QueryParameter parameter,
        QueryParameter occurrence,
        List<String> names
    ) {
        if (isSameParameterType(parameter, occurrence)) {
            return;
        }

        if (!names.isEmpty()) {
            throw new UnsupportedOperationException(
                "Placeholder %s has conflicting types: %s and %s"
                    .formatted(
                        describePlaceholder(parameter.index(), names),
                        describeParameterType(parameter),
                        describeParameterType(occurrence)
                    )
            );
        }

        throw new UnsupportedOperationException(
            "Placeholder $%d has conflicting types: %s from '%s' and %s from '%s'"
                .formatted(
                    parameter.index(),
                    describeParameterType(parameter),
                    parameter.name(),
                    describeParameterType(occurrence),
                    occurrence.name()
                )
        );
    }

    private boolean isSameParameterType(QueryParameter parameter, QueryParameter occurrence) {
        if (parameter.array() != occurrence.array()) {
            return false;
        }

        if (parameter.type() == ColumnType.ENUM || occurrence.type() == ColumnType.ENUM) {
            return parameter.type() == occurrence.type()
                && parameter.enumType().equalsIgnoreCase(occurrence.enumType());
        }

        return typeResolver.resolve(parameter.type())
            .equals(typeResolver.resolve(occurrence.type()));
    }

    /**
     * Describes a parameter's type for a diagnostic. An enum is described by
     * its PostgreSQL name, because analysis renders no Java name, and an array
     * by the list of its element's description.
     */
    private String describeParameterType(QueryParameter parameter) {
        if (parameter.type() == ColumnType.ENUM) {
            return parameter.array()
                ? parameter.enumType() + "[]"
                : parameter.enumType();
        }

        String javaType = typeResolver.resolve(parameter.type());

        return parameter.array()
            ? "List<" + javaType + ">"
            : javaType;
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

        addSource(sources, table, schema, false);

        List<Join> joins = plainSelect.getJoins();

        if (joins == null) {
            return List.copyOf(sources);
        }

        for (Join join : joins) {
            Source joined = addSource(
                sources,
                requireSupportedJoin(join),
                schema,
                join.isLeft()
            );

            requireJoinCondition(join, sources, joined);
        }

        return List.copyOf(sources);
    }

    private Source addSource(List<Source> sources, Table table, Schema schema, boolean leftJoined) {
        Source source = toSource(table, schema, leftJoined);

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

    /** A base or write source, which always contributes a row of its own. */
    private Source toSource(Table table, Schema schema) {
        return toSource(table, schema, false);
    }

    private Source toSource(Table table, Schema schema, boolean leftJoined) {
        return new Source(
            table.getAlias() == null
                ? table.getUnquotedName()
                : table.getAlias().getUnquotedName(),
            findTable(schema, table.getUnquotedName()),
            leftJoined
        );
    }

    /**
     * Accepts only a bare {@code JOIN} or explicit {@code INNER JOIN}, or a
     * {@code LEFT JOIN} in its bare or {@code LEFT OUTER JOIN} spelling, of one
     * table source. {@link Join#isInnerJoin()} and {@link Join#isLeft()} also
     * report shapes that this subset excludes, so every excluded modifier is
     * rejected explicitly.
     */
    private Table requireSupportedJoin(Join join) {
        if (!isSupportedInnerJoin(join) && !isSupportedLeftJoin(join)) {
            throw new UnsupportedOperationException(
                "Only unmodified INNER JOIN and LEFT JOIN clauses are supported."
            );
        }

        if (!(join.getRightItem() instanceof Table table)) {
            throw new UnsupportedOperationException(
                "Only table join sources are supported."
            );
        }

        return table;
    }

    private boolean isSupportedInnerJoin(Join join) {
        return join.isInnerJoin()
            && !join.isOuter()
            && !join.isLeft()
            && !join.isRight()
            && !join.isFull()
            && !join.isCross()
            && !join.isNatural()
            && hasNoOtherJoinModifier(join);
    }

    /**
     * Reports a {@code LEFT JOIN}, whose {@code LEFT OUTER JOIN} spelling also
     * sets the {@code OUTER} keyword. Every other qualifier, including the bare
     * {@code OUTER JOIN} that sets no side, is excluded.
     */
    private boolean isSupportedLeftJoin(Join join) {
        return join.isLeft()
            && !join.isInner()
            && !join.isRight()
            && !join.isFull()
            && !join.isCross()
            && !join.isNatural()
            && hasNoOtherJoinModifier(join);
    }

    private boolean hasNoOtherJoinModifier(Join join) {
        return !join.isSimple()
            && !join.isSemi()
            && !join.isApply()
            && !join.isStraight()
            && !join.isGlobal()
            && !join.isWindowJoin()
            && join.getJoinHint() == null
            && join.getUsingColumns().isEmpty();
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
    private void resolveBindingParameters(
        PlainSelect plainSelect,
        List<Source> sources,
        Placeholders placeholders
    ) {
        if (plainSelect.getWhere() != null) {
            resolveParameters(
                plainSelect.getWhere(),
                sources,
                placeholders
            );
        }

        resolvePaginationParameters(plainSelect, placeholders);
    }

    /**
     * Resolves the pagination parameters, which follow every {@code WHERE}
     * parameter in textual order. A {@code LIMIT} row count and an
     * {@code OFFSET} value that is a placeholder each becomes an
     * {@code INTEGER} parameter named after its own clause, or after the
     * placeholder when the placeholder is named. The parser stores
     * {@code OFFSET a LIMIT b} exactly like {@code LIMIT b OFFSET a}, so two
     * placeholders are ordered by their source positions.
     */
    private void resolvePaginationParameters(PlainSelect plainSelect, Placeholders placeholders) {
        Expression rowCountValue = resolveLimitRowCount(plainSelect.getLimit());

        Placeholder rowCount = placeholders.of(rowCountValue);

        Offset offset = plainSelect.getOffset();

        Expression offsetExpression = offset == null
            ? null
            : offset.getOffset();

        Placeholder offsetValue = placeholders.of(offsetExpression);

        if (
            rowCount != null
                && offsetValue != null
                && sourcePosition(offsetExpression) < sourcePosition(rowCountValue)
        ) {
            addParameter(offsetValue, OFFSET_PARAMETER_NAME, ColumnType.INTEGER, placeholders);
            addParameter(rowCount, LIMIT_PARAMETER_NAME, ColumnType.INTEGER, placeholders);

            return;
        }

        if (rowCount != null) {
            addParameter(rowCount, LIMIT_PARAMETER_NAME, ColumnType.INTEGER, placeholders);
        }

        if (offsetValue != null) {
            addParameter(offsetValue, OFFSET_PARAMETER_NAME, ColumnType.INTEGER, placeholders);
        }
    }

    /**
     * Reports the analyzed row count of a {@code LIMIT} clause, which is absent
     * when the clause carries an offset of its own, as in the {@code LIMIT a, b}
     * form.
     */
    private Expression resolveLimitRowCount(Limit limit) {
        return limit == null || limit.getOffset() != null
            ? null
            : limit.getRowCount();
    }

    /** The source position of a placeholder token, counted from one. */
    private int sourcePosition(Expression placeholder) {
        return placeholder.getASTNode().jjtGetFirstToken().absoluteBegin;
    }

    private void resolveParameters(
        Expression expression,
        List<Source> sources,
        Placeholders placeholders
    ) {
        if (expression instanceof AndExpression and) {
            resolveParameters(and.getLeftExpression(), sources, placeholders);
            resolveParameters(and.getRightExpression(), sources, placeholders);
            return;
        }

        if (expression instanceof OrExpression or) {
            resolveParameters(or.getLeftExpression(), sources, placeholders);
            resolveParameters(or.getRightExpression(), sources, placeholders);
            return;
        }

        if (expression instanceof ParenthesedExpressionList<?> parentheses) {
            for (Expression nestedExpression : parentheses) {
                resolveParameters(
                    nestedExpression,
                    sources,
                    placeholders
                );
            }
            return;
        }

        if (expression instanceof InExpression in) {
            resolveInExpression(in, sources, placeholders);
            return;
        }

        if (expression instanceof LikeExpression like) {
            resolveLikeExpression(like, sources, placeholders);
            return;
        }

        if (expression instanceof IsNullExpression isNull) {
            resolveIsNullExpression(isNull, sources, placeholders);
            return;
        }

        if (expression instanceof Between between) {
            resolveBetweenExpression(between, sources, placeholders);
            return;
        }

        if (expression instanceof ComparisonOperator comparison) {
            resolveParameterComparison(comparison, sources, placeholders);
            return;
        }

        resolveCastPlaceholders(expression, placeholders);
    }

    private void resolveInExpression(InExpression in, List<Source> sources, Placeholders placeholders) {
        if (!(in.getLeftExpression() instanceof net.sf.jsqlparser.schema.Column column)) {
            resolveCastPlaceholders(in.getLeftExpression(), placeholders);
            resolveCastPlaceholders(in.getRightExpression(), placeholders);

            return;
        }

        dev.sqlcj.schema.Column schemaColumn = resolveColumn(column, sources).column();

        Expression rightExpression = in.getRightExpression();

        if (rightExpression instanceof ExpressionList<?> expressionList) {
            for (Expression expression : expressionList) {
                resolveColumnValue(expression, schemaColumn, placeholders);
            }

            return;
        }

        resolveInExpression(
            rightExpression,
            schemaColumn,
            sources,
            placeholders
        );
    }

    private void resolveInExpression(
        Expression expression,
        dev.sqlcj.schema.Column schemaColumn,
        List<Source> sources,
        Placeholders placeholders
    ) {
        Placeholder placeholder = placeholders.of(expression);

        if (placeholder != null) {
            addParameter(placeholder, schemaColumn, placeholders);
            return;
        }

        if (expression instanceof AndExpression and) {
            resolveInExpression(
                and.getLeftExpression(),
                schemaColumn,
                sources,
                placeholders
            );

            resolveInExpression(
                and.getRightExpression(),
                schemaColumn,
                sources,
                placeholders
            );

            return;
        }

        if (expression instanceof OrExpression or) {
            resolveInExpression(
                or.getLeftExpression(),
                schemaColumn,
                sources,
                placeholders
            );

            resolveInExpression(
                or.getRightExpression(),
                schemaColumn,
                sources,
                placeholders
            );

            return;
        }

        if (expression instanceof ParenthesedExpressionList<?> expressionList) {
            for (Expression nestedExpression : expressionList) {
                Placeholder nested = placeholders.of(nestedExpression);

                if (nested != null) {
                    addParameter(nested, schemaColumn, placeholders);
                } else {
                    resolveParameters(
                        nestedExpression,
                        sources,
                        placeholders
                    );
                }
            }

            return;
        }

        CastPlaceholder cast = placeholders.ofCast(expression);

        if (cast != null) {
            addCastParameter(cast, schemaColumn.name(), placeholders);
            return;
        }

        resolveCastPlaceholders(expression, placeholders);
    }

    /**
     * Resolves the pattern parameter of {@code <column> LIKE $N} or
     * {@code <column> ILIKE $N}, typed from the tested text column. A pattern
     * that binds no placeholder reaches the database as written, so a literal
     * pattern stays unanalyzed.
     *
     * <p>A cast pattern states its own type, so the pattern shape and the
     * tested column's type restrict only an uncast pattern, which takes the
     * tested column's type. A cast pattern keeps the tested column's name only
     * in the shape that accepts an uncast pattern, because only there would an
     * uncast pattern be named after that column; in every other shape it is
     * named after its own placeholder.
     */
    private void resolveLikeExpression(
        LikeExpression like,
        List<Source> sources,
        Placeholders placeholders
    ) {
        Expression left = like.getLeftExpression();
        Expression right = like.getRightExpression();

        if (placeholders.of(left) != null) {
            throw new UnsupportedOperationException(
                "A LIKE placeholder must be the pattern, not the tested value."
            );
        }

        Placeholder pattern = placeholders.of(right);

        if (pattern != null) {
            requireSupportedLikePattern(like);

            if (!(left instanceof net.sf.jsqlparser.schema.Column column)) {
                return;
            }

            dev.sqlcj.schema.Column schemaColumn = resolveColumn(column, sources).column();

            requireSupportedType(schemaColumn);

            addParameter(
                pattern,
                requireTextColumn(schemaColumn),
                placeholders
            );

            return;
        }

        CastPlaceholder castPattern = placeholders.ofCast(right);

        if (
            castPattern != null
                && unsupportedLikePatternReason(like) == null
                && left instanceof net.sf.jsqlparser.schema.Column column
        ) {
            addCastParameter(
                castPattern,
                resolveColumn(column, sources).column().name(),
                placeholders
            );

            return;
        }

        resolveCastPlaceholders(left, placeholders);
        resolveCastPlaceholders(right, placeholders);
    }

    /**
     * Requires the exact {@code LIKE}/{@code ILIKE} pattern shape this subset
     * types, so a related keyword or modifier is never analyzed as a plain
     * pattern match.
     */
    private void requireSupportedLikePattern(LikeExpression like) {
        String reason = unsupportedLikePatternReason(like);

        if (reason != null) {
            throw new UnsupportedOperationException(reason);
        }
    }

    /**
     * Reports why a {@code LIKE} expression is not the pattern shape this
     * subset types, and {@code null} when it is that shape. The reason is the
     * rejection of an uncast pattern placeholder, and it is also what decides
     * whether a cast pattern is named after the tested column.
     */
    private String unsupportedLikePatternReason(LikeExpression like) {
        if (like.isNot()) {
            return "A negated LIKE pattern placeholder is not supported.";
        }

        LikeExpression.KeyWord keyword = like.getLikeKeyWord();

        if (keyword != LikeExpression.KeyWord.LIKE && keyword != LikeExpression.KeyWord.ILIKE) {
            return "Only LIKE and ILIKE pattern placeholders are supported, but was: " + keyword;
        }

        if (like.getEscape() != null) {
            return "A LIKE pattern placeholder must not have an ESCAPE clause.";
        }

        if (like.isUseBinary()) {
            return "A binary LIKE pattern placeholder is not supported.";
        }

        return null;
    }

    /**
     * Requires a text column, because the pattern parameter takes the tested
     * column's type and only a character type makes that type a pattern.
     */
    private dev.sqlcj.schema.Column requireTextColumn(dev.sqlcj.schema.Column column) {
        boolean text = column.type() == ColumnType.VARCHAR || column.type() == ColumnType.TEXT;

        if (!text || column.array()) {
            throw new UnsupportedOperationException(
                "A LIKE pattern placeholder requires a VARCHAR or TEXT column, but %s is %s."
                    .formatted(
                        column.name(),
                        column.array()
                            ? column.type() + "[]"
                            : column.type()
                    )
            );
        }

        return column;
    }

    /**
     * Resolves the tested column of {@code IS NULL} and {@code IS NOT NULL}
     * against the query sources. The predicate names no parameter after its
     * tested value, so a tested value that is not a direct column contributes
     * only the parameters its own cast placeholders state.
     */
    private void resolveIsNullExpression(
        IsNullExpression isNull,
        List<Source> sources,
        Placeholders placeholders
    ) {
        Expression tested = isNull.getLeftExpression();

        if (tested instanceof net.sf.jsqlparser.schema.Column column) {
            resolveColumn(column, sources);
            return;
        }

        resolveCastPlaceholders(tested, placeholders);
    }

    /**
     * Resolves the bounds of {@code <column> BETWEEN $a AND $b} and its
     * negation, typing each placeholder bound from the tested column and adding
     * the start bound before the end bound. A bound that binds no placeholder
     * reaches the database as written, so a range of literal bounds stays
     * unanalyzed.
     */
    private void resolveBetweenExpression(
        Between between,
        List<Source> sources,
        Placeholders placeholders
    ) {
        Expression left = between.getLeftExpression();
        Expression start = between.getBetweenExpressionStart();
        Expression end = between.getBetweenExpressionEnd();

        if (placeholders.of(left) != null) {
            return;
        }

        if (
            left instanceof net.sf.jsqlparser.schema.Column column
                && (bindsValue(start, placeholders) || bindsValue(end, placeholders))
        ) {
            dev.sqlcj.schema.Column schemaColumn = resolveColumn(column, sources).column();

            resolveColumnValue(start, schemaColumn, placeholders);
            resolveColumnValue(end, schemaColumn, placeholders);

            return;
        }

        resolveCastPlaceholders(left, placeholders);
        resolveCastPlaceholders(start, placeholders);
        resolveCastPlaceholders(end, placeholders);
    }

    /**
     * Resolves the operands of one comparison, which names a parameter after
     * the column it compares. A placeholder compared with a column takes that
     * column's type, and a cast placeholder states its own type and keeps the
     * column's name. Every other operand contributes only the parameters its
     * own cast placeholders state.
     */
    private void resolveParameterComparison(
        ComparisonOperator comparison,
        List<Source> sources,
        Placeholders placeholders
    ) {
        Expression left = comparison.getLeftExpression();
        Expression right = comparison.getRightExpression();

        Placeholder leftPlaceholder = placeholders.of(left);
        Placeholder rightPlaceholder = placeholders.of(right);

        if (left instanceof net.sf.jsqlparser.schema.Column column && rightPlaceholder != null) {
            addParameter(
                rightPlaceholder,
                resolveColumn(column, sources).column(),
                placeholders
            );
            return;
        }

        if (leftPlaceholder != null && right instanceof net.sf.jsqlparser.schema.Column column) {
            addParameter(
                leftPlaceholder,
                resolveColumn(column, sources).column(),
                placeholders
            );
            return;
        }

        if (isColumnTypedComparison(comparison)) {
            if (left instanceof net.sf.jsqlparser.schema.Column column) {
                CastPlaceholder cast = placeholders.ofCast(right);

                if (cast != null) {
                    addCastParameter(
                        cast,
                        resolveColumn(column, sources).column().name(),
                        placeholders
                    );
                    return;
                }
            } else if (right instanceof net.sf.jsqlparser.schema.Column column) {
                CastPlaceholder cast = placeholders.ofCast(left);

                if (cast != null) {
                    addCastParameter(
                        cast,
                        resolveColumn(column, sources).column().name(),
                        placeholders
                    );
                    return;
                }
            }
        }

        resolveCastPlaceholders(left, placeholders);
        resolveCastPlaceholders(right, placeholders);
    }

    /**
     * Reports whether a comparison is one of the supported predicate
     * comparisons, whose operand is a value of the compared column's type and
     * therefore names its parameter after that column. Another operator the
     * parser reports as a comparison, such as the array overlap {@code &&},
     * compares something other than one column value, so a cast placeholder
     * there is named after itself.
     */
    private boolean isColumnTypedComparison(ComparisonOperator comparison) {
        return comparison instanceof EqualsTo
            || comparison instanceof NotEqualsTo
            || comparison instanceof GreaterThan
            || comparison instanceof GreaterThanEquals
            || comparison instanceof MinorThan
            || comparison instanceof MinorThanEquals;
    }

    /**
     * Resolves one value of a clause that names its parameter after a column: a
     * placeholder typed from the column, a cast placeholder that states its own
     * type and keeps the column's name, or an expression that is neither, which
     * contributes only the parameters its own cast placeholders state.
     */
    private void resolveColumnValue(
        Expression value,
        dev.sqlcj.schema.Column column,
        Placeholders placeholders
    ) {
        Placeholder placeholder = placeholders.of(value);

        if (placeholder != null) {
            addParameter(placeholder, column, placeholders);
            return;
        }

        CastPlaceholder cast = placeholders.ofCast(value);

        if (cast != null) {
            addCastParameter(cast, column.name(), placeholders);
            return;
        }

        resolveCastPlaceholders(value, placeholders);
    }

    /**
     * Reports whether an expression is itself the value of one parameter, which
     * is a placeholder or a cast of one.
     */
    private boolean bindsValue(Expression value, Placeholders placeholders) {
        return placeholders.of(value) != null || placeholders.ofCast(value) != null;
    }

    /**
     * Binds every cast placeholder an expression contains that no clause of the
     * query names, each after its own placeholder, in textual order. The
     * expression visitor reports an expression's parts in the order the query
     * writes them and does not descend into a subquery, whose placeholders are
     * not analyzed.
     */
    private void resolveCastPlaceholders(Expression expression, Placeholders placeholders) {
        if (expression == null) {
            return;
        }

        expression.accept(
            new ExpressionVisitorAdapter<Void>() {

                @Override
                public <S> Void visit(CastExpression cast, S context) {
                    CastPlaceholder castPlaceholder = placeholders.ofCast(cast);

                    if (castPlaceholder == null) {
                        return super.visit(cast, context);
                    }

                    addCastParameter(
                        castPlaceholder,
                        indexedParameterName(castPlaceholder.placeholder()),
                        placeholders
                    );

                    return null;
                }
            },
            null
        );
    }

    /**
     * The name an indexed placeholder takes where no column of the query names
     * it, which is the placeholder's own index. A named placeholder states its
     * name itself, so this name is used only for an indexed one.
     */
    private String indexedParameterName(Placeholder placeholder) {
        return INDEXED_PARAMETER_NAME_PREFIX + placeholder.index();
    }

    /**
     * Records one parameter occurrence a cast types, named after the column
     * whose value it is where a clause names one, and otherwise after the
     * placeholder itself.
     */
    private void addCastParameter(
        CastPlaceholder cast,
        String name,
        Placeholders placeholders
    ) {
        ColumnTypeMapping.MappedType type = cast.type();

        addParameter(
            cast.placeholder(),
            name,
            type.type(),
            type.enumType(),
            type.array(),
            type.blankPadded(),
            placeholders
        );
    }

    private void addParameter(
        Placeholder placeholder,
        dev.sqlcj.schema.Column column,
        Placeholders placeholders
    ) {
        addParameter(
            placeholder,
            column.name(),
            requireSupportedType(column),
            column.enumType(),
            column.array(),
            column.blankPadded(),
            placeholders
        );
    }

    private void addParameter(
        Placeholder placeholder,
        String name,
        ColumnType type,
        Placeholders placeholders
    ) {
        addParameter(placeholder, name, type, null, false, false, placeholders);
    }

    /**
     * Records one parameter occurrence, named after its placeholder when the
     * placeholder is named, and otherwise after the column or clause it belongs
     * to.
     */
    private void addParameter(
        Placeholder placeholder,
        String name,
        ColumnType type,
        String enumType,
        boolean array,
        boolean blankPadded,
        Placeholders placeholders
    ) {
        placeholders.add(
            new QueryParameter(
                placeholder.index(),
                placeholder.nameOr(name),
                type,
                enumType,
                array,
                blankPadded
            )
        );
    }

    /**
     * Rejects the placeholder forms an analyzed query must not contain
     * anywhere in its SQL source, including a clause this analyzer does not
     * traverse: an anonymous placeholder, which has no parameter to bind, a
     * named placeholder the compiler did not replace, which the executable SQL
     * would otherwise keep, and indexed and named placeholders in one query,
     * whose parameter order would follow two rules at once.
     */
    private void requireSupportedPlaceholders(ParsedSql parsedSql) {
        if (parsedSql.parameters().hasAnonymousParameter()) {
            throw new UnsupportedOperationException(ANONYMOUS_PARAMETER_REJECTION);
        }

        List<String> uncompiled = parsedSql.parameters().uncompiledPlaceholders();

        if (!uncompiled.isEmpty()) {
            throw new UnsupportedOperationException(
                NAMED_PLACEHOLDER_REJECTION.formatted(uncompiled.getFirst())
            );
        }

        if (parsedSql.parameters().hasPositionalParameter() && !parsedSql.parameters().names().isEmpty()) {
            throw new UnsupportedOperationException(MIXED_PLACEHOLDER_REJECTION);
        }
    }

    /**
     * The compiled placeholders of one query, against which every analyzed
     * placeholder occurrence resolves to its logical parameter.
     */
    private Placeholders toPlaceholders(ParsedSql parsedSql, Schema schema) {
        return new Placeholders(
            parsedSql.parameters().names(),
            schema,
            columnTypeMapping
        );
    }

    /**
     * Resolves the selected columns in declared order, expanding {@code *}
     * across the query sources in their declared order and
     * {@code qualifier.*} across one source, each in schema column order. A
     * column of a left-joined source is nullable even when its schema
     * declaration is not, because an unmatched row reads it as {@code NULL}.
     *
     * <p>An aliased scalar count and an aliased cast each resolve to one column
     * in their own position, so either may stand beside any other item.
     */
    private List<QueryColumn> resolveColumns(
        PlainSelect plainSelect,
        List<Source> sources,
        Schema schema
    ) {
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
                ResolvedColumn resolved = resolveColumn(column, sources);
                dev.sqlcj.schema.Column schemaColumn = resolved.column();

                columns.add(
                    new QueryColumn(
                        selectedColumnName(selectItem.getAlias(), schemaColumn),
                        requireSupportedType(schemaColumn),
                        schemaColumn.nullable() || resolved.source().leftJoined(),
                        schemaColumn.enumType(),
                        schemaColumn.array()
                    )
                );

                continue;
            }

            if (expression instanceof Function function && isScalarCount(function)) {
                columns.add(scalarCountColumn(selectItem.getAlias()));

                continue;
            }

            if (expression instanceof CastExpression cast && cast.getColDataType() != null) {
                columns.add(castColumn(cast, selectItem.getAlias(), schema));

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
     * Reports whether a projected function is the supported scalar count, which
     * is an unqualified case-insensitive {@code COUNT} whose single argument is
     * exactly {@code *}. A qualified name such as {@code pg_catalog.count(*)},
     * a {@code DISTINCT} count, and {@code COUNT(t.*)}, whose argument is a
     * subtype of the {@code COUNT(*)} argument, are other functions.
     */
    private boolean isScalarCount(Function function) {
        List<String> name = function.getMultipartName();

        if (name == null || name.size() != 1 || !name.getFirst().equalsIgnoreCase("count")) {
            return false;
        }

        if (function.isDistinct()) {
            return false;
        }

        ExpressionList<?> arguments = function.getParameters();

        return arguments != null
            && arguments.size() == 1
            && arguments.getFirst().getClass() == AllColumns.class;
    }

    /**
     * Resolves the scalar count projection into its single result column, which
     * is named after the required alias. The column is {@code BIGINT} because
     * {@code count(*)} returns {@code bigint}, and it is non-null because a
     * count is always a number, {@code 0} when no row matches.
     */
    private QueryColumn scalarCountColumn(Alias alias) {
        if (alias == null) {
            throw new UnsupportedOperationException(
                "COUNT(*) requires a result alias, such as COUNT(*) AS total."
            );
        }

        return new QueryColumn(alias.getUnquotedName(), ColumnType.BIGINT, false);
    }

    /**
     * Resolves a cast projection into its single result column, which is named
     * after the required alias and typed by the cast, as a cast types a
     * placeholder. The column is nullable because the cast operand is not
     * analyzed, so whether it can read as {@code NULL} is unknown here.
     *
     * <p>The operand itself reaches the database as written, as a non-binding
     * write value does, so PostgreSQL rather than sqlcj checks it.
     */
    private QueryColumn castColumn(CastExpression cast, Alias alias, Schema schema) {
        if (alias == null) {
            throw new UnsupportedOperationException(
                "A cast projection requires a result alias, such as SUM(amount)::numeric AS total."
            );
        }

        String name = alias.getUnquotedName();

        ColumnTypeMapping.MappedType type = columnTypeMapping.map(
            cast.getColDataType(),
            schema.enums()
        );

        if (!type.mapped()) {
            throw new UnsupportedOperationException(
                "Result column '%s' has unsupported cast type %s"
                    .formatted(name, type.unsupportedType())
            );
        }

        return new QueryColumn(name, type.type(), true, type.enumType(), type.array());
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
     * type still comes from the schema column, and its nullability from the
     * schema column and its source.
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
                    requireSupportedType(column),
                    column.nullable() || source.leftJoined(),
                    column.enumType(),
                    column.array()
                )
            )
            .toList();
    }

    /**
     * Reports the analyzed type of a schema column. A column the schema
     * recorded without a mapped type fails here, in the query that reads,
     * binds, or expands it, rather than when the schema is parsed.
     */
    private ColumnType requireSupportedType(dev.sqlcj.schema.Column column) {
        if (column.type() == null) {
            throw new UnsupportedOperationException(
                "Column '%s' has unsupported type %s"
                    .formatted(column.name(), column.unsupportedType())
            );
        }

        return column.type();
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
