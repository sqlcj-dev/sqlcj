package dev.sqlcj.schema.parser;

import dev.sqlcj.schema.Column;
import dev.sqlcj.schema.ColumnType;
import dev.sqlcj.schema.Constraint;
import dev.sqlcj.schema.ConstraintType;
import dev.sqlcj.schema.Schema;
import dev.sqlcj.schema.Table;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.MultiPartName;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.create.table.CheckConstraint;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.ForeignKeyIndex;
import net.sf.jsqlparser.statement.create.table.Index;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class DefaultSchemaParser implements SchemaParser {

    /** One parenthesized type argument group, such as {@code (10, 2)}. */
    private static final Pattern TYPE_ARGUMENTS = Pattern.compile("\\([^)]*\\)");

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
        String tableName = createTable.getTable().getUnquotedName();

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
        String typeName = typeName(definition);

        return new Column(
            columnName(definition),
            columnType(typeName),
            !isSerial(typeName) && isNullable(definition)
        );
    }

    /**
     * Returns the canonical column name without SQL identifier delimiters.
     */
    private String columnName(ColumnDefinition definition) {
        return MultiPartName.unquote(definition.getColumnName());
    }

    /**
     * Returns the canonical spelling of a declared SQL type: upper case,
     * without type arguments such as a length or a precision, and with single
     * spaces between the remaining words.
     */
    private String typeName(ColumnDefinition definition) {
        String dataType = definition.getColDataType()
            .getDataType()
            .toUpperCase(Locale.ROOT);

        return TYPE_ARGUMENTS
            .matcher(dataType)
            .replaceAll(" ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private ColumnType columnType(String typeName) {
        return switch (typeName) {
            case "INTEGER", "INT", "SERIAL" -> ColumnType.INTEGER;
            case "BIGINT", "BIGSERIAL" -> ColumnType.BIGINT;
            case "SMALLINT" -> ColumnType.SMALLINT;
            case "BOOLEAN", "BOOL" -> ColumnType.BOOLEAN;
            case "VARCHAR" -> ColumnType.VARCHAR;
            case "TEXT" -> ColumnType.TEXT;
            case "DATE" -> ColumnType.DATE;
            case "TIMESTAMP" -> ColumnType.TIMESTAMP;
            case "TIMESTAMP WITH TIME ZONE" -> ColumnType.TIMESTAMP_WITH_TIME_ZONE;
            case "DECIMAL", "NUMERIC" -> ColumnType.DECIMAL;
            case "UUID" -> ColumnType.UUID;
            default -> throw new UnsupportedOperationException(
                "Unsupported SQL column type: " + typeName
            );
        };
    }

    /**
     * PostgreSQL defines the serial spellings as an integer type with a
     * sequence default and {@code NOT NULL}, so such a column is never
     * nullable.
     */
    private boolean isSerial(String typeName) {
        return "SERIAL".equals(typeName) || "BIGSERIAL".equals(typeName);
    }

    private boolean isNullable(ColumnDefinition definition) {
        List<String> specs = definition.getColumnSpecs();

        if (specs == null) {
            return true;
        }

        for (int i = 0; i < specs.size() - 1; i++) {
            if ("NOT".equalsIgnoreCase(specs.get(i)) && "NULL".equalsIgnoreCase(specs.get(i + 1))) {
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

            if ("PRIMARY".equalsIgnoreCase(spec) && i + 1 < specs.size() && "KEY".equalsIgnoreCase(specs.get(i + 1))) {

                constraints.add(
                    new Constraint(
                        ConstraintType.PRIMARY_KEY,
                        List.of(columnName(definition))
                    )
                );
            }

            if ("UNIQUE".equalsIgnoreCase(spec)) {
                constraints.add(
                    new Constraint(
                        ConstraintType.UNIQUE,
                        List.of(columnName(definition))
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
            if (isIgnoredTableConstraint(index)) {
                continue;
            }

            constraints.add(
                new Constraint(
                    constraintType(index),
                    index.getColumnsNames().stream()
                        .map(MultiPartName::unquote)
                        .toList()
                )
            );
        }

        return constraints;
    }

    /**
     * Foreign key and check constraints are accepted but not modeled, because
     * they do not affect the generated Java types.
     */
    private boolean isIgnoredTableConstraint(Index index) {
        return index instanceof ForeignKeyIndex || index instanceof CheckConstraint;
    }

    private ConstraintType constraintType(Index index) {
        String indexType = index.getType();

        String declaredType = indexType == null
            ? ""
            : indexType.toUpperCase(Locale.ROOT);

        return switch (declaredType) {
            case "PRIMARY KEY" -> ConstraintType.PRIMARY_KEY;
            case "UNIQUE" -> ConstraintType.UNIQUE;
            default -> throw new UnsupportedOperationException(
                "Unsupported table constraint: " + indexType
            );
        };
    }
}
