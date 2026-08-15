package dev.sqlcj.schema.parser;

import dev.sqlcj.schema.Column;
import dev.sqlcj.schema.ColumnType;
import dev.sqlcj.schema.Constraint;
import dev.sqlcj.schema.ConstraintType;
import dev.sqlcj.schema.Schema;
import dev.sqlcj.schema.Table;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.Index;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DefaultSchemaParser implements SchemaParser {

    @Override
    public Schema parse(String sql) {
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sql);

            List<Table> tables = new ArrayList<>();

            for (Statement statement : statements) {
                if (!(statement instanceof CreateTable createTable)) {
                    throw new UnsupportedOperationException(
                            "Unsupported schema statement: "
                                    + statement.getClass().getSimpleName()
                    );
                }

                tables.add(parseTable(createTable));
            }

            return new Schema(tables);
        } catch (JSQLParserException e) {
            throw new SchemaParseException("Failed to parse schema", e);
        }
    }

    private Table parseTable(CreateTable createTable) {
        String tableName = createTable.getTable().getName();

        List<Column> columns = new ArrayList<>();
        List<Constraint> constraints = new ArrayList<>();

        for (ColumnDefinition definition : createTable.getColumnDefinitions()) {
            columns.add(parseColumn(definition));
            constraints.addAll(parseColumnConstraints(definition));
        }

        constraints.addAll(parseTableConstraints(createTable));

        return new Table(
                tableName,
                columns,
                constraints
        );
    }

    private Column parseColumn(ColumnDefinition definition) {
        String name = definition.getColumnName();
        ColumnType type = parseColumnType(definition);
        boolean nullable = isNullable(definition);

        return new Column(
                name,
                type,
                nullable
        );
    }

    private ColumnType parseColumnType(ColumnDefinition definition) {
        String typeName = definition.getColDataType()
                .getDataType()
                .toUpperCase(Locale.ROOT);

        int parenthesisIndex = typeName.indexOf('(');

        if (parenthesisIndex >= 0) {
            typeName = typeName.substring(0, parenthesisIndex).trim();
        }

        return switch (typeName) {
            case "INTEGER", "INT" -> ColumnType.INTEGER;
            case "BIGINT" -> ColumnType.BIGINT;
            case "SMALLINT" -> ColumnType.SMALLINT;
            case "BOOLEAN", "BOOL" -> ColumnType.BOOLEAN;
            case "VARCHAR" -> ColumnType.VARCHAR;
            case "TEXT" -> ColumnType.TEXT;
            case "DATE" -> ColumnType.DATE;
            case "TIMESTAMP" -> ColumnType.TIMESTAMP;
            case "DECIMAL", "NUMERIC" -> ColumnType.DECIMAL;
            default -> throw new UnsupportedOperationException(
                    "Unsupported SQL column type: " + typeName
            );
        };
    }

    private boolean isNullable(ColumnDefinition definition) {
        List<String> specs = definition.getColumnSpecs();

        if (specs == null) {
            return true;
        }

        for (int i = 0; i < specs.size() - 1; i++) {
            if ("NOT".equalsIgnoreCase(specs.get(i))
                    && "NULL".equalsIgnoreCase(specs.get(i + 1))) {
                return false;
            }
        }

        return true;
    }

    private List<Constraint> parseColumnConstraints(ColumnDefinition definition) {
        List<String> specs = definition.getColumnSpecs();

        if (specs == null) {
            return List.of();
        }

        List<Constraint> constraints = new ArrayList<>();

        for (int i = 0; i < specs.size(); i++) {
            String spec = specs.get(i);

            if ("PRIMARY".equalsIgnoreCase(spec)
                    && i + 1 < specs.size()
                    && "KEY".equalsIgnoreCase(specs.get(i + 1))) {

                constraints.add(
                        new Constraint(
                                ConstraintType.PRIMARY_KEY,
                                List.of(definition.getColumnName())
                        )
                );
            }

            if ("UNIQUE".equalsIgnoreCase(spec)) {
                constraints.add(
                        new Constraint(
                                ConstraintType.UNIQUE,
                                List.of(definition.getColumnName())
                        )
                );
            }
        }

        return constraints;
    }

    private List<Constraint> parseTableConstraints(CreateTable createTable) {
        List<Index> indexes = createTable.getIndexes();

        if (indexes == null) {
            return List.of();
        }

        List<Constraint> constraints = new ArrayList<>();

        for (Index index : indexes) {
            String indexType = index.getType();

            ConstraintType type = switch (indexType.toUpperCase(Locale.ROOT)) {
                case "PRIMARY KEY" -> ConstraintType.PRIMARY_KEY;
                case "UNIQUE" -> ConstraintType.UNIQUE;
                default -> throw new UnsupportedOperationException(
                        "Unsupported table constraint: " + indexType
                );
            };

            constraints.add(
                    new Constraint(
                            type,
                            index.getColumnsNames()
                    )
            );
        }

        return constraints;
    }
}
