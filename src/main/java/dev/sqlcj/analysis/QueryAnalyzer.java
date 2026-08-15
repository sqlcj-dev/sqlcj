package dev.sqlcj.analysis;

import dev.sqlcj.parser.Query;
import dev.sqlcj.schema.Schema;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
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
import java.util.List;

public final class QueryAnalyzer {

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

    private QueryModel analyzeSelect(
            Query query,
            Select select,
            Schema schema
    ) {
        PlainSelect plainSelect = select.getPlainSelect();
        Table table = getTable(plainSelect);

        List<QueryColumn> columns = resolveColumns(
                plainSelect,
                schema,
                table
        );

        List<QueryParameter> parameters = resolveParameters(plainSelect, schema, table);

        return new QueryModel(
                query.name(),
                query.type(),
                table.getName(),
                columns,
                parameters
        );
    }

    private Table getTable(PlainSelect plainSelect) {
        if (!(plainSelect.getFromItem() instanceof Table table)) {
            throw new UnsupportedOperationException("Only table sources are supported.");
        }

        return table;
    }

    private List<QueryParameter> resolveParameters(
            PlainSelect plainSelect,
            Schema schema,
            Table table
    ) {
        if (plainSelect.getWhere() == null) {
            return List.of();
        }

        dev.sqlcj.schema.Table schemaTable =
                findTable(schema, table.getName());

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

        if (expression instanceof EqualsTo equalsTo) {
            resolveParameterComparison(
                    equalsTo.getLeftExpression(),
                    equalsTo.getRightExpression(),
                    table,
                    parameters
            );
        }
    }

    private void resolveParameterComparison(
            Expression left,
            Expression right,
            dev.sqlcj.schema.Table table,
            List<QueryParameter> parameters
    ) {
        if (left instanceof net.sf.jsqlparser.schema.Column column
                && right instanceof JdbcParameter parameter) {

            addParameter(
                    parameter,
                    column.getColumnName(),
                    table,
                    parameters
            );
            return;
        }

        if (left instanceof JdbcParameter parameter
                && right instanceof net.sf.jsqlparser.schema.Column column) {

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
        dev.sqlcj.schema.Column column =
                findColumn(table, columnName);

        parameters.add(
                new QueryParameter(
                        parameter.getIndex(),
                        column.type()
                )
        );
    }

    private List<QueryColumn> resolveColumns(
            PlainSelect plainSelect,
            Schema schema,
            Table table
    ) {
        dev.sqlcj.schema.Table schemaTable =
                findTable(schema, table.getName());

        List<QueryColumn> columns = new ArrayList<>();

        for (SelectItem<?> selectItem : plainSelect.getSelectItems()) {
            if (selectItem.getExpression() instanceof AllColumns) {
                columns.addAll(resolveAllColumns(schemaTable));
                continue;
            }

            if (selectItem.getExpression()
                    instanceof net.sf.jsqlparser.schema.Column column) {

                dev.sqlcj.schema.Column schemaColumn =
                        findColumn(schemaTable, column.getColumnName());

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

    private dev.sqlcj.schema.Table findTable(
            Schema schema,
            String tableName
    ) {
        return schema.tables().stream()
                .filter(table -> table.name().equalsIgnoreCase(tableName))
                .findFirst()
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Table not found in schema: " + tableName
                        )
                );
    }

    private List<QueryColumn> resolveAllColumns(
            dev.sqlcj.schema.Table table
    ) {
        return table.columns().stream()
                .map(column -> new QueryColumn(
                        column.name(),
                        column.type(),
                        column.nullable()
                ))
                .toList();
    }

    private dev.sqlcj.schema.Column findColumn(
            dev.sqlcj.schema.Table table,
            String columnName
    ) {
        return table.columns().stream()
                .filter(column -> column.name().equalsIgnoreCase(columnName))
                .findFirst()
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Column not found in table "
                                        + table.name()
                                        + ": "
                                        + columnName
                        )
                );
    }
}
