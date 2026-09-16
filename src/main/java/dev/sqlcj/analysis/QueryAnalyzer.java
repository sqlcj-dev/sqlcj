package dev.sqlcj.analysis;

import dev.sqlcj.parser.Query;
import dev.sqlcj.schema.Schema;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.update.Update;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

public final class QueryAnalyzer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\d+");

    public QueryModel analyze(Query query, Statement statement, Schema schema) {
        if (statement instanceof Select select) {
            return analyzeSelect(query, select, schema);
        }

        if (statement instanceof Insert) {
            throw new UnsupportedOperationException("INSERT is not supported yet");
        }

        if (statement instanceof Update) {
            throw new UnsupportedOperationException("UPDATE is not supported yet");
        }

        if (statement instanceof Delete) {
            throw new UnsupportedOperationException("DELETE is not supported yet");
        }

        throw new UnsupportedOperationException(
            statement.getClass().getSimpleName()
        );
    }

    private QueryModel analyzeSelect(Query query, Select select, Schema schema) {
        PlainSelect plainSelect = select.getPlainSelect();
        Table table = getTable(plainSelect);

        List<QueryColumn> columns = resolveColumns(
            plainSelect,
            schema,
            table
        );

        List<QueryParameter> bindingParameters = resolveBindingParameters(plainSelect, schema, table);

        List<Integer> bindingParameterIndexes = bindingParameters.stream()
            .map(QueryParameter::index)
            .toList();

        List<QueryParameter> parameters = bindingParameters.stream()
            .sorted(Comparator.comparingInt(QueryParameter::index))
            .toList();

        return new QueryModel(
            query.name(),
            query.type(),
            table.getName(),
            toExecutableSql(query.sql()),
            bindingParameterIndexes,
            columns,
            parameters
        );
    }

    private String toExecutableSql(String sql) {
        return PLACEHOLDER.matcher(sql).replaceAll("?");
    }

    private Table getTable(PlainSelect plainSelect) {
        if (!(plainSelect.getFromItem() instanceof Table table)) {
            throw new UnsupportedOperationException("Only table sources are supported.");
        }

        return table;
    }

    /**
     * Resolves the supported parameters in the textual order in which they are
     * encountered, which is the JDBC binding order of the generated {@code ?}
     * positions.
     */
    private List<QueryParameter> resolveBindingParameters(PlainSelect plainSelect, Schema schema, Table table) {
        if (plainSelect.getWhere() == null) {
            return List.of();
        }

        dev.sqlcj.schema.Table schemaTable = findTable(schema, table.getName());

        List<QueryParameter> parameters = new ArrayList<>();

        resolveParameters(
            plainSelect.getWhere(),
            schemaTable,
            parameters
        );

        return parameters;
    }

    private void resolveParameters(
        Expression expression,
        dev.sqlcj.schema.Table table,
        List<QueryParameter> parameters
    ) {
        if (expression instanceof AndExpression and) {
            resolveParameters(and.getLeftExpression(), table, parameters);
            resolveParameters(and.getRightExpression(), table, parameters);
            return;
        }

        if (expression instanceof OrExpression or) {
            resolveParameters(or.getLeftExpression(), table, parameters);
            resolveParameters(or.getRightExpression(), table, parameters);
            return;
        }

        if (expression instanceof ParenthesedExpressionList<?> parentheses) {
            for (Expression nestedExpression : parentheses) {
                resolveParameters(
                    nestedExpression,
                    table,
                    parameters
                );
            }
            return;
        }

        if (expression instanceof InExpression in) {
            resolveInExpression(in, table, parameters);
            return;
        }

        if (expression instanceof ComparisonOperator comparison) {
            resolveParameterComparison(
                comparison.getLeftExpression(),
                comparison.getRightExpression(),
                table,
                parameters
            );
        }
    }

    private void resolveInExpression(InExpression in, dev.sqlcj.schema.Table table, List<QueryParameter> parameters) {
        if (!(in.getLeftExpression() instanceof net.sf.jsqlparser.schema.Column column)) {
            return;
        }

        dev.sqlcj.schema.Column schemaColumn = findColumn(table, column.getColumnName());

        Expression rightExpression = in.getRightExpression();

        if (rightExpression instanceof ExpressionList<?> expressionList) {
            for (Expression expression : expressionList) {
                if (expression instanceof JdbcParameter parameter) {
                    addParameter(parameter, schemaColumn, parameters);
                }
            }

            return;
        }

        resolveInExpression(
            rightExpression,
            schemaColumn,
            table,
            parameters
        );
    }

    private void resolveInExpression(
        Expression expression,
        dev.sqlcj.schema.Column schemaColumn,
        dev.sqlcj.schema.Table table,
        List<QueryParameter> parameters
    ) {
        if (expression instanceof JdbcParameter parameter) {
            addParameter(parameter, schemaColumn, parameters);
            return;
        }

        if (expression instanceof AndExpression and) {
            resolveInExpression(
                and.getLeftExpression(),
                schemaColumn,
                table,
                parameters
            );

            resolveInExpression(
                and.getRightExpression(),
                schemaColumn,
                table,
                parameters
            );

            return;
        }

        if (expression instanceof OrExpression or) {
            resolveInExpression(
                or.getLeftExpression(),
                schemaColumn,
                table,
                parameters
            );

            resolveInExpression(
                or.getRightExpression(),
                schemaColumn,
                table,
                parameters
            );

            return;
        }

        if (expression instanceof ParenthesedExpressionList<?> expressionList) {
            for (Expression nestedExpression : expressionList) {
                if (nestedExpression instanceof JdbcParameter parameter) {
                    addParameter(parameter, schemaColumn, parameters);
                } else {
                    resolveParameters(
                        nestedExpression,
                        table,
                        parameters
                    );
                }
            }
        }
    }

    private void resolveParameterComparison(
        Expression left,
        Expression right,
        dev.sqlcj.schema.Table table,
        List<QueryParameter> parameters
    ) {
        if (
            left instanceof net.sf.jsqlparser.schema.Column column
                && right instanceof JdbcParameter parameter
        ) {
            addParameter(
                parameter,
                column.getColumnName(),
                table,
                parameters
            );
            return;
        }

        if (
            left instanceof JdbcParameter parameter
                && right instanceof net.sf.jsqlparser.schema.Column column
        ) {
            addParameter(
                parameter,
                column.getColumnName(),
                table,
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
        dev.sqlcj.schema.Column column = findColumn(table, columnName);

        parameters.add(
            new QueryParameter(
                parameter.getIndex(),
                column.name(),
                column.type()
            )
        );
    }

    private void addParameter(
        JdbcParameter parameter,
        dev.sqlcj.schema.Column column,
        List<QueryParameter> parameters
    ) {
        parameters.add(
            new QueryParameter(
                parameter.getIndex(),
                column.name(),
                column.type()
            )
        );
    }

    private List<QueryColumn> resolveColumns(PlainSelect plainSelect, Schema schema, Table table) {
        dev.sqlcj.schema.Table schemaTable = findTable(schema, table.getName());

        List<QueryColumn> columns = new ArrayList<>();

        for (SelectItem<?> selectItem : plainSelect.getSelectItems()) {
            if (selectItem.getExpression() instanceof AllColumns) {
                columns.addAll(resolveAllColumns(schemaTable));
                continue;
            }

            if (selectItem.getExpression() instanceof net.sf.jsqlparser.schema.Column column) {
                dev.sqlcj.schema.Column schemaColumn = findColumn(schemaTable, column.getColumnName());

                columns.add(
                    new QueryColumn(
                        schemaColumn.name(),
                        schemaColumn.type(),
                        schemaColumn.nullable()
                    )
                );

                continue;
            }

            throw new UnsupportedOperationException(
                "Unsupported SELECT expression: "
                    + selectItem.getExpression().getClass().getSimpleName()
            );
        }

        return columns;
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

    private List<QueryColumn> resolveAllColumns(dev.sqlcj.schema.Table table) {
        return table.columns().stream()
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
        return table.columns().stream()
            .filter(column -> column.name().equalsIgnoreCase(columnName))
            .findFirst()
            .orElseThrow(
                () -> new IllegalArgumentException(
                    "Column not found in table "
                        + table.name()
                        + ": "
                        + columnName
                )
            );
    }
}
