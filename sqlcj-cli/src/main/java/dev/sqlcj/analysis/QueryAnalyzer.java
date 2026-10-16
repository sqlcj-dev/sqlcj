package dev.sqlcj.analysis;

import dev.sqlcj.parser.Query;
import dev.sqlcj.parser.QueryType;
import dev.sqlcj.schema.ColumnType;
import dev.sqlcj.schema.Schema;
import dev.sqlcj.schema.parser.ColumnTypeMapping;
import dev.sqlcj.schema.parser.SchemaNamespace;
import dev.sqlcj.sql.ParsedSql;
import dev.sqlcj.sql.PlaceholderOccurrence;
import dev.sqlcj.type.DefaultTypeResolver;
import dev.sqlcj.type.TypeResolver;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
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
import net.sf.jsqlparser.statement.ParenthesedStatement;
import net.sf.jsqlparser.statement.ReturningClause;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.Index;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.delete.ParenthesedDelete;
import net.sf.jsqlparser.statement.insert.ConflictActionType;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.insert.InsertConflictAction;
import net.sf.jsqlparser.statement.insert.InsertConflictTarget;
import net.sf.jsqlparser.statement.insert.ParenthesedInsert;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.LateralSubSelect;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.Offset;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.ParenthesedUpdate;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
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

    /** The rejection of a statement kind outside the supported subset. */
    private static final String UNSUPPORTED_STATEMENT_REJECTION = """
        Only SELECT, INSERT, UPDATE, and DELETE statements are supported.""";

    /** The rejection of a set operation, which joins two queries into one. */
    private static final String SET_OPERATION_REJECTION = """
        UNION, INTERSECT, and EXCEPT are not supported; write one query per SELECT.""";

    /**
     * The rejection of a projected expression that is neither a column, a
     * wildcard, the scalar count, nor a cast, which is written as the query
     * spells it. A cast states the type of such a projection, so the rejection
     * names that fix.
     */
    private static final String COMPUTED_PROJECTION_REJECTION = """
        Cannot infer the type of %s in SELECT; state it with a cast and an alias, \
        such as SUM(amount)::numeric AS total""";

    /**
     * The rejection of a returned item that is neither a column of the write
     * target nor a bare {@code *}, which is written as the query spells it. A
     * cast does not make such an item supported, so the rejection names the
     * items that are.
     */
    private static final String COMPUTED_RETURNING_REJECTION = """
        Unsupported RETURNING item %s; return columns of the target table or *""";

    /**
     * The rejection of an aliased returned wildcard, which is written as the
     * query spells the wildcard, because an alias names one result component
     * and a wildcard returns a row of them.
     */
    private static final String RETURNING_WILDCARD_ALIAS_REJECTION = """
        RETURNING %s must not be aliased.""";

    /**
     * The rejection of a modified returned wildcard, which is written as the
     * query spells the wildcard, because a modifier changes which columns the
     * wildcard returns.
     */
    private static final String RETURNING_WILDCARD_MODIFIER_REJECTION = """
        RETURNING %s must not be modified.""";

    /**
     * The rejection of a bare {@code *} on a joined write, which PostgreSQL
     * returns the columns of every source for, while the only complete row such
     * a write returns here is its target's.
     */
    private static final String JOINED_RETURNING_ALL_REJECTION = """
        RETURNING * is not supported with UPDATE ... FROM or DELETE ... USING; \
        write RETURNING %s.* for the target's row.""";

    /**
     * The rejection of a returned wildcard of a source other than the write
     * target, whose complete row the write does not return, named by the source
     * the qualifier exposes.
     */
    private static final String OTHER_SOURCE_RETURNING_WILDCARD_REJECTION = """
        RETURNING %s.* is not supported; return the columns of %s by name.""";

    /**
     * The rejection of an {@code UPDATE} that names several targets, whose
     * updated rows this subset does not model, naming the clause that adds a
     * source instead.
     */
    private static final String MULTI_TABLE_UPDATE_REJECTION = """
        UPDATE of several tables is not supported; write the other sources in UPDATE ... FROM.""";

    /**
     * The rejection of a joined {@code DELETE}, whose deleted rows this subset
     * does not model, naming the clause that adds a source instead.
     */
    private static final String JOINED_DELETE_REJECTION = """
        A joined DELETE is not supported; write the other sources in DELETE ... USING.""";

    /** The rejection of a source of a write's {@code FROM} or {@code USING}. */
    private static final String WRITE_SOURCE_REJECTION = "Only table sources are supported.";

    /** The rejection of a {@code LATERAL} source. */
    private static final String LATERAL_REJECTION = "LATERAL is not supported.";

    /**
     * The rejection of a subquery source without an alias, which exposes no
     * name its columns could be read through.
     */
    private static final String UNALIASED_FROM_SUBQUERY_REJECTION = """
        A subquery in FROM requires an alias, such as (SELECT ...) AS recent.""";

    /**
     * The rejection of a column list on a derived source, written on a
     * {@code WITH} name or on a subquery alias, which renames the columns of
     * its {@code SELECT} by position.
     */
    private static final String DERIVED_SOURCE_COLUMN_LIST_REJECTION = """
        A column list on %s is not supported; alias the columns in its SELECT instead.""";

    /**
     * The rejection of a derived source two of whose columns expose one name,
     * which makes a reference to that name ambiguous.
     */
    private static final String DERIVED_SOURCE_COLUMN_NAME_REJECTION = """
        Derived source %s has more than one column named %s; give each a distinct alias.""";

    /** The rejection of a recursive {@code WITH} clause. */
    private static final String RECURSIVE_WITH_REJECTION = "WITH RECURSIVE is not supported.";

    /** The rejection of a {@code WITH} body that writes rows. */
    private static final String DATA_MODIFYING_WITH_REJECTION = """
        A data-modifying statement in WITH is not supported; run it as a query of its own.""";

    /**
     * The rejection of an {@code INSERT} whose rows come from neither
     * parenthesized {@code VALUES} rows nor a {@code SELECT}, which are the
     * row sources this subset models.
     */
    private static final String INSERT_ROW_SOURCE_REJECTION = """
        INSERT requires parenthesized VALUES rows or a SELECT.""";

    /**
     * The rejection of a {@code VALUES} row or an insert {@code SELECT} that
     * writes a number of values other than the number of written columns.
     */
    private static final String INSERT_COUNT_REJECTION = """
        INSERT column and value counts must match.""";

    /**
     * The rejection of a placeholder that no clause of the query types and no
     * cast states the type of, which names the placeholder as the query spells
     * it and where the query writes it. The suggested cast is always
     * {@code ::text}, because sqlcj infers no type for such a placeholder.
     */
    private static final String UNTYPED_PLACEHOLDER_REJECTION = """
        Cannot infer the type of %s in %s; state it with a cast, such as %s::text""";

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
     *
     * <p>A derived source — a common table expression or a subquery source —
     * carries the result columns of its own {@code SELECT} under its exposed
     * name instead of a schema table's columns.
     */
    private record Source(
        String name,
        dev.sqlcj.schema.Table table,
        boolean derived,
        boolean leftJoined
    ) {

        /**
         * The schema's declared name of the table whose complete row this
         * source contributes, and {@code null} for a derived source, whose
         * columns are specific to the query that declares it.
         */
        private String rowTable() {
            return derived
                ? null
                : table.name();
        }
    }

    /**
     * The column scope of one query level: the sources that level resolves a
     * column reference against, the common table expressions in scope there,
     * and the level enclosing it, which is {@code null} for a query no
     * analyzed query encloses.
     *
     * <p>A predicate subquery is the level inside the level its predicate
     * stands in, so a column resolves in its own sources first and then in
     * each enclosing level outward, as PostgreSQL resolves a correlated
     * reference. A join condition's level is the sources written up to and
     * including its own joined source.
     */
    private record Scope(List<Source> sources, List<Source> commonTableExpressions, Scope enclosing) {

        /** A lone query level, which no analyzed query encloses. */
        private static Scope of(List<Source> sources, List<Source> commonTableExpressions) {
            return new Scope(sources, commonTableExpressions, null);
        }

        /** A lone query level that declares no common table expression. */
        private static Scope of(List<Source> sources) {
            return of(sources, List.of());
        }

        /**
         * The level of the same query whose sources are the given ones, which
         * is how a join condition narrows its own level without changing the
         * levels around it.
         */
        private Scope narrowedTo(List<Source> levelSources) {
            return new Scope(levelSources, commonTableExpressions, enclosing);
        }

        /**
         * The sources every level makes visible, from the innermost level
         * outward, which are the sources a column reference may resolve in.
         */
        private List<Source> visibleSources() {
            List<Source> visible = new ArrayList<>();

            for (Scope level = this; level != null; level = level.enclosing()) {
                visible.addAll(level.sources());
            }

            return visible;
        }
    }

    /**
     * The inputs every analyzed {@code SELECT} of one statement shares: the
     * parsed source, the schema it resolves against, and the placeholders its
     * clauses type.
     */
    private record Analysis(ParsedSql parsedSql, Schema schema, Placeholders placeholders) {
    }

    /**
     * The analyzed facts of one {@code SELECT}: the name the query writes for
     * its first source, its ordered sources, and its projection in selected
     * order.
     */
    private record AnalyzedSelect(
        String table,
        List<Source> sources,
        ResolvedProjection projection
    ) {

        /** The columns this {@code SELECT} exposes as a derived source. */
        private List<dev.sqlcj.schema.Column> columns() {
            return projection.columns();
        }
    }

    /**
     * One analyzed projection in selected order: the columns it expands to,
     * which are the columns a derived source exposes, and the result components
     * of a top-level read, where a wildcard of a schema table that is not
     * left-joined is one embedded row.
     */
    private record ResolvedProjection(
        List<dev.sqlcj.schema.Column> columns,
        List<QueryColumn> resultColumns
    ) {
    }

    /** One column reference resolved against the ordered query sources. */
    private record ResolvedColumn(Source source, dev.sqlcj.schema.Column column) {
    }

    /**
     * One placeholder occurrence, resolved to the logical parameter it binds
     * and, for a named placeholder, to the name that parameter carries. The
     * placeholder expression identifies the occurrence, which is how a clause's
     * analysis meets the placeholder occurrences the parser reported.
     */
    private record Placeholder(Expression expression, int index, String name) {

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
     * The compiled placeholders of one query and the parameter occurrences its
     * clauses typed, each recorded against the placeholder expression it
     * resolves. The textual order of the occurrences is the order the parser
     * reported them in, not the order the clauses were analyzed in.
     */
    private static final class Placeholders {

        /** The compiled placeholder names in logical parameter order. */
        private final List<String> names;

        /** The schema whose declared enum types a cast may name. */
        private final Schema schema;

        /** Resolves the type a cast states. */
        private final ColumnTypeMapping columnTypeMapping;

        /** The occurrences a clause typed, by their placeholder expression. */
        private final Map<Expression, QueryParameter> typedOccurrences = new IdentityHashMap<>();

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

                return new Placeholder(expression, parameter.getIndex(), null);
            }

            if (expression instanceof JdbcNamedParameter named) {
                return new Placeholder(
                    expression,
                    requireCompiledName(named),
                    named.getName()
                );
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

        /**
         * Records the parameter occurrence a clause of the query typed for one
         * placeholder. Each placeholder occurrence stands in one clause, so
         * typing one twice is a defect of this analyzer.
         */
        private void record(Placeholder placeholder, QueryParameter occurrence) {
            if (typedOccurrences.put(placeholder.expression(), occurrence) != null) {
                throw new IllegalStateException(
                    "Placeholder %s was typed twice.".formatted(placeholder.spelled())
                );
            }
        }

        /**
         * Takes the occurrence a clause typed for one placeholder, and
         * {@code null} when no clause typed it.
         */
        private QueryParameter typed(Expression placeholder) {
            return typedOccurrences.remove(placeholder);
        }

        /**
         * Requires that every occurrence a clause typed was one of the
         * placeholder occurrences the parser reported, so that no analyzed
         * parameter is left out of the binding order.
         */
        private void requireReportedOccurrences() {
            if (!typedOccurrences.isEmpty()) {
                throw new IllegalStateException(
                    "A typed placeholder is not a reported placeholder occurrence."
                );
            }
        }
    }

    public QueryModel analyze(Query query, ParsedSql parsedSql, Schema schema) {
        requireSupportedPlaceholders(parsedSql);

        Statement statement = parsedSql.statement();

        if (statement instanceof Select select) {
            return analyzeSelectStatement(query, parsedSql, select, schema);
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

        throw new UnsupportedOperationException(UNSUPPORTED_STATEMENT_REJECTION);
    }

    /**
     * Analyzes a {@code SELECT} statement, which is classified by the query it
     * writes inside its parentheses, at any depth, so a parenthesized query is
     * the query it encloses. The analyzed read is the top-level statement, so
     * the annotation, the row table, and the query model are decided here,
     * while the sources, the result columns, and the clause placeholders follow
     * the rules every analyzed {@code SELECT} shares.
     */
    private QueryModel analyzeSelectStatement(
        Query query,
        ParsedSql parsedSql,
        Select select,
        Schema schema
    ) {
        List<Select> selects = parenthesisLevels(select);

        PlainSelect plainSelect = requirePlainSelect(selects);

        requireResultQueryType(query);

        Analysis analysis = new Analysis(parsedSql, schema, toPlaceholders(parsedSql, schema));

        AnalyzedSelect analyzed = analyzePlainSelect(
            plainSelect,
            analysis,
            analyzeCommonTableExpressions(selects, analysis, List.of())
        );

        String rowTable = resolveSelectRowTable(plainSelect, analyzed.sources());

        return toQueryModel(
            query,
            parsedSql,
            analyzed.table(),
            rowTable == null
                ? analyzed.projection().resultColumns()
                : toQueryColumns(analyzed.columns()),
            analysis.placeholders(),
            rowTable
        );
    }

    /**
     * Analyzes one nested {@code SELECT} by the rules of a top-level read: it
     * is classified inside its own parentheses, its own {@code WITH} list is
     * analyzed beside the common table expressions of the queries around it,
     * and its sources, result columns, and clause placeholders are resolved as
     * a top-level read's are. Its annotation, its row table, and the query
     * model stay the top-level statement's own facts.
     */
    private AnalyzedSelect analyzeNestedSelect(
        Select select,
        Analysis analysis,
        List<Source> commonTableExpressions
    ) {
        List<Select> selects = parenthesisLevels(select);

        PlainSelect plainSelect = requirePlainSelect(selects);

        return analyzePlainSelect(
            plainSelect,
            analysis,
            analyzeCommonTableExpressions(selects, analysis, commonTableExpressions)
        );
    }

    /**
     * The {@code SELECT} of each parenthesis level of one query, outermost
     * first, because a parenthesized query is the query it encloses, at any
     * depth, and each level may declare a {@code WITH} list of its own.
     */
    private List<Select> parenthesisLevels(Select select) {
        List<Select> selects = new ArrayList<>();
        Select enclosed = select;

        selects.add(enclosed);

        while (enclosed instanceof ParenthesedSelect parenthesed) {
            enclosed = parenthesed.getSelect();

            selects.add(enclosed);
        }

        return selects;
    }

    /**
     * Requires the query a {@code SELECT} encloses to be a single plain
     * {@code SELECT}. A set operation and every other query form, such as a
     * {@code VALUES} or {@code TABLE} statement, states no single supported
     * query, so it is rejected before the annotation is checked and before any
     * {@code WITH} body is analyzed.
     */
    private PlainSelect requirePlainSelect(List<Select> selects) {
        Select enclosed = selects.get(selects.size() - 1);

        if (enclosed instanceof PlainSelect plainSelect) {
            return plainSelect;
        }

        if (enclosed instanceof SetOperationList) {
            throw new UnsupportedOperationException(SET_OPERATION_REJECTION);
        }

        throw new UnsupportedOperationException(UNSUPPORTED_STATEMENT_REJECTION);
    }

    /**
     * Analyzes one plain {@code SELECT} against the common table expressions in
     * scope: its sources, its result columns, and the parameters its join
     * conditions, {@code WHERE}, and pagination clauses type.
     */
    private AnalyzedSelect analyzePlainSelect(
        PlainSelect plainSelect,
        Analysis analysis,
        List<Source> commonTableExpressions
    ) {
        List<Source> sources = resolveSources(plainSelect, analysis, commonTableExpressions, null);

        Scope scope = Scope.of(sources, commonTableExpressions);

        ResolvedProjection projection = resolveProjection(plainSelect, analysis, scope);

        resolveBindingParameters(plainSelect, scope, analysis);

        return new AnalyzedSelect(
            declaredSourceName(plainSelect.getFromItem(), sources.get(0)),
            sources,
            projection
        );
    }

    /**
     * Analyzes one subquery of a predicate, which is the level inside the level
     * its predicate stands in: it is classified inside its own parentheses as a
     * top-level read is, its own {@code WITH} list is analyzed beside the common
     * table expressions in scope around it, and its sources, its join
     * conditions, and its clause placeholders resolve through its own level and
     * then each enclosing one.
     *
     * <p>Its {@code SELECT} list states no result column of the analyzed query,
     * because the predicate reads whether a row exists or compares one value,
     * so a direct column there only resolves, whether or not the schema maps its
     * type, and every other item reaches the database as written.
     */
    private void analyzePredicateSubquery(
        ParenthesedSelect subquery,
        Scope enclosing,
        Analysis analysis
    ) {
        List<Select> selects = parenthesisLevels(subquery);

        PlainSelect plainSelect = requirePlainSelect(selects);

        List<Source> commonTableExpressions = analyzeCommonTableExpressions(
            selects,
            analysis,
            enclosing.commonTableExpressions()
        );

        Scope scope = new Scope(
            resolveSources(plainSelect, analysis, commonTableExpressions, enclosing),
            commonTableExpressions,
            enclosing
        );

        resolvePredicateSubqueryColumns(plainSelect, scope);
        resolveBindingParameters(plainSelect, scope, analysis);
    }

    /**
     * Resolves the direct columns of a predicate subquery's {@code SELECT}
     * list, which reach the database as written like every other item there but
     * name a column of the sources in scope.
     */
    private void resolvePredicateSubqueryColumns(PlainSelect plainSelect, Scope scope) {
        for (SelectItem<?> selectItem : plainSelect.getSelectItems()) {
            net.sf.jsqlparser.schema.Column selectedColumn = directColumn(selectItem.getExpression());

            if (selectedColumn != null) {
                resolveColumn(selectedColumn, scope);
            }
        }
    }

    /**
     * The name the query writes for its first source, which the analyzed model
     * carries as the query's table: the table's own name as the query spells
     * it, and the exposed name of a derived source, which names no table.
     */
    private String declaredSourceName(FromItem fromItem, Source source) {
        return source.derived()
            ? source.name()
            : ((Table) fromItem).getUnquotedName();
    }

    /**
     * Analyzes the {@code WITH} items each parenthesis level of one query
     * declares, in the order the query writes them, and reports the derived
     * sources they declare after the ones the queries around it declared.
     *
     * <p>Every body is analyzed, whether or not a source reads it, so its
     * placeholders are typed by its own clause rules. A body sees the common
     * table expressions written before it and those of every enclosing query,
     * so a name declared here reads the schema table of that name inside its
     * own body.
     */
    private List<Source> analyzeCommonTableExpressions(
        List<Select> selects,
        Analysis analysis,
        List<Source> commonTableExpressions
    ) {
        List<Source> scope = new ArrayList<>(commonTableExpressions);

        for (Select select : selects) {
            List<WithItem<?>> withItems = select.getWithItemsList();

            if (withItems == null) {
                continue;
            }

            for (WithItem<?> withItem : withItems) {
                scope.add(analyzeCommonTableExpression(withItem, analysis, List.copyOf(scope)));
            }
        }

        return List.copyOf(scope);
    }

    /**
     * Analyzes the {@code WITH} items of one statement, which are the only ones
     * a write declares, and reports the derived sources they declare, which are
     * in scope of the predicates of that write.
     */
    private List<Source> analyzeCommonTableExpressions(
        List<WithItem<?>> withItems,
        Analysis analysis
    ) {
        if (withItems == null) {
            return List.of();
        }

        List<Source> scope = new ArrayList<>();

        for (WithItem<?> withItem : withItems) {
            scope.add(analyzeCommonTableExpression(withItem, analysis, List.copyOf(scope)));
        }

        return List.copyOf(scope);
    }

    /**
     * Analyzes one {@code WITH} item into the derived source it declares, whose
     * columns are its body's result columns. A recursive item reads itself, and
     * a data-modifying one writes rows, neither of which this subset models.
     */
    private Source analyzeCommonTableExpression(
        WithItem<?> withItem,
        Analysis analysis,
        List<Source> commonTableExpressions
    ) {
        if (withItem.isRecursive()) {
            throw new UnsupportedOperationException(RECURSIVE_WITH_REJECTION);
        }

        Select body = requireSelectBody(withItem);
        String name = withItem.getUnquotedAliasName();

        if (withItem.getWithItemList() != null) {
            throw new UnsupportedOperationException(
                DERIVED_SOURCE_COLUMN_LIST_REJECTION.formatted(name)
            );
        }

        return toDerivedSource(
            name,
            analyzeNestedSelect(body, analysis, commonTableExpressions).columns(),
            false
        );
    }

    /**
     * The {@code SELECT} one {@code WITH} item states inside its parentheses. A
     * body that writes rows is rejected from the parsed statement kind, because
     * reading such a body as a {@code SELECT} is an error of the parser's own
     * model, and every other body states no query at all.
     */
    private Select requireSelectBody(WithItem<?> withItem) {
        ParenthesedStatement statement = withItem.getParenthesedStatement();

        if (statement instanceof ParenthesedSelect select) {
            return select.getSelect();
        }

        if (
            statement instanceof ParenthesedInsert
                || statement instanceof ParenthesedUpdate
                || statement instanceof ParenthesedDelete
        ) {
            throw new UnsupportedOperationException(DATA_MODIFYING_WITH_REJECTION);
        }

        throw new UnsupportedOperationException(UNSUPPORTED_STATEMENT_REJECTION);
    }

    private QueryModel analyzeInsert(Query query, ParsedSql parsedSql, Insert insert, Schema schema) {
        ReturningClause returningClause = insert.getReturningClause();

        requireWriteQueryType(query, returningClause);

        Table table = insert.getTable();
        Source source = toSource(table, schema);

        Placeholders placeholders = toPlaceholders(parsedSql, schema);
        Analysis analysis = new Analysis(parsedSql, schema, placeholders);

        List<Source> commonTableExpressions = analyzeCommonTableExpressions(
            insert.getWithItemsList(),
            analysis
        );

        resolveInsertRows(insert, source.table(), commonTableExpressions, analysis);

        List<QueryColumn> columns = List.of();
        String rowTable = null;

        if (returningClause != null) {
            columns = resolveReturningColumns(returningClause, List.of(source));
            rowTable = resolveReturningRowTable(returningClause, List.of(source));
        }

        resolveConflictParameters(insert, source, commonTableExpressions, analysis);

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
        requireSupportedUpdateSources(update);

        Table table = update.getTable();
        Source target = toSource(table, schema);

        Placeholders placeholders = toPlaceholders(parsedSql, schema);
        Analysis analysis = new Analysis(parsedSql, schema, placeholders);

        List<Source> commonTableExpressions = analyzeCommonTableExpressions(
            update.getWithItemsList(),
            analysis
        );

        List<Source> sources = resolveWriteSources(
            target,
            update.getFromItem() == null
                ? List.of()
                : List.of(update.getFromItem()),
            update.getJoins(),
            analysis,
            commonTableExpressions
        );

        List<QueryColumn> columns = List.of();
        String rowTable = null;

        if (returningClause != null) {
            columns = resolveReturningColumns(returningClause, sources);
            rowTable = resolveReturningRowTable(returningClause, sources);
        }

        resolveUpdateSetParameters(update.getUpdateSets(), target.table(), placeholders);

        if (update.getWhere() != null) {
            resolveParameters(
                update.getWhere(),
                Scope.of(sources, commonTableExpressions),
                analysis
            );
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
        requireSupportedDeleteSources(delete);

        Table table = delete.getTable();
        Source target = toSource(table, schema);

        Placeholders placeholders = toPlaceholders(parsedSql, schema);
        Analysis analysis = new Analysis(parsedSql, schema, placeholders);

        List<Source> commonTableExpressions = analyzeCommonTableExpressions(
            delete.getWithItemsList(),
            analysis
        );

        List<Source> sources = resolveWriteSources(
            target,
            delete.getUsingFromItemList(),
            null,
            analysis,
            commonTableExpressions
        );

        List<QueryColumn> columns = List.of();
        String rowTable = null;

        if (returningClause != null) {
            columns = resolveReturningColumns(returningClause, sources);
            rowTable = resolveReturningRowTable(returningClause, sources);
        }

        if (delete.getWhere() != null) {
            resolveParameters(
                delete.getWhere(),
                Scope.of(sources, commonTableExpressions),
                analysis
            );
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
     * Resolves the returned columns in declared order against the write's
     * sources, the target first.
     *
     * <p>A clause that is exactly a wildcard of the target returns that table's
     * complete row, so it resolves to the row's own columns. A wildcard written
     * beside another item is one embedded row of the target in its own
     * position.
     */
    private List<QueryColumn> resolveReturningColumns(
        ReturningClause returningClause,
        List<Source> sources
    ) {
        if (
            returningClause.getKeyword() != ReturningClause.Keyword.RETURNING
                || returningClause.getDataItems() != null
        ) {
            throw new UnsupportedOperationException(
                "Only a RETURNING clause without data items is supported."
            );
        }

        boolean wholeRow = resolveReturningRowTable(returningClause, sources) != null;

        List<QueryColumn> columns = new ArrayList<>();

        for (SelectItem<?> returningItem : returningClause) {
            columns.addAll(
                resolveReturningItem(
                    returningItem.getExpression(),
                    returningItem.getAlias(),
                    sources,
                    !wholeRow
                )
            );
        }

        return List.copyOf(columns);
    }

    /**
     * Reports the table whose complete row a returning write returns, which is
     * the write target when the clause is exactly a wildcard of the target, and
     * {@code null} for a returned column list. The name is the schema's own
     * spelling of the table, so every query returning that row shares one row
     * identity.
     *
     * <p>A bare {@code *} is such a wildcard only on a single-table write,
     * because a joined write returns the columns of every source for it and is
     * rejected where the returned columns are resolved.
     */
    private String resolveReturningRowTable(ReturningClause returningClause, List<Source> sources) {
        if (returningClause.size() != 1) {
            return null;
        }

        Expression expression = returningClause.get(0).getExpression();
        Source target = sources.get(0);

        if (expression instanceof AllTableColumns qualified) {
            return lookupSource(sources, returningQualifier(qualified))
                .filter(source -> source == target)
                .map(source -> source.table().name())
                .orElse(null);
        }

        return expression instanceof AllColumns && sources.size() == 1
            ? target.table().name()
            : null;
    }

    /**
     * Resolves one returned item, which is either a wildcard of the write
     * target or a direct column of one of the write's sources, whose alias
     * names its result component. A computed item, a subscripted reference
     * included, has no schema type to generate, so it is rejected instead of
     * analyzed.
     *
     * <p>A returned column of a left-joined source is nullable even when its
     * schema declaration is not, because an unmatched row reads it as
     * {@code NULL}.
     */
    private List<QueryColumn> resolveReturningItem(
        Expression expression,
        Alias alias,
        List<Source> sources,
        boolean embedRow
    ) {
        if (expression instanceof AllColumns allColumns) {
            return resolveReturningWildcard(allColumns, alias, sources, embedRow);
        }

        net.sf.jsqlparser.schema.Column returnedColumn = directColumn(expression);

        if (returnedColumn != null) {
            ResolvedColumn resolved = resolveColumn(returnedColumn, Scope.of(sources));
            dev.sqlcj.schema.Column schemaColumn = resolved.column();

            return List.of(
                new QueryColumn(
                    selectedColumnName(alias, schemaColumn),
                    requireSupportedType(schemaColumn),
                    schemaColumn.nullable() || resolved.source().leftJoined(),
                    schemaColumn.enumType(),
                    schemaColumn.array()
                )
            );
        }

        throw new UnsupportedOperationException(
            COMPUTED_RETURNING_REJECTION.formatted(written(expression))
        );
    }

    /**
     * Resolves one returned wildcard, which returns the complete row of the
     * write target: a bare {@code *} on a single-table write, and
     * {@code target.*} on any write. The columns of another source are
     * returnable by name, and a bare {@code *} on a joined write would return
     * every source's columns, so each is rejected naming what to write instead.
     *
     * <p>A wildcard written beside another returned item is one embedded row of
     * the target, named after the name the target exposes, and a clause that is
     * exactly the wildcard resolves to the row's own columns.
     *
     * <p>An alias names one result component and a modifier changes which
     * columns the wildcard returns, so neither is supported on a bare or a
     * qualified wildcard.
     */
    private List<QueryColumn> resolveReturningWildcard(
        AllColumns allColumns,
        Alias alias,
        List<Source> sources,
        boolean embedRow
    ) {
        String written = writtenReturningWildcard(allColumns);

        if (alias != null) {
            throw new UnsupportedOperationException(
                RETURNING_WILDCARD_ALIAS_REJECTION.formatted(written)
            );
        }

        if (allColumns.getExceptColumns() != null || allColumns.getReplaceExpressions() != null) {
            throw new UnsupportedOperationException(
                RETURNING_WILDCARD_MODIFIER_REJECTION.formatted(written)
            );
        }

        Source target = sources.get(0);

        if (!(allColumns instanceof AllTableColumns qualified)) {
            if (sources.size() > 1) {
                throw new UnsupportedOperationException(
                    JOINED_RETURNING_ALL_REJECTION.formatted(target.name())
                );
            }

            return returningWildcardColumns(target, embedRow);
        }

        Source source = findSource(sources, returningQualifier(qualified));

        if (source != target) {
            throw new UnsupportedOperationException(
                OTHER_SOURCE_RETURNING_WILDCARD_REJECTION.formatted(source.name(), source.name())
            );
        }

        return returningWildcardColumns(target, embedRow);
    }

    /**
     * The returned components of one wildcard of the write target, which are
     * one embedded row of the target beside another returned item and the
     * target row's own columns when the clause is exactly the wildcard.
     */
    private List<QueryColumn> returningWildcardColumns(Source target, boolean embedRow) {
        List<QueryColumn> columns = toQueryColumns(resolveAllColumns(target));

        return embedRow
            ? List.of(QueryColumn.row(target.name(), target.rowTable(), columns))
            : columns;
    }

    /**
     * The exposed name a qualified returned wildcard names, which the parser
     * reports as the wildcard's own table, and as a returning qualifier for the
     * {@code old} and {@code new} row aliases of a write.
     */
    private String returningQualifier(AllTableColumns allTableColumns) {
        Table table = allTableColumns.getTable();

        return table == null
            ? allTableColumns.getReturningQualifier()
            : table.getUnquotedName();
    }

    /**
     * One returned wildcard as the query writes it, which a diagnostic names,
     * without any modifier the wildcard carries.
     */
    private String writtenReturningWildcard(AllColumns allColumns) {
        return allColumns instanceof AllTableColumns qualified
            ? returningQualifier(qualified) + ".*"
            : "*";
    }

    /**
     * One expression as the query writes it, on one line: the parser's own
     * spelling of the expression, with every run of whitespace written as one
     * space, so a diagnostic naming an expression written across lines stays
     * one line.
     */
    private String written(Expression expression) {
        return expression.toString()
            .replaceAll("\\s+", " ")
            .trim();
    }

    /**
     * Rejects an {@code UPDATE} that names several tables before {@code SET},
     * whose updated rows this subset does not model, on an {@code :exec} and on
     * a returning update alike. The parser reports both the comma-separated and
     * the joined spelling of that form as the joins of the target, so a source
     * written in {@code FROM}, which this subset models, is not one of them.
     */
    private void requireSupportedUpdateSources(Update update) {
        if (update.getStartJoins() != null) {
            throw new UnsupportedOperationException(MULTI_TABLE_UPDATE_REJECTION);
        }
    }

    /**
     * Rejects a joined {@code DELETE}, whose deleted rows this subset does not
     * model, on an {@code :exec} and on a returning delete alike. The parser
     * reports a {@code USING} source, which this subset models, as a source of
     * its own clause rather than as a join.
     */
    private void requireSupportedDeleteSources(Delete delete) {
        if (delete.getJoins() != null) {
            throw new UnsupportedOperationException(JOINED_DELETE_REJECTION);
        }
    }

    /**
     * Resolves the rows an {@code INSERT} writes against its explicit column
     * list, which pairs with the written values by position and is also their
     * textual order.
     *
     * <p>The row source is classified inside its parentheses, at any depth, as
     * a top-level {@code SELECT} is, so {@code VALUES} rows written inside
     * parentheses are those rows and a parenthesized query is the query it
     * encloses. A column list with no row source, as in {@code DEFAULT VALUES},
     * states no row of this subset at all.
     *
     * <p>Every parenthesis level of the row source may declare a {@code WITH}
     * list of its own, which is analyzed beside the insert's own clauses so its
     * placeholders are typed from its columns. A {@code VALUES} source reads no
     * source, so the derived sources such a list declares are read by nothing.
     */
    private void resolveInsertRows(
        Insert insert,
        dev.sqlcj.schema.Table table,
        List<Source> insertCommonTableExpressions,
        Analysis analysis
    ) {
        ExpressionList<net.sf.jsqlparser.schema.Column> columns = insert.getColumns();

        if (columns == null || columns.isEmpty()) {
            throw new UnsupportedOperationException("INSERT requires an explicit column list.");
        }

        Select rowSource = insert.getSelect();

        if (rowSource == null) {
            throw new UnsupportedOperationException(INSERT_ROW_SOURCE_REJECTION);
        }

        List<Select> selects = parenthesisLevels(rowSource);

        if (selects.get(selects.size() - 1) instanceof Values values) {
            analyzeCommonTableExpressions(selects, analysis, insertCommonTableExpressions);
            resolveInsertValueRows(values, columns, table, analysis.placeholders());

            return;
        }

        PlainSelect plainSelect = requirePlainSelect(selects);

        resolveInsertSelect(
            plainSelect,
            columns,
            table,
            analyzeCommonTableExpressions(selects, analysis, insertCommonTableExpressions),
            analysis
        );
    }

    /**
     * Resolves the values of every {@code VALUES} row against the column list,
     * which pairs with each row by position. The parser reports one row as the
     * parenthesized list of its values and several rows as a list of such
     * rows, so an element that is not a parenthesized row states no row.
     */
    private void resolveInsertValueRows(
        Values values,
        ExpressionList<net.sf.jsqlparser.schema.Column> columns,
        dev.sqlcj.schema.Table table,
        Placeholders placeholders
    ) {
        ExpressionList<?> rows = values.getExpressions();

        if (rows instanceof ParenthesedExpressionList<?> singleRow) {
            resolveInsertValueRow(singleRow, columns, table, placeholders);

            return;
        }

        for (Expression element : rows) {
            if (!(element instanceof ParenthesedExpressionList<?> row)) {
                throw new UnsupportedOperationException(INSERT_ROW_SOURCE_REJECTION);
            }

            resolveInsertValueRow(row, columns, table, placeholders);
        }
    }

    /**
     * Resolves one {@code VALUES} row, each of whose values is typed from the
     * column it is written into. A value that binds no placeholder reaches the
     * database as written, so only its target column must exist, and a
     * placeholder repeated across rows is one parameter bound at each of its
     * positions.
     *
     * <p>A target written with a subscript inserts into one element of its
     * array column, so its value is not typed from that column, which must
     * still exist.
     */
    private void resolveInsertValueRow(
        ParenthesedExpressionList<?> row,
        ExpressionList<net.sf.jsqlparser.schema.Column> columns,
        dev.sqlcj.schema.Table table,
        Placeholders placeholders
    ) {
        if (row.size() != columns.size()) {
            throw new UnsupportedOperationException(INSERT_COUNT_REJECTION);
        }

        for (int index = 0; index < columns.size(); index++) {
            net.sf.jsqlparser.schema.Column target = columns.get(index);
            dev.sqlcj.schema.Column column = findColumn(table, target.getUnquotedColumnName());

            if (directColumn(target) != null) {
                resolveColumnValue(row.get(index), column, placeholders);
            }
        }
    }

    /**
     * Resolves the {@code SELECT} an insert reads its rows from, which is
     * analyzed by the rules of a nested read: its sources resolve against the
     * common table expressions in scope, and its join conditions, {@code WHERE},
     * and pagination clauses type their own placeholders. It sees no source of
     * the insert, as PostgreSQL scopes it, and it may omit {@code FROM}, which
     * states one row of values and no source at all.
     *
     * <p>Its items pair with the column list by position and must match its
     * count. An item that is a placeholder, or a cast of one, is the value of
     * its target column, so it is typed as a written value is; a direct column
     * only resolves against the select's own sources, whether or not the schema
     * maps its type, because it binds nothing; and every other item reaches the
     * database as written.
     */
    private void resolveInsertSelect(
        PlainSelect plainSelect,
        ExpressionList<net.sf.jsqlparser.schema.Column> columns,
        dev.sqlcj.schema.Table table,
        List<Source> commonTableExpressions,
        Analysis analysis
    ) {
        List<Source> sources = plainSelect.getFromItem() == null
            ? List.of()
            : resolveSources(plainSelect, analysis, commonTableExpressions, null);

        Scope scope = Scope.of(sources, commonTableExpressions);

        List<Expression> items = resolveInsertSelectItems(plainSelect, sources);

        if (items.size() != columns.size()) {
            throw new UnsupportedOperationException(INSERT_COUNT_REJECTION);
        }

        for (int index = 0; index < columns.size(); index++) {
            net.sf.jsqlparser.schema.Column target = columns.get(index);

            resolveInsertSelectItem(
                items.get(index),
                target,
                findColumn(table, target.getUnquotedColumnName()),
                scope,
                analysis.placeholders()
            );
        }

        resolveBindingParameters(plainSelect, scope, analysis);
    }

    /**
     * The items an insert's {@code SELECT} writes by position, where each
     * column a wildcard expands to is one item of its own, reported as
     * {@code null} because it states a column of a source rather than a
     * written value. A wildcard expands over the columns of each source in
     * declared order, and {@code qualifier.*} over the columns of that one
     * source, neither of which needs a mapped type, because nothing binds them.
     */
    private List<Expression> resolveInsertSelectItems(
        PlainSelect plainSelect,
        List<Source> sources
    ) {
        List<Expression> items = new ArrayList<>();

        for (SelectItem<?> selectItem : plainSelect.getSelectItems()) {
            Expression expression = selectItem.getExpression();

            if (expression instanceof AllTableColumns allTableColumns) {
                addWildcardItems(
                    items,
                    findSource(sources, allTableColumns.getTable().getUnquotedName())
                );

                continue;
            }

            if (expression instanceof AllColumns) {
                for (Source source : sources) {
                    addWildcardItems(items, source);
                }

                continue;
            }

            items.add(expression);
        }

        return items;
    }

    /** One wildcard item per column of the source the wildcard expands over. */
    private void addWildcardItems(List<Expression> items, Source source) {
        for (int index = 0; index < source.table().columns().size(); index++) {
            items.add(null);
        }
    }

    /**
     * Resolves one item of an insert's {@code SELECT} as the value of the
     * column it is written into. A wildcard's column states a column of a
     * source rather than a value, so it types nothing, and neither does a
     * direct column, which only resolves, nor a value of a subscripted target,
     * whose array column must still exist.
     */
    private void resolveInsertSelectItem(
        Expression item,
        net.sf.jsqlparser.schema.Column target,
        dev.sqlcj.schema.Column column,
        Scope scope,
        Placeholders placeholders
    ) {
        if (item == null) {
            return;
        }

        net.sf.jsqlparser.schema.Column selectedColumn = directColumn(item);

        if (selectedColumn != null) {
            resolveColumn(selectedColumn, scope);

            return;
        }

        if (directColumn(target) != null) {
            resolveColumnValue(item, column, placeholders);
        }
    }

    /**
     * Resolves the assignment parameters of an {@code UPDATE} or of an
     * {@code ON CONFLICT DO UPDATE} action in source order, which precedes any
     * parameter in the {@code WHERE} expression. An assigned value that binds
     * no placeholder reaches the database as written, so only its target
     * column must exist.
     *
     * <p>A target written with a subscript assigns one element of its array
     * column, so its value is not typed from that column, which must still
     * exist.
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

            net.sf.jsqlparser.schema.Column target = updateSet.getColumn(0);
            dev.sqlcj.schema.Column column = findColumn(table, target.getUnquotedColumnName());

            if (directColumn(target) != null) {
                resolveColumnValue(updateSet.getValue(0), column, placeholders);
            }
        }
    }

    /**
     * Resolves the conflict clause of an insert, whose placeholders are written
     * after the {@code VALUES} placeholders and therefore bind after them. A
     * {@code DO UPDATE ... WHERE} predicate reads both the stored row and the
     * proposed one, so its placeholders follow the {@code WHERE} rules over the
     * inserted table and {@code EXCLUDED} as two sources of the same columns,
     * which makes an unqualified column of either one ambiguous, as PostgreSQL
     * also reports it.
     *
     * <p>{@code DO NOTHING} decides the conflict by itself, so it needs no
     * target, while {@code DO UPDATE} must name the rows it updates.
     */
    private void resolveConflictParameters(
        Insert insert,
        Source source,
        List<Source> commonTableExpressions,
        Analysis analysis
    ) {
        InsertConflictTarget target = insert.getConflictTarget();
        InsertConflictAction action = insert.getConflictAction();

        if (target == null && action == null) {
            return;
        }

        boolean doUpdate = action != null
            && action.getConflictActionType() == ConflictActionType.DO_UPDATE;

        if (target != null) {
            resolveConflictTarget(target, source, commonTableExpressions, analysis);
        } else if (doUpdate) {
            throw new UnsupportedOperationException(
                "ON CONFLICT DO UPDATE requires a conflict target,"
                    + " such as ON CONFLICT (id) or ON CONFLICT ON CONSTRAINT name."
            );
        }

        if (!doUpdate) {
            return;
        }

        List<UpdateSet> updateSets = action.getUpdateSets();

        resolveUpdateSetParameters(updateSets, source.table(), analysis.placeholders());
        resolveExcludedColumns(updateSets, source.table());

        if (action.getWhereExpression() != null) {
            resolveParameters(
                action.getWhereExpression(),
                Scope.of(
                    List.of(source, new Source(EXCLUDED_QUALIFIER, source.table(), false, false)),
                    commonTableExpressions
                ),
                analysis
            );
        }
    }

    /**
     * Resolves the conflict target against the inserted table. Each element of
     * a target of plain column names is a column of that table, and the
     * target's predicate reads that table alone, under the name it exposes. A named
     * constraint, like the unique index a column-name target matches, is
     * resolved by PostgreSQL. An element that is an expression or carries a
     * collation or an operator class decides the conflict by something the
     * analyzed model does not describe.
     */
    private void resolveConflictTarget(
        InsertConflictTarget target,
        Source source,
        List<Source> commonTableExpressions,
        Analysis analysis
    ) {
        if (target.getConstraintName() != null) {
            return;
        }

        boolean columnNames = target.getIndexElements().stream().noneMatch(
            element -> element.isExpression()
                || element.getCollation() != null
                || element.getOperatorClass() != null
        );

        if (!columnNames) {
            throw new UnsupportedOperationException(
                "ON CONFLICT requires a target of column names, such as ON CONFLICT (id)."
            );
        }

        for (Index.ColumnParams element : target.getIndexElements()) {
            findColumn(source.table(), MultiPartName.unquote(element.getColumnName()));
        }

        if (target.getWhereExpression() != null) {
            resolveParameters(
                target.getWhereExpression(),
                Scope.of(List.of(source), commonTableExpressions),
                analysis
            );
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
     * Builds the analyzed model from the parameter occurrences resolved in
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
        List<QueryParameter> occurrences = resolveOccurrences(parsedSql, placeholders);

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
     * Resolves the parameter occurrences of one query in the textual order of
     * the placeholder occurrences the SQL parser reported, which is the JDBC
     * binding order of the generated {@code ?} positions.
     *
     * <p>An occurrence a clause of the query typed is that clause's parameter.
     * Every other occurrence is typed by the cast it is the direct operand of,
     * wherever in the statement it stands, and is named after its own
     * placeholder. A placeholder neither a clause nor a cast types has no type
     * to generate, so the first such occurrence in textual order is rejected
     * naming where the query writes it.
     */
    private List<QueryParameter> resolveOccurrences(ParsedSql parsedSql, Placeholders placeholders) {
        List<QueryParameter> occurrences = new ArrayList<>();

        for (PlaceholderOccurrence occurrence : parsedSql.placeholders()) {
            QueryParameter typed = placeholders.typed(occurrence.placeholder());

            if (typed != null) {
                occurrences.add(typed);

                continue;
            }

            CastPlaceholder cast = placeholders.ofCast(occurrence.cast());

            if (cast == null) {
                throw untypedPlaceholder(occurrence);
            }

            occurrences.add(
                toCastParameter(cast, indexedParameterName(cast.placeholder()))
            );
        }

        placeholders.requireReportedOccurrences();

        return occurrences;
    }

    /**
     * The rejection of one placeholder occurrence no clause of the query typed
     * and no cast states the type of, which the SQL parser reported together
     * with where the query writes it.
     */
    private UnsupportedOperationException untypedPlaceholder(PlaceholderOccurrence occurrence) {
        String spelled = spelledPlaceholder(occurrence.placeholder());

        return new UnsupportedOperationException(
            UNTYPED_PLACEHOLDER_REJECTION.formatted(spelled, occurrence.enclosing(), spelled)
        );
    }

    /**
     * One placeholder expression as the query spells it, which is its own name
     * for a named placeholder and its index for an indexed one. Every other
     * placeholder form is already rejected before a placeholder is spelled.
     */
    private String spelledPlaceholder(Expression placeholder) {
        if (placeholder instanceof JdbcNamedParameter named) {
            return NAME_PREFIX + named.getName();
        }

        return "$" + ((JdbcParameter) placeholder).getIndex();
    }

    /**
     * The occurrence the SQL parser reported for one placeholder expression of
     * the parsed source, which every placeholder of that source has.
     */
    private PlaceholderOccurrence occurrenceOf(ParsedSql parsedSql, Expression placeholder) {
        return parsedSql.placeholders().stream()
            .filter(occurrence -> occurrence.placeholder() == placeholder)
            .findFirst()
            .orElseThrow(
                () -> new IllegalStateException(
                    "A placeholder is not a reported placeholder occurrence."
                )
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

    /**
     * Resolves one query source: a subquery in the clause, a source that names
     * a common table expression in scope, which reads that expression rather
     * than the schema table of the same name as PostgreSQL resolves it, or a
     * table of the schema. A {@code LATERAL} source states a source this subset
     * does not model at all, and is classified first.
     */
    private Source resolveSource(
        FromItem fromItem,
        String unsupportedSourceRejection,
        Analysis analysis,
        List<Source> commonTableExpressions,
        boolean leftJoined
    ) {
        requireNoLateralSource(fromItem);

        if (fromItem instanceof ParenthesedSelect subquery) {
            return toSubquerySource(subquery, analysis, commonTableExpressions, leftJoined);
        }

        if (!(fromItem instanceof Table table)) {
            throw new UnsupportedOperationException(unsupportedSourceRejection);
        }

        Source commonTableExpression = findCommonTableExpression(table, commonTableExpressions);

        if (commonTableExpression != null) {
            return toDerivedSource(
                table.getAlias() == null
                    ? commonTableExpression.name()
                    : table.getAlias().getUnquotedName(),
                commonTableExpression.table().columns(),
                leftJoined
            );
        }

        return toSource(table, analysis.schema(), leftJoined);
    }

    /**
     * Rejects a {@code LATERAL} source, which reads the sources written before
     * it, so it is never reported as a join kind that could be rewritten.
     */
    private void requireNoLateralSource(FromItem fromItem) {
        if (fromItem instanceof LateralSubSelect) {
            throw new UnsupportedOperationException(LATERAL_REJECTION);
        }
    }

    /**
     * Resolves a subquery source into the derived source its own
     * {@code SELECT} exposes. The body sees only the common table expressions
     * in scope and no source of the query around it, as PostgreSQL scopes a
     * subquery source that is not {@code LATERAL}.
     *
     * <p>The alias is the only name the subquery's columns can be read through,
     * so it is required, and a column list on it would rename those columns by
     * position, which this subset does not model.
     */
    private Source toSubquerySource(
        ParenthesedSelect subquery,
        Analysis analysis,
        List<Source> commonTableExpressions,
        boolean leftJoined
    ) {
        Alias alias = subquery.getAlias();

        if (alias == null) {
            throw new UnsupportedOperationException(UNALIASED_FROM_SUBQUERY_REJECTION);
        }

        String name = alias.getUnquotedName();

        if (alias.getAliasColumns() != null && !alias.getAliasColumns().isEmpty()) {
            throw new UnsupportedOperationException(
                DERIVED_SOURCE_COLUMN_LIST_REJECTION.formatted(name)
            );
        }

        return toDerivedSource(
            name,
            analyzeNestedSelect(subquery.getSelect(), analysis, commonTableExpressions).columns(),
            leftJoined
        );
    }

    /**
     * The common table expression in scope one source names, and {@code null}
     * when the source names none. Only an unqualified name can name one, and
     * the innermost declaration of that name, compared ignoring case, is the
     * one the source reads.
     */
    private Source findCommonTableExpression(Table table, List<Source> commonTableExpressions) {
        if (table.getSchemaName() != null) {
            return null;
        }

        String name = table.getUnquotedName();

        for (int index = commonTableExpressions.size() - 1; index >= 0; index--) {
            Source commonTableExpression = commonTableExpressions.get(index);

            if (commonTableExpression.name().equalsIgnoreCase(name)) {
                return commonTableExpression;
            }
        }

        return null;
    }

    /**
     * One derived source — a common table expression or a subquery source —
     * under the name the query exposes it as, whose columns are the result
     * columns of its own {@code SELECT}. A left join of the source makes every
     * one of them nullable, as it does for a table source.
     */
    private Source toDerivedSource(
        String name,
        List<dev.sqlcj.schema.Column> columns,
        boolean leftJoined
    ) {
        requireDistinctDerivedColumnNames(name, columns);

        return new Source(
            name,
            new dev.sqlcj.schema.Table(name, columns, List.of()),
            true,
            leftJoined
        );
    }

    /**
     * Requires the columns of a derived source to expose distinct names,
     * compared ignoring case as a column reference resolves them, because a
     * repeated name makes every reference to it ambiguous.
     */
    private void requireDistinctDerivedColumnNames(
        String name,
        List<dev.sqlcj.schema.Column> columns
    ) {
        for (int index = 0; index < columns.size(); index++) {
            String columnName = columns.get(index).name();

            boolean repeated = columns.subList(0, index).stream()
                .anyMatch(column -> column.name().equalsIgnoreCase(columnName));

            if (repeated) {
                throw new UnsupportedOperationException(
                    DERIVED_SOURCE_COLUMN_NAME_REJECTION.formatted(name, columnName)
                );
            }
        }
    }

    /**
     * Resolves the ordered query sources from the first source followed by
     * every joined source, rejecting an exposed name that repeats in this
     * level. Each join condition resolves its columns through the sources up to
     * and including its own joined source and then through the levels enclosing
     * this one, which are none for a query no analyzed query encloses.
     */
    private List<Source> resolveSources(
        PlainSelect plainSelect,
        Analysis analysis,
        List<Source> commonTableExpressions,
        Scope enclosing
    ) {
        List<Source> sources = new ArrayList<>();

        addSource(
            sources,
            resolveSource(
                plainSelect.getFromItem(),
                "Only table sources are supported.",
                analysis,
                commonTableExpressions,
                false
            )
        );

        List<Join> joins = plainSelect.getJoins();

        if (joins == null) {
            return List.copyOf(sources);
        }

        for (Join join : joins) {
            addSource(sources, requireSupportedJoin(join, analysis, commonTableExpressions));

            resolveJoinConditionColumns(
                requireJoinCondition(join),
                new Scope(List.copyOf(sources), commonTableExpressions, enclosing)
            );
        }

        return List.copyOf(sources);
    }

    /**
     * Resolves the ordered sources of one write: its target, which its rows
     * belong to, followed by every source its {@code FROM} or {@code USING}
     * clause adds, in the order the query writes them. An exposed name that
     * repeats among them is rejected as it is in a read, the target included.
     *
     * <p>Each added item written beside the others — the single {@code FROM}
     * item, a comma-separated source after it, and each {@code USING} source —
     * starts a join group, and each joined source joins the group it is written
     * onto, because PostgreSQL binds a {@code JOIN} more tightly than a comma.
     * A join condition therefore resolves its columns and types its
     * placeholders over its own group up to and including its joined source,
     * which never includes the target, as PostgreSQL scopes a condition inside
     * {@code FROM}.
     */
    private List<Source> resolveWriteSources(
        Source target,
        List<FromItem> addedItems,
        List<Join> joins,
        Analysis analysis,
        List<Source> commonTableExpressions
    ) {
        List<Source> sources = new ArrayList<>();
        List<Source> joinGroup = new ArrayList<>();

        addSource(sources, target);

        for (FromItem addedItem : addedItems) {
            addWriteSource(
                sources,
                joinGroup,
                resolveSource(
                    addedItem,
                    WRITE_SOURCE_REJECTION,
                    analysis,
                    commonTableExpressions,
                    false
                )
            );
        }

        if (joins == null) {
            return List.copyOf(sources);
        }

        for (Join join : joins) {
            if (join.isSimple()) {
                addWriteSource(
                    sources,
                    joinGroup,
                    resolveSource(
                        join.getRightItem(),
                        WRITE_SOURCE_REJECTION,
                        analysis,
                        commonTableExpressions,
                        false
                    )
                );

                continue;
            }

            addJoinedWriteSource(
                sources,
                joinGroup,
                requireSupportedJoin(join, analysis, commonTableExpressions)
            );

            Expression condition = requireJoinCondition(join);
            Scope scope = Scope.of(List.copyOf(joinGroup), commonTableExpressions);

            resolveJoinConditionColumns(condition, scope);
            resolveParameters(condition, scope, analysis);
        }

        return List.copyOf(sources);
    }

    /**
     * Adds one source a write's {@code FROM} or {@code USING} clause writes
     * beside the others, which starts a join group of its own.
     */
    private void addWriteSource(List<Source> sources, List<Source> joinGroup, Source source) {
        addSource(sources, source);

        joinGroup.clear();
        joinGroup.add(source);
    }

    /**
     * Adds one source a write's {@code FROM} clause joins onto the group it is
     * written onto, which is the scope of its own join condition.
     */
    private void addJoinedWriteSource(List<Source> sources, List<Source> joinGroup, Source source) {
        addSource(sources, source);

        joinGroup.add(source);
    }

    private void addSource(List<Source> sources, Source source) {
        boolean duplicate = sources.stream()
            .anyMatch(existing -> existing.name().equalsIgnoreCase(source.name()));

        if (duplicate) {
            throw new IllegalArgumentException(
                "Duplicate source name in query: " + source.name()
            );
        }

        sources.add(source);
    }

    /** A write source, which always contributes a row of its own. */
    private Source toSource(Table table, Schema schema) {
        return toSource(table, schema, false);
    }

    private Source toSource(Table table, Schema schema, boolean leftJoined) {
        return new Source(
            table.getAlias() == null
                ? table.getUnquotedName()
                : table.getAlias().getUnquotedName(),
            findSourceTable(schema, table),
            false,
            leftJoined
        );
    }

    /**
     * The modeled table one query source names. sqlcj models one namespace, so
     * an unqualified and a {@code public}-qualified name are the same table,
     * and a name qualified with any other schema names a table sqlcj leaves
     * unmodeled and fails as a missing table.
     */
    private dev.sqlcj.schema.Table findSourceTable(Schema schema, Table table) {
        if (!SchemaNamespace.isModeled(table)) {
            throw new IllegalArgumentException(
                "Table not found in schema: " + SchemaNamespace.declaredName(table)
            );
        }

        return findTable(schema, table.getUnquotedName());
    }

    /**
     * Accepts only a bare {@code JOIN} or explicit {@code INNER JOIN}, or a
     * {@code LEFT JOIN} in its bare or {@code LEFT OUTER JOIN} spelling, of one
     * table or derived source. {@link Join#isInnerJoin()} and
     * {@link Join#isLeft()} also report shapes that this subset excludes, so
     * every excluded modifier is rejected explicitly.
     *
     * <p>A {@code LATERAL} joined source is classified first, because a source
     * this subset does not model cannot be written as a supported join at all,
     * and the join kinds that can be written another way are then reported with
     * that rewrite. Every remaining modifier keeps the general rejection.
     */
    private Source requireSupportedJoin(
        Join join,
        Analysis analysis,
        List<Source> commonTableExpressions
    ) {
        requireNoLateralSource(join.getRightItem());
        requireSupportedJoinKind(join);

        if (!isSupportedInnerJoin(join) && !isSupportedLeftJoin(join)) {
            throw new UnsupportedOperationException(
                "Only unmodified INNER JOIN and LEFT JOIN clauses are supported."
            );
        }

        return resolveSource(
            join.getRightItem(),
            "Only table join sources are supported.",
            analysis,
            commonTableExpressions,
            join.isLeft()
        );
    }

    /**
     * Rejects each join kind that joins its sources by a rule this subset does
     * not model, naming the join to write instead where one exists.
     */
    private void requireSupportedJoinKind(Join join) {
        if (join.isFull()) {
            throw new UnsupportedOperationException("FULL JOIN is not supported.");
        }

        if (join.isRight()) {
            throw new UnsupportedOperationException(
                "RIGHT JOIN is not supported; write a LEFT JOIN with the two sources swapped."
            );
        }

        if (join.isCross()) {
            throw new UnsupportedOperationException(
                "CROSS JOIN is not supported; write JOIN ... ON TRUE."
            );
        }

        if (join.isNatural()) {
            throw new UnsupportedOperationException(
                "NATURAL JOIN is not supported; write the join condition with ON."
            );
        }

        if (!join.getUsingColumns().isEmpty()) {
            throw new UnsupportedOperationException(
                "JOIN ... USING is not supported; write the join condition with ON."
            );
        }

        if (join.isSimple()) {
            throw new UnsupportedOperationException(
                "A comma-separated FROM list is not supported; write JOIN ... ON with the join condition."
            );
        }
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
     * The single {@code ON} condition of one join, which may be any boolean
     * expression. The parser reports no condition for a join written without
     * one and two conditions for the nested {@code JOIN a JOIN b ON ... ON ...}
     * form, neither of which states one condition for the joined source.
     */
    private Expression requireJoinCondition(Join join) {
        Collection<Expression> onExpressions = join.getOnExpressions();

        if (onExpressions.size() != 1) {
            throw new UnsupportedOperationException(
                "A join requires one ON condition."
            );
        }

        return onExpressions.iterator().next();
    }

    /**
     * Resolves every column reference an {@code ON} condition makes against the
     * sources introduced up to and including its own joined source, as
     * PostgreSQL scopes a join condition, so a reference to a later source of
     * the same level is an unknown qualifier, and then against each enclosing
     * level. A nested {@code SELECT} is not analyzed here, which the visitor's
     * default traversal already leaves unentered, so a subquery of the
     * condition is analyzed by the predicate rules alone.
     */
    private void resolveJoinConditionColumns(Expression condition, Scope scope) {
        condition.accept(
            new ExpressionVisitorAdapter<Void>() {

                @Override
                public <S> Void visit(net.sf.jsqlparser.schema.Column column, S context) {
                    resolveColumn(column, scope);

                    return null;
                }
            }
        );
    }

    /**
     * Resolves the parameters the clauses of a read type, which are the join
     * condition parameters, the {@code WHERE} parameters, and the pagination
     * parameters.
     */
    private void resolveBindingParameters(
        PlainSelect plainSelect,
        Scope scope,
        Analysis analysis
    ) {
        resolveJoinConditionParameters(plainSelect, scope, analysis);

        if (plainSelect.getWhere() != null) {
            resolveParameters(
                plainSelect.getWhere(),
                scope,
                analysis
            );
        }

        resolvePaginationParameters(plainSelect, analysis.placeholders());
    }

    /**
     * Resolves the parameters of each join's {@code ON} condition by the
     * {@code WHERE} predicate rules, over the sources introduced up to and
     * including that join's own source. Binding order comes from the textual
     * placeholder order the parser reported, not from this traversal.
     */
    private void resolveJoinConditionParameters(
        PlainSelect plainSelect,
        Scope scope,
        Analysis analysis
    ) {
        List<Join> joins = plainSelect.getJoins();

        if (joins == null) {
            return;
        }

        for (int index = 0; index < joins.size(); index++) {
            resolveParameters(
                requireJoinCondition(joins.get(index)),
                scope.narrowedTo(scope.sources().subList(0, index + 2)),
                analysis
            );
        }
    }

    /**
     * Resolves the pagination parameters, which are the {@code LIMIT} row count
     * and the {@code OFFSET} value. Either value binds one parameter named after
     * its own clause, or after the placeholder when the placeholder is named.
     * The parser stores {@code OFFSET a LIMIT b} exactly like
     * {@code LIMIT b OFFSET a}, so the two bind in the order the parser reported
     * their placeholders, not in the order they are resolved here.
     */
    private void resolvePaginationParameters(PlainSelect plainSelect, Placeholders placeholders) {
        Offset offset = plainSelect.getOffset();

        resolvePaginationValue(
            resolveLimitRowCount(plainSelect.getLimit()),
            LIMIT_PARAMETER_NAME,
            placeholders
        );

        resolvePaginationValue(
            offset == null
                ? null
                : offset.getOffset(),
            OFFSET_PARAMETER_NAME,
            placeholders
        );
    }

    /**
     * Resolves one pagination value: a placeholder, which counts rows and is
     * therefore an {@code INTEGER}, or a cast placeholder, which states its own
     * type and keeps its clause's name. Every other value reaches the database
     * as written.
     */
    private void resolvePaginationValue(
        Expression value,
        String name,
        Placeholders placeholders
    ) {
        Placeholder placeholder = placeholders.of(value);

        if (placeholder != null) {
            addParameter(placeholder, name, ColumnType.INTEGER, placeholders);

            return;
        }

        CastPlaceholder cast = placeholders.ofCast(value);

        if (cast != null) {
            addCastParameter(cast, name, placeholders);
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

    /**
     * Resolves the parameters one predicate types, over the level the predicate
     * stands in. A negated predicate states the same operands as the predicate
     * it negates, so the rules descend through {@code NOT} as they do through
     * {@code AND}, {@code OR}, and parentheses.
     *
     * <p>A subquery of {@code EXISTS}, which {@code NOT EXISTS} negates, is
     * analyzed as the level inside this one.
     */
    private void resolveParameters(
        Expression expression,
        Scope scope,
        Analysis analysis
    ) {
        if (expression instanceof AndExpression and) {
            resolveParameters(and.getLeftExpression(), scope, analysis);
            resolveParameters(and.getRightExpression(), scope, analysis);
            return;
        }

        if (expression instanceof OrExpression or) {
            resolveParameters(or.getLeftExpression(), scope, analysis);
            resolveParameters(or.getRightExpression(), scope, analysis);
            return;
        }

        if (expression instanceof NotExpression not) {
            resolveParameters(not.getExpression(), scope, analysis);
            return;
        }

        if (expression instanceof ParenthesedExpressionList<?> parentheses) {
            for (Expression nestedExpression : parentheses) {
                resolveParameters(
                    nestedExpression,
                    scope,
                    analysis
                );
            }
            return;
        }

        if (expression instanceof ExistsExpression exists) {
            if (exists.getRightExpression() instanceof ParenthesedSelect subquery) {
                analyzePredicateSubquery(subquery, scope, analysis);
            }

            return;
        }

        if (expression instanceof InExpression in) {
            resolveInExpression(in, scope, analysis);
            return;
        }

        if (expression instanceof LikeExpression like) {
            resolveLikeExpression(like, scope, analysis);
            return;
        }

        if (expression instanceof IsNullExpression isNull) {
            resolveIsNullExpression(isNull, scope);
            return;
        }

        if (expression instanceof Between between) {
            resolveBetweenExpression(between, scope, analysis);
            return;
        }

        if (expression instanceof ComparisonOperator comparison) {
            resolveParameterComparison(comparison, scope, analysis);
        }
    }

    /**
     * Resolves one {@code IN} or {@code NOT IN} predicate. A tested direct
     * column resolves against the level the predicate stands in, and a
     * membership query is analyzed as the level inside it, whatever the tested
     * value is, because the query states its own rows rather than values of
     * that column.
     */
    private void resolveInExpression(InExpression in, Scope scope, Analysis analysis) {
        net.sf.jsqlparser.schema.Column column = directColumn(in.getLeftExpression());

        if (in.getRightExpression() instanceof ParenthesedSelect subquery) {
            if (column != null) {
                resolveColumn(column, scope);
            }

            analyzePredicateSubquery(subquery, scope, analysis);

            return;
        }

        if (column == null) {
            return;
        }

        dev.sqlcj.schema.Column schemaColumn = resolveColumn(column, scope).column();

        Expression rightExpression = in.getRightExpression();

        if (rightExpression instanceof ExpressionList<?> expressionList) {
            for (Expression expression : expressionList) {
                resolveColumnValue(expression, schemaColumn, analysis.placeholders());
            }

            return;
        }

        resolveInExpression(
            rightExpression,
            schemaColumn,
            scope,
            analysis
        );
    }

    private void resolveInExpression(
        Expression expression,
        dev.sqlcj.schema.Column schemaColumn,
        Scope scope,
        Analysis analysis
    ) {
        Placeholders placeholders = analysis.placeholders();

        Placeholder placeholder = placeholders.of(expression);

        if (placeholder != null) {
            addParameter(placeholder, schemaColumn, placeholders);
            return;
        }

        if (expression instanceof AndExpression and) {
            resolveInExpression(
                and.getLeftExpression(),
                schemaColumn,
                scope,
                analysis
            );

            resolveInExpression(
                and.getRightExpression(),
                schemaColumn,
                scope,
                analysis
            );

            return;
        }

        if (expression instanceof OrExpression or) {
            resolveInExpression(
                or.getLeftExpression(),
                schemaColumn,
                scope,
                analysis
            );

            resolveInExpression(
                or.getRightExpression(),
                schemaColumn,
                scope,
                analysis
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
                        scope,
                        analysis
                    );
                }
            }

            return;
        }

        CastPlaceholder cast = placeholders.ofCast(expression);

        if (cast != null) {
            addCastParameter(cast, schemaColumn.name(), placeholders);
        }
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
        Scope scope,
        Analysis analysis
    ) {
        Placeholders placeholders = analysis.placeholders();

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

            net.sf.jsqlparser.schema.Column testedColumn = directColumn(left);

            if (testedColumn == null) {
                return;
            }

            dev.sqlcj.schema.Column schemaColumn = resolveColumn(testedColumn, scope).column();

            requireSupportedType(schemaColumn);

            addParameter(
                pattern,
                requireTextColumn(schemaColumn),
                placeholders
            );

            return;
        }

        CastPlaceholder castPattern = placeholders.ofCast(right);
        net.sf.jsqlparser.schema.Column testedColumn = directColumn(left);

        if (
            castPattern != null
                && unsupportedLikePatternReason(like) == null
                && testedColumn != null
        ) {
            addCastParameter(
                castPattern,
                resolveColumn(testedColumn, scope).column().name(),
                placeholders
            );
        }
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
     * tested value, so a tested value that is not a direct column is left to
     * the cast that types its own placeholder.
     */
    private void resolveIsNullExpression(IsNullExpression isNull, Scope scope) {
        Expression tested = isNull.getLeftExpression();

        if (tested instanceof net.sf.jsqlparser.schema.Column column) {
            resolveColumn(column, scope);
        }
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
        Scope scope,
        Analysis analysis
    ) {
        Placeholders placeholders = analysis.placeholders();

        Expression left = between.getLeftExpression();
        Expression start = between.getBetweenExpressionStart();
        Expression end = between.getBetweenExpressionEnd();

        if (placeholders.of(left) != null) {
            return;
        }

        net.sf.jsqlparser.schema.Column testedColumn = directColumn(left);

        if (testedColumn != null && (bindsValue(start, placeholders) || bindsValue(end, placeholders))) {
            dev.sqlcj.schema.Column schemaColumn = resolveColumn(testedColumn, scope).column();

            resolveColumnValue(start, schemaColumn, placeholders);
            resolveColumnValue(end, schemaColumn, placeholders);
        }
    }

    /**
     * Resolves the operands of one comparison, which names a parameter after
     * the column it compares. A placeholder compared with a column takes that
     * column's type, and a cast placeholder states its own type and keeps the
     * column's name. A placeholder inside the {@code = ANY(p)} form takes a
     * list of the compared column's values. Every other operand contributes
     * only the parameters its own cast placeholders state.
     *
     * <p>An operand that is a subquery states its own value rather than a
     * column's, so it is analyzed as the level inside the one the comparison
     * stands in and types no parameter of this comparison.
     */
    private void resolveParameterComparison(
        ComparisonOperator comparison,
        Scope scope,
        Analysis analysis
    ) {
        Placeholders placeholders = analysis.placeholders();

        Expression left = comparison.getLeftExpression();
        Expression right = comparison.getRightExpression();

        if (resolveComparisonSubqueries(left, right, scope, analysis)) {
            return;
        }

        Placeholder leftPlaceholder = placeholders.of(left);
        Placeholder rightPlaceholder = placeholders.of(right);

        net.sf.jsqlparser.schema.Column leftColumn = directColumn(left);
        net.sf.jsqlparser.schema.Column rightColumn = directColumn(right);

        if (leftColumn != null && rightPlaceholder != null) {
            addParameter(
                rightPlaceholder,
                resolveColumn(leftColumn, scope).column(),
                placeholders
            );
            return;
        }

        if (leftPlaceholder != null && rightColumn != null) {
            addParameter(
                leftPlaceholder,
                resolveColumn(rightColumn, scope).column(),
                placeholders
            );
            return;
        }

        if (comparison instanceof EqualsTo && leftColumn != null) {
            Expression listArgument = anyListArgument(right, placeholders);

            if (listArgument != null) {
                resolveAnyListParameter(listArgument, leftColumn, scope, placeholders);
                return;
            }
        }

        if (isColumnTypedComparison(comparison)) {
            if (leftColumn != null) {
                CastPlaceholder cast = placeholders.ofCast(right);

                if (cast != null) {
                    addCastParameter(
                        cast,
                        resolveColumn(leftColumn, scope).column().name(),
                        placeholders
                    );
                    return;
                }
            } else if (rightColumn != null) {
                CastPlaceholder cast = placeholders.ofCast(left);

                if (cast != null) {
                    addCastParameter(
                        cast,
                        resolveColumn(rightColumn, scope).column().name(),
                        placeholders
                    );
                }
            }
        }
    }

    /**
     * Analyzes each operand of one comparison that is a subquery, as the level
     * inside the one the comparison stands in, and reports whether the
     * comparison compares one. No parameter rule of a comparison reads such an
     * operand, so a comparison with one types no parameter of its own and
     * leaves a placeholder beside it to the cast that states its type.
     */
    private boolean resolveComparisonSubqueries(
        Expression left,
        Expression right,
        Scope scope,
        Analysis analysis
    ) {
        boolean subquery = false;

        if (left instanceof ParenthesedSelect leftSubquery) {
            analyzePredicateSubquery(leftSubquery, scope, analysis);

            subquery = true;
        }

        if (right instanceof ParenthesedSelect rightSubquery) {
            analyzePredicateSubquery(rightSubquery, scope, analysis);

            subquery = true;
        }

        return subquery;
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
     * The argument of the {@code ANY(p)} operand whose parameter is the list a
     * compared column's value is looked up in, and {@code null} when the
     * expression is not that operand. The operand is an unquoted, unqualified,
     * case-insensitive {@code ANY} with no modifier and with exactly one
     * argument that is itself one parameter's value, which is a placeholder or
     * a cast of one.
     *
     * <p>Every other spelling, modifier, and argument, such as a quoted
     * {@code "ANY"}, {@code pg_catalog.any}, an array constructor, and a
     * literal, is another expression, which binds no list here.
     */
    private Expression anyListArgument(Expression expression, Placeholders placeholders) {
        if (!(expression instanceof Function function)) {
            return null;
        }

        List<String> name = function.getMultipartName();

        if (name == null || name.size() != 1 || !name.get(0).equalsIgnoreCase("any")) {
            return null;
        }

        if (function.isDistinct() || function.getOrderByElements() != null) {
            return null;
        }

        ExpressionList<?> arguments = function.getParameters();

        if (arguments == null || arguments.size() != 1) {
            return null;
        }

        Expression argument = arguments.get(0);

        return bindsValue(argument, placeholders)
            ? argument
            : null;
    }

    /**
     * Resolves the list parameter of {@code <column> = ANY(p)}, which is named
     * after the compared column and is an array of that column's type, so that
     * the generated method takes one list and the runtime binds it as one
     * server array. A null list and an empty list match no row, as PostgreSQL
     * compares a value with no element of either.
     *
     * <p>A cast argument states its own type, as a cast placeholder does
     * anywhere else, and keeps the column's name, so only the column is
     * resolved for it.
     */
    private void resolveAnyListParameter(
        Expression argument,
        net.sf.jsqlparser.schema.Column column,
        Scope scope,
        Placeholders placeholders
    ) {
        dev.sqlcj.schema.Column schemaColumn = resolveColumn(column, scope).column();
        Placeholder placeholder = placeholders.of(argument);

        if (placeholder == null) {
            addCastParameter(
                placeholders.ofCast(argument),
                schemaColumn.name(),
                placeholders
            );
            return;
        }

        ColumnType type = requireSupportedType(schemaColumn);

        requireArrayElementColumn(schemaColumn);

        addParameter(
            placeholder,
            schemaColumn.name(),
            type,
            schemaColumn.enumType(),
            true,
            schemaColumn.blankPadded(),
            placeholders
        );
    }

    /**
     * Requires a column an array of whose values sqlcj binds, because the list
     * parameter of {@code = ANY} takes the compared column's type as its
     * element type. An array column's own values are not elements of its type,
     * and the element types the schema maps no array of have no array binding.
     */
    private dev.sqlcj.schema.Column requireArrayElementColumn(dev.sqlcj.schema.Column column) {
        if (column.array() || !columnTypeMapping.isArrayElementType(column.type())) {
            throw new UnsupportedOperationException(
                ("An = ANY placeholder requires a non-array column of a type other than BYTEA, "
                    + "JSON, and JSONB, but %s is %s.")
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
     * Resolves one value of a clause that names its parameter after a column: a
     * placeholder typed from the column, a cast placeholder that states its own
     * type and keeps the column's name, or an expression that is neither, whose
     * own cast placeholders are typed where they stand.
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
        }
    }

    /**
     * Reports whether an expression is itself the value of one parameter, which
     * is a placeholder or a cast of one.
     */
    private boolean bindsValue(Expression value, Placeholders placeholders) {
        return placeholders.of(value) != null || placeholders.ofCast(value) != null;
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
     * Records one parameter occurrence a cast types in the clause that names
     * it, which names it after the column whose value it is.
     */
    private void addCastParameter(
        CastPlaceholder cast,
        String name,
        Placeholders placeholders
    ) {
        placeholders.record(cast.placeholder(), toCastParameter(cast, name));
    }

    /** One parameter occurrence a cast types, under the given name. */
    private QueryParameter toCastParameter(CastPlaceholder cast, String name) {
        ColumnTypeMapping.MappedType type = cast.type();

        return toParameter(
            cast.placeholder(),
            name,
            type.type(),
            type.enumType(),
            type.array(),
            type.blankPadded()
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

    /** Records one parameter occurrence a clause of the query typed. */
    private void addParameter(
        Placeholder placeholder,
        String name,
        ColumnType type,
        String enumType,
        boolean array,
        boolean blankPadded,
        Placeholders placeholders
    ) {
        placeholders.record(
            placeholder,
            toParameter(placeholder, name, type, enumType, array, blankPadded)
        );
    }

    /**
     * One parameter occurrence, named after its placeholder when the placeholder
     * is named, and otherwise after the column or clause it belongs to.
     */
    private QueryParameter toParameter(
        Placeholder placeholder,
        String name,
        ColumnType type,
        String enumType,
        boolean array,
        boolean blankPadded
    ) {
        return new QueryParameter(
            placeholder.index(),
            placeholder.nameOr(name),
            type,
            enumType,
            array,
            blankPadded
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
                NAMED_PLACEHOLDER_REJECTION.formatted(uncompiled.get(0))
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
     *
     * <p>A bare placeholder projection is a placeholder a cast makes a
     * supported result column, so it is rejected as an untyped placeholder
     * rather than as an unsupported result expression.
     *
     * <p>A subscripted reference projects an element rather than the column, so
     * it is rejected as any other computed item is, and a cast states its type.
     *
     * <p>Beside the expanded columns, which are the columns a derived source
     * exposes, the projection carries the result components of a top-level
     * read, where a wildcard of a schema table that is not left-joined is one
     * embedded row rather than that table's own columns.
     */
    private ResolvedProjection resolveProjection(
        PlainSelect plainSelect,
        Analysis analysis,
        Scope scope
    ) {
        List<Source> sources = scope.sources();

        List<dev.sqlcj.schema.Column> columns = new ArrayList<>();
        List<QueryColumn> resultColumns = new ArrayList<>();

        for (SelectItem<?> selectItem : plainSelect.getSelectItems()) {
            Expression expression = selectItem.getExpression();

            if (expression instanceof AllTableColumns allTableColumns) {
                addWildcardColumns(
                    columns,
                    resultColumns,
                    findSource(
                        sources,
                        allTableColumns.getTable().getUnquotedName()
                    )
                );

                continue;
            }

            if (expression instanceof AllColumns) {
                for (Source source : sources) {
                    addWildcardColumns(columns, resultColumns, source);
                }

                continue;
            }

            dev.sqlcj.schema.Column column = resolveColumnItem(selectItem, analysis, scope);

            columns.add(column);
            resultColumns.add(toQueryColumn(column));
        }

        return new ResolvedProjection(columns, resultColumns);
    }

    /**
     * Adds one wildcard of one source to the expanded columns and to the result
     * components: the source's columns in schema order, and one embedded row of
     * the source's table in its own position.
     *
     * <p>A derived source has no row record, and a left-joined source's row may
     * be absent, so each contributes its expanded columns as result components
     * too.
     */
    private void addWildcardColumns(
        List<dev.sqlcj.schema.Column> columns,
        List<QueryColumn> resultColumns,
        Source source
    ) {
        List<dev.sqlcj.schema.Column> sourceColumns = resolveAllColumns(source);

        columns.addAll(sourceColumns);

        if (embedsRow(source)) {
            resultColumns.add(
                QueryColumn.row(
                    source.name(),
                    source.rowTable(),
                    toQueryColumns(sourceColumns)
                )
            );

            return;
        }

        resultColumns.addAll(toQueryColumns(sourceColumns));
    }

    /**
     * Reports whether a wildcard of one source selects the complete row of a
     * table the package generates a row record for, which a derived source has
     * none of and a left-joined source may contribute no row for.
     */
    private boolean embedsRow(Source source) {
        return !source.derived() && !source.leftJoined();
    }

    /**
     * Resolves one projection item that is not a wildcard into its single
     * column.
     */
    private dev.sqlcj.schema.Column resolveColumnItem(
        SelectItem<?> selectItem,
        Analysis analysis,
        Scope scope
    ) {
        Expression expression = selectItem.getExpression();

        net.sf.jsqlparser.schema.Column selectedColumn = directColumn(expression);

        if (selectedColumn != null) {
            ResolvedColumn resolved = resolveColumn(selectedColumn, scope);
            dev.sqlcj.schema.Column schemaColumn = resolved.column();

            return new dev.sqlcj.schema.Column(
                selectedColumnName(selectItem.getAlias(), schemaColumn),
                requireSupportedType(schemaColumn),
                schemaColumn.nullable() || resolved.source().leftJoined(),
                null,
                schemaColumn.enumType(),
                schemaColumn.array(),
                schemaColumn.blankPadded()
            );
        }

        if (expression instanceof Function function && isScalarCount(function)) {
            return scalarCountColumn(selectItem.getAlias());
        }

        if (expression instanceof CastExpression cast && cast.getColDataType() != null) {
            return castColumn(cast, selectItem.getAlias(), analysis.schema());
        }

        if (expression instanceof JdbcParameter || expression instanceof JdbcNamedParameter) {
            throw untypedPlaceholder(occurrenceOf(analysis.parsedSql(), expression));
        }

        throw new UnsupportedOperationException(
            COMPUTED_PROJECTION_REJECTION.formatted(written(expression))
        );
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

        if (name == null || name.size() != 1 || !name.get(0).equalsIgnoreCase("count")) {
            return false;
        }

        if (function.isDistinct()) {
            return false;
        }

        ExpressionList<?> arguments = function.getParameters();

        return arguments != null
            && arguments.size() == 1
            && arguments.get(0).getClass() == AllColumns.class;
    }

    /**
     * Resolves the scalar count projection into its single result column, which
     * is named after the required alias. The column is {@code BIGINT} because
     * {@code count(*)} returns {@code bigint}, and it is non-null because a
     * count is always a number, {@code 0} when no row matches.
     */
    private dev.sqlcj.schema.Column scalarCountColumn(Alias alias) {
        if (alias == null) {
            throw new UnsupportedOperationException(
                "COUNT(*) requires a result alias, such as COUNT(*) AS total."
            );
        }

        return new dev.sqlcj.schema.Column(alias.getUnquotedName(), ColumnType.BIGINT, false);
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
    private dev.sqlcj.schema.Column castColumn(CastExpression cast, Alias alias, Schema schema) {
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

        return new dev.sqlcj.schema.Column(
            name,
            type.type(),
            true,
            null,
            type.enumType(),
            type.array(),
            type.blankPadded()
        );
    }

    /**
     * Reports the table whose complete row a read returns, which is the table
     * of the one source a lone wildcard covers: {@code *} over a single source,
     * and {@code qualifier.*} beside any number of other sources. Every other
     * projection returns a result of its own, so it reports {@code null}.
     *
     * <p>The name is the schema's own spelling of the table, so a query-side
     * alias or spelling never splits one row identity.
     *
     * <p>A derived source names no table, so a read over one returns a result
     * specific to its own query even when the source is named like a table, and
     * a left-joined source's row may be absent, so a wildcard of one is read as
     * its own columns.
     */
    private String resolveSelectRowTable(PlainSelect plainSelect, List<Source> sources) {
        List<SelectItem<?>> selectItems = plainSelect.getSelectItems();

        if (selectItems.size() != 1) {
            return null;
        }

        Expression expression = selectItems.get(0).getExpression();

        if (expression instanceof AllTableColumns allTableColumns) {
            return wildcardRowTable(
                findSource(sources, allTableColumns.getTable().getUnquotedName())
            );
        }

        return expression instanceof AllColumns && sources.size() == 1
            ? wildcardRowTable(sources.get(0))
            : null;
    }

    /**
     * The table whose complete row a wildcard of one source returns, and
     * {@code null} for a source whose wildcard is read as its own columns.
     */
    private String wildcardRowTable(Source source) {
        return embedsRow(source)
            ? source.rowTable()
            : null;
    }

    /**
     * The analyzed result columns of one query, which carry the facts code
     * generation reads from a selected column.
     */
    private List<QueryColumn> toQueryColumns(List<dev.sqlcj.schema.Column> columns) {
        return columns.stream()
            .map(this::toQueryColumn)
            .toList();
    }

    /** The analyzed result column of one selected column. */
    private QueryColumn toQueryColumn(dev.sqlcj.schema.Column column) {
        return new QueryColumn(
            column.name(),
            column.type(),
            column.nullable(),
            column.enumType(),
            column.array()
        );
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
     * Reports the column one expression reads directly, and {@code null} when
     * the expression reads none. A reference written with a subscript or a
     * slice, such as {@code labels[1]} or {@code labels[1:2]}, reads one
     * element or one range of the column's array value rather than the column,
     * so it states neither the type nor the name of the column it reads, and
     * every rule that takes a type or a name from a column leaves it to the
     * rules of any other expression.
     */
    private net.sf.jsqlparser.schema.Column directColumn(Expression expression) {
        return expression instanceof net.sf.jsqlparser.schema.Column column
            && column.getArrayConstructor() == null
                ? column
                : null;
    }

    /**
     * Resolves one column reference against the levels in scope, innermost
     * first, as PostgreSQL resolves a reference inside a subquery.
     *
     * <p>A qualified reference resolves through the innermost source of that
     * exposed name. An unqualified reference resolves in the innermost level
     * that has the column and is ambiguous only within that level, so the same
     * column name in an enclosing level never makes a reference ambiguous. A
     * reference no level has names every visible source, from the innermost
     * level outward.
     */
    private ResolvedColumn resolveColumn(net.sf.jsqlparser.schema.Column column, Scope scope) {
        String columnName = column.getUnquotedColumnName();
        String qualifier = qualifier(column);

        if (qualifier != null) {
            Source source = findSource(scope, qualifier);

            return new ResolvedColumn(source, findColumn(source.table(), columnName));
        }

        for (Scope level = scope; level != null; level = level.enclosing()) {
            List<Source> matches = level.sources().stream()
                .filter(source -> lookupColumn(source.table(), columnName).isPresent())
                .toList();

            if (matches.size() > 1) {
                throw new IllegalArgumentException(
                    "Ambiguous column reference '%s' in sources: %s"
                        .formatted(columnName, sourceNames(matches))
                );
            }

            if (!matches.isEmpty()) {
                Source source = matches.get(0);

                return new ResolvedColumn(source, findColumn(source.table(), columnName));
            }
        }

        throw new IllegalArgumentException(
            "Column not found in sources %s: %s"
                .formatted(sourceNames(scope.visibleSources()), columnName)
        );
    }

    private String qualifier(net.sf.jsqlparser.schema.Column column) {
        Table table = column.getTable();

        return table == null || table.getName() == null
            ? null
            : table.getUnquotedName();
    }

    private Source findSource(List<Source> sources, String name) {
        return lookupSource(sources, name)
            .orElseThrow(() -> unknownSourceQualifier(name));
    }

    /**
     * The innermost source of one exposed name, which is the source a qualified
     * reference reads: a source of the level the reference stands in before one
     * of an enclosing level.
     */
    private Source findSource(Scope scope, String name) {
        for (Scope level = scope; level != null; level = level.enclosing()) {
            Optional<Source> source = lookupSource(level.sources(), name);

            if (source.isPresent()) {
                return source.get();
            }
        }

        throw unknownSourceQualifier(name);
    }

    private Optional<Source> lookupSource(List<Source> sources, String name) {
        return sources.stream()
            .filter(source -> source.name().equalsIgnoreCase(name))
            .findFirst();
    }

    private IllegalArgumentException unknownSourceQualifier(String name) {
        return new IllegalArgumentException("Unknown source qualifier in query: " + name);
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

    /**
     * The columns one source exposes in its own order, each nullable when the
     * source is left-joined, which is the expansion of a wildcard over that
     * source.
     */
    private List<dev.sqlcj.schema.Column> resolveAllColumns(Source source) {
        return source.table().columns().stream()
            .map(
                column -> new dev.sqlcj.schema.Column(
                    column.name(),
                    requireSupportedType(column),
                    column.nullable() || source.leftJoined(),
                    null,
                    column.enumType(),
                    column.array(),
                    column.blankPadded()
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
