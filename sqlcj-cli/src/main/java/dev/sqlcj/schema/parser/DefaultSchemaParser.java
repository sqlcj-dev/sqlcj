package dev.sqlcj.schema.parser;

import dev.sqlcj.schema.Column;
import dev.sqlcj.schema.ColumnType;
import dev.sqlcj.schema.Constraint;
import dev.sqlcj.schema.ConstraintType;
import dev.sqlcj.schema.EnumType;
import dev.sqlcj.schema.Schema;
import dev.sqlcj.schema.Table;
import dev.sqlcj.sql.SqlParseReason;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.parser.CCJSqlParser;
import net.sf.jsqlparser.parser.CCJSqlParserConstants;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.parser.Token;
import net.sf.jsqlparser.schema.MultiPartName;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.UnsupportedStatement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.alter.AlterOperation;
import net.sf.jsqlparser.statement.alter.AlterType;
import net.sf.jsqlparser.statement.alter.sequence.AlterSequence;
import net.sf.jsqlparser.statement.comment.Comment;
import net.sf.jsqlparser.statement.create.extension.CreateExtension;
import net.sf.jsqlparser.statement.create.function.CreateFunction;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import net.sf.jsqlparser.statement.create.sequence.CreateSequence;
import net.sf.jsqlparser.statement.create.table.CheckConstraint;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.ForeignKeyIndex;
import net.sf.jsqlparser.statement.create.table.Index;
import net.sf.jsqlparser.statement.create.trigger.CreateTrigger;
import net.sf.jsqlparser.statement.create.type.CreateType;
import net.sf.jsqlparser.statement.create.type.EnumTypeDefinition;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.grant.Grant;
import net.sf.jsqlparser.statement.grant.Revoke;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.update.Update;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

public class DefaultSchemaParser implements SchemaParser {

    /** One parenthesized type argument group, such as {@code (10, 2)}. */
    private static final Pattern TYPE_ARGUMENTS = Pattern.compile("\\([^)]*\\)");

    /**
     * The opening words of an {@code ALTER INDEX} statement, which the parser
     * reports only as an opaque unsupported statement.
     */
    private static final Pattern ALTER_INDEX = Pattern.compile(
        "^ALTER\\s+INDEX\\b",
        Pattern.CASE_INSENSITIVE
    );

    @Override
    public Schema parse(Schema schema, String sql) {
        AtomicReference<CCJSqlParser> parser = new AtomicReference<>();

        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sql, parser::set);

            List<Table> tables = new ArrayList<>(schema.tables());
            List<EnumType> enums = new ArrayList<>(schema.enums());

            if (!statements.isEmpty()) {
                List<Integer> lines = statementLines(parser.get());

                for (int i = 0; i < statements.size(); i++) {
                    apply(statements.get(i), statementLine(lines, i), tables, enums);
                }
            }

            return new Schema(tables, enums);
        } catch (JSQLParserException e) {
            throw new SchemaParseException(SqlParseReason.of(e, true), e);
        }
    }

    /**
     * The line each statement of one parsed source begins on, in order.
     *
     * <p>The parser reports no position per statement, so the lines are read
     * from the token chain of its parse tree: the first token, and the first
     * token after each statement separator, begins a statement. A comment is a
     * special token outside the chain and a dollar-quoted function body is one
     * literal token, so neither contributes a separator.
     */
    private List<Integer> statementLines(CCJSqlParser parser) {
        List<Integer> lines = new ArrayList<>();

        Token token = parser.getASTRoot().jjtGetFirstToken();
        boolean starting = true;

        while (token != null && token.kind != CCJSqlParserConstants.EOF) {
            if (token.kind == CCJSqlParserConstants.ST_SEMICOLON) {
                starting = true;
            } else {
                if (starting) {
                    lines.add(token.beginLine);
                }

                starting = false;
            }

            token = token.next;
        }

        return lines;
    }

    /**
     * The line of the statement at {@code index}, clamped to the last line
     * found. The clamp only covers a source whose statements are not separated
     * by semicolons, which PostgreSQL itself rejects.
     */
    private int statementLine(List<Integer> lines, int index) {
        return lines.get(Math.min(index, lines.size() - 1));
    }

    /**
     * Applies one statement to the tables and enum types the statements before
     * it left.
     */
    private void apply(Statement statement, int line, List<Table> tables, List<EnumType> enums) {
        if (statement instanceof CreateTable createTable) {
            applyCreateTable(createTable, enums, tables);
        } else if (statement instanceof Alter alter) {
            applyAlterTable(alter, line, enums, tables);
        } else if (statement instanceof Drop drop && drop.getObjectType() == Drop.ObjectType.TABLE) {
            applyDropTable(drop, tables);
        } else if (
            statement instanceof CreateType createType
                && createType.getDefinition() instanceof EnumTypeDefinition definition
        ) {
            applyCreateEnumType(createType, definition, enums);
        } else if (
            statement instanceof AlterType alterType
                && alterType.getAction() == AlterType.Action.ADD_VALUE
        ) {
            applyAddEnumValue(alterType, enums);
        } else if (!isIgnored(statement)) {
            throw unsupportedStatement(statement, line);
        }
    }

    /**
     * Appends one enum type with its declared labels. A repeated type name
     * fails, as PostgreSQL rejects it, and so does a repeated label. An enum is
     * the only type sqlcj models; every other {@code CREATE TYPE} is rejected.
     */
    private void applyCreateEnumType(
        CreateType createType,
        EnumTypeDefinition definition,
        List<EnumType> enums
    ) {
        String typeName = MultiPartName.unquote(createType.getName());

        if (indexOfEnum(enums, typeName) >= 0) {
            throw typeAlreadyExists(typeName);
        }

        List<String> labels = new ArrayList<>();

        for (StringValue declaredLabel : definition.getLabels()) {
            String label = declaredLabel.getNotExcapedValue();

            if (labels.contains(label)) {
                throw labelAlreadyExists(typeName, label);
            }

            labels.add(label);
        }

        enums.add(new EnumType(typeName, labels));
    }

    /**
     * Inserts one label into an enum type: after the last label by default, and
     * otherwise directly before or after the stated neighbor, so the modeled
     * labels stay in PostgreSQL's sort order. Adding a value is the only
     * {@code ALTER TYPE} action sqlcj models; every other action is rejected.
     *
     * <p>As PostgreSQL does, {@code IF NOT EXISTS} is decided by the label
     * alone: a label the type already has is a no-op even when the stated
     * neighbor is not one of its labels.
     */
    private void applyAddEnumValue(AlterType alterType, List<EnumType> enums) {
        String typeName = MultiPartName.unquote(alterType.getName());

        int index = indexOfEnum(enums, typeName);

        if (index < 0) {
            throw typeNotFound(typeName);
        }

        EnumType enumType = enums.get(index);
        String label = alterType.getValue().getNotExcapedValue();

        if (enumType.labels().contains(label)) {
            if (alterType.isIfNotExists()) {
                return;
            }

            throw labelAlreadyExists(typeName, label);
        }

        List<String> labels = new ArrayList<>(enumType.labels());

        labels.add(labelPosition(alterType, enumType), label);

        enums.set(index, new EnumType(enumType.name(), labels));
    }

    /** The position an added label takes among the type's existing labels. */
    private int labelPosition(AlterType alterType, EnumType enumType) {
        AlterType.Position position = alterType.getPosition();

        if (position == null) {
            return enumType.labels().size();
        }

        String neighbor = alterType.getNeighborValue().getNotExcapedValue();

        int index = enumType.labels().indexOf(neighbor);

        if (index < 0) {
            throw labelNotFound(enumType.name(), neighbor);
        }

        return position == AlterType.Position.BEFORE
            ? index
            : index + 1;
    }

    /**
     * Reports whether a statement is one of the documented statements a schema
     * source may state without changing the schema model. Such a statement is
     * not resolved against the schema at all.
     */
    private boolean isIgnored(Statement statement) {
        if (statement instanceof Drop drop) {
            return isIgnoredDropObject(drop);
        }

        if (statement instanceof CreateFunction createFunction) {
            return hasDollarQuotedBody(createFunction);
        }

        if (statement instanceof UnsupportedStatement unsupported) {
            return ALTER_INDEX.matcher(unsupported.toString()).find();
        }

        return statement instanceof CreateIndex
            || statement instanceof Comment
            || statement instanceof CreateExtension
            || statement instanceof CreateSequence
            || statement instanceof AlterSequence
            || statement instanceof Grant
            || statement instanceof Revoke
            || statement instanceof CreateTrigger
            || statement instanceof Insert
            || statement instanceof Update
            || statement instanceof Delete;
    }

    /**
     * A dropped table is modeled and the listed dropped objects are ignored;
     * any other dropped object, such as a view, is rejected.
     */
    private boolean isIgnoredDropObject(Drop drop) {
        return switch (drop.getObjectType()) {
            case INDEX, SEQUENCE, FUNCTION, TRIGGER -> true;
            default -> false;
        };
    }

    /**
     * Reports whether a {@code CREATE FUNCTION} states a dollar-quoted body,
     * which the parser lexes as one literal token. It captures a body written
     * any other way through the end of the source, so such a function is
     * rejected instead of ignored.
     *
     * <p>A captured statement separator and everything after it is part of a
     * later statement rather than of this function, so only the parts before the
     * first {@code ";"} part decide.
     */
    private boolean hasDollarQuotedBody(CreateFunction createFunction) {
        List<String> parts = createFunction.getFunctionDeclarationParts();

        return parts != null
            && parts.stream()
                .takeWhile(part -> !";".equals(part))
                .anyMatch(part -> StringValue.getDollarQuoteDelimiter(part) != null);
    }

    /**
     * Appends one table. A repeated table name fails, as PostgreSQL rejects it,
     * unless the statement declares {@code IF NOT EXISTS}.
     */
    private void applyCreateTable(
        CreateTable createTable,
        List<EnumType> enums,
        List<Table> tables
    ) {
        String tableName = createTable.getTable().getUnquotedName();

        if (indexOfTable(tables, tableName) >= 0) {
            if (createTable.isIfNotExists()) {
                return;
            }

            throw tableAlreadyExists(tableName);
        }

        tables.add(parseTable(createTable, enums));
    }

    /** Removes every named table, keeping the order of the remaining ones. */
    private void applyDropTable(Drop drop, List<Table> tables) {
        for (net.sf.jsqlparser.schema.Table dropped : drop.getNames()) {
            String tableName = dropped.getUnquotedName();

            int index = indexOfTable(tables, tableName);

            if (index < 0) {
                if (drop.isIfExists()) {
                    continue;
                }

                throw tableNotFound(tableName);
            }

            tables.remove(index);
        }
    }

    /**
     * Applies the actions of one {@code ALTER TABLE} in order, in the position
     * of the altered table. {@code ALTER TABLE IF EXISTS} covers only the table,
     * so a missing column of an existing table still fails.
     */
    private void applyAlterTable(Alter alter, int line, List<EnumType> enums, List<Table> tables) {
        String tableName = alter.getTable().getUnquotedName();

        int index = indexOfTable(tables, tableName);

        if (index < 0) {
            if (alter.isUseTableIfExists()) {
                return;
            }

            throw tableNotFound(tableName);
        }

        for (AlterExpression expression : alter.getAlterExpressions()) {
            tables.set(index, applyAlterExpression(alter, line, expression, enums, tables, index));
        }
    }

    /**
     * Applies one {@code ALTER TABLE} action and returns the altered table. A
     * constraint action is accepted and leaves the table unchanged; an action
     * that cannot be recognized as one of those forms, such as
     * {@code SET DEFAULT}, is rejected like any other unsupported statement.
     */
    private Table applyAlterExpression(
        Alter alter,
        int line,
        AlterExpression expression,
        List<EnumType> enums,
        List<Table> tables,
        int index
    ) {
        Table table = tables.get(index);
        AlterOperation operation = expression.getOperation();

        if (operation == AlterOperation.ADD && isNotEmpty(expression.getColDataTypeList())) {
            return addColumns(expression, enums, table);
        }

        if (operation == AlterOperation.DROP && expression.getColumnName() != null) {
            return dropColumn(expression, table);
        }

        if (operation == AlterOperation.RENAME && expression.getColumnOldName() != null) {
            return renameColumn(expression, table);
        }

        if (operation == AlterOperation.RENAME_TABLE) {
            return renameTable(expression, tables, index);
        }

        if (operation == AlterOperation.ALTER) {
            return alterColumns(alter, line, expression, enums, table);
        }

        if (isIgnoredConstraintAction(expression)) {
            return table;
        }

        throw unsupportedStatement(alter, line);
    }

    /**
     * Reports whether one {@code ALTER TABLE} action states a constraint sqlcj
     * accepts without modeling it: adding a primary-key, unique, foreign-key, or
     * check constraint, dropping a named constraint, or renaming one. The table
     * itself is still resolved, so a missing table fails as it does for any
     * other {@code ALTER TABLE}.
     */
    private boolean isIgnoredConstraintAction(AlterExpression expression) {
        AlterOperation operation = expression.getOperation();

        if (operation == AlterOperation.ADD) {
            return !isNotEmpty(expression.getColDataTypeList())
                && isIgnoredConstraintKind(expression.getIndex());
        }

        if (operation == AlterOperation.DROP) {
            return expression.getColumnName() == null
                && expression.getConstraintName() != null;
        }

        return operation == AlterOperation.RENAME_CONSTRAINT;
    }

    private boolean isIgnoredConstraintKind(Index index) {
        if (index == null) {
            return false;
        }

        return switch (index.getKind()) {
            case PRIMARY_KEY, UNIQUE, FOREIGN_KEY, CHECK -> true;
            default -> false;
        };
    }

    /**
     * Applies one {@code ALTER COLUMN} action: a new type, which keeps the
     * column's position and nullability, or a nullability change.
     */
    private Table alterColumns(
        Alter alter,
        int line,
        AlterExpression expression,
        List<EnumType> enums,
        Table table
    ) {
        if (isNotEmpty(expression.getColDataTypeList())) {
            if (!statesNewTypes(expression.getColDataTypeList())) {
                throw unsupportedStatement(alter, line);
            }

            return changeColumnTypes(expression, enums, table);
        }

        if (isNotEmpty(expression.getColumnSetNotNullList())) {
            return changeNullability(
                expression.getColumnSetNotNullList().stream()
                    .map(AlterExpression.ColumnSetNotNull::getColumnName)
                    .toList(),
                false,
                table
            );
        }

        if (isNotEmpty(expression.getColumnDropNotNullList())) {
            return changeNullability(
                expression.getColumnDropNotNullList().stream()
                    .map(AlterExpression.ColumnDropNotNull::getColumnName)
                    .toList(),
                true,
                table
            );
        }

        throw unsupportedStatement(alter, line);
    }

    /**
     * Reports whether every entry of an {@code ALTER COLUMN} action states a new
     * type. The parser reports other column actions, such as
     * {@code SET STATISTICS} or an identity change, in the same list without a
     * declared type, and those are not supported.
     */
    private boolean statesNewTypes(List<AlterExpression.ColumnDataType> definitions) {
        return definitions.stream()
            .allMatch(
                definition -> definition.isWithType() && definition.getColDataType() != null
            );
    }

    /**
     * Appends each added column, typed exactly as a {@code CREATE TABLE} column
     * of the same declaration, and records its column-level constraints.
     */
    private Table addColumns(AlterExpression expression, List<EnumType> enums, Table table) {
        List<Column> columns = new ArrayList<>(table.columns());
        List<Constraint> constraints = new ArrayList<>(table.constraints());

        for (AlterExpression.ColumnDataType definition : expression.getColDataTypeList()) {
            Column column = parseColumn(definition, enums);

            if (indexOfColumn(columns, column.name()) >= 0) {
                if (expression.isUseIfNotExists()) {
                    continue;
                }

                throw columnAlreadyExists(table.name(), column.name());
            }

            columns.add(column);
            constraints.addAll(parseColumnConstraints(definition));
        }

        return new Table(table.name(), columns, constraints);
    }

    /** Removes one column and every constraint that lists it. */
    private Table dropColumn(AlterExpression expression, Table table) {
        String columnName = MultiPartName.unquote(expression.getColumnName());

        int index = indexOfColumn(table.columns(), columnName);

        if (index < 0) {
            if (expression.isUsingIfExists()) {
                return table;
            }

            throw columnNotFound(table.name(), columnName);
        }

        List<Column> columns = new ArrayList<>(table.columns());

        columns.remove(index);

        List<Constraint> constraints = table.constraints().stream()
            .filter(constraint -> !listsColumn(constraint, columnName))
            .toList();

        return new Table(table.name(), columns, constraints);
    }

    /**
     * Renames one column in its position and in the constraints that list it.
     */
    private Table renameColumn(AlterExpression expression, Table table) {
        String oldName = MultiPartName.unquote(expression.getColumnOldName());
        String newName = MultiPartName.unquote(expression.getColumnName());

        int index = indexOfColumn(table.columns(), oldName);

        if (index < 0) {
            throw columnNotFound(table.name(), oldName);
        }

        int existing = indexOfColumn(table.columns(), newName);

        if (existing >= 0 && existing != index) {
            throw columnAlreadyExists(table.name(), newName);
        }

        List<Column> columns = new ArrayList<>(table.columns());
        Column column = columns.get(index);

        columns.set(
            index,
            new Column(
                newName,
                column.type(),
                column.nullable(),
                column.unsupportedType(),
                column.enumType()
            )
        );

        List<Constraint> constraints = table.constraints().stream()
            .map(constraint -> renameConstraintColumn(constraint, oldName, newName))
            .toList();

        return new Table(table.name(), columns, constraints);
    }

    /** Renames one table in its position, keeping its columns and constraints. */
    private Table renameTable(AlterExpression expression, List<Table> tables, int index) {
        String newName = MultiPartName.unquote(expression.getNewTableName());

        int existing = indexOfTable(tables, newName);

        if (existing >= 0 && existing != index) {
            throw tableAlreadyExists(newName);
        }

        Table table = tables.get(index);

        return new Table(newName, table.columns(), table.constraints());
    }

    /**
     * Retypes each named column in its position, mapping or recording the new
     * type as a {@code CREATE TABLE} column of the same type would be, and
     * keeping the column's nullability, which a type change does not state.
     */
    private Table changeColumnTypes(
        AlterExpression expression,
        List<EnumType> enums,
        Table table
    ) {
        List<Column> columns = new ArrayList<>(table.columns());

        for (AlterExpression.ColumnDataType definition : expression.getColDataTypeList()) {
            String columnName = MultiPartName.unquote(definition.getColumnName());

            int index = indexOfColumn(columns, columnName);

            if (index < 0) {
                throw columnNotFound(table.name(), columnName);
            }

            Column column = columns.get(index);
            Column retyped = parseColumn(definition, enums);

            columns.set(
                index,
                new Column(
                    column.name(),
                    retyped.type(),
                    column.nullable(),
                    retyped.unsupportedType(),
                    retyped.enumType()
                )
            );
        }

        return new Table(table.name(), columns, table.constraints());
    }

    /** Sets the nullability of each named column in its position. */
    private Table changeNullability(
        List<String> declaredNames,
        boolean nullable,
        Table table
    ) {
        List<Column> columns = new ArrayList<>(table.columns());

        for (String declaredName : declaredNames) {
            String columnName = MultiPartName.unquote(declaredName);

            int index = indexOfColumn(columns, columnName);

            if (index < 0) {
                throw columnNotFound(table.name(), columnName);
            }

            Column column = columns.get(index);

            columns.set(
                index,
                new Column(
                    column.name(),
                    column.type(),
                    nullable,
                    column.unsupportedType(),
                    column.enumType()
                )
            );
        }

        return new Table(table.name(), columns, table.constraints());
    }

    private boolean listsColumn(Constraint constraint, String columnName) {
        return constraint.columns().stream()
            .anyMatch(column -> column.equalsIgnoreCase(columnName));
    }

    private Constraint renameConstraintColumn(
        Constraint constraint,
        String oldName,
        String newName
    ) {
        return new Constraint(
            constraint.type(),
            constraint.columns().stream()
                .map(column -> column.equalsIgnoreCase(oldName) ? newName : column)
                .toList()
        );
    }

    /**
     * Table and column names are matched case-insensitively, as query analysis
     * looks them up.
     */
    private int indexOfTable(List<Table> tables, String tableName) {
        for (int i = 0; i < tables.size(); i++) {
            if (tables.get(i).name().equalsIgnoreCase(tableName)) {
                return i;
            }
        }

        return -1;
    }

    /** Enum type names are matched case-insensitively, as a column names them. */
    private int indexOfEnum(List<EnumType> enums, String typeName) {
        for (int i = 0; i < enums.size(); i++) {
            if (enums.get(i).name().equalsIgnoreCase(typeName)) {
                return i;
            }
        }

        return -1;
    }

    private int indexOfColumn(List<Column> columns, String columnName) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equalsIgnoreCase(columnName)) {
                return i;
            }
        }

        return -1;
    }

    private boolean isNotEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }

    private IllegalArgumentException tableNotFound(String tableName) {
        return new IllegalArgumentException("Table not found in schema: " + tableName);
    }

    private IllegalArgumentException tableAlreadyExists(String tableName) {
        return new IllegalArgumentException("Table already exists in schema: " + tableName);
    }

    private IllegalArgumentException columnNotFound(String tableName, String columnName) {
        return new IllegalArgumentException(
            "Column not found in table %s: %s".formatted(tableName, columnName)
        );
    }

    private IllegalArgumentException columnAlreadyExists(String tableName, String columnName) {
        return new IllegalArgumentException(
            "Column already exists in table %s: %s".formatted(tableName, columnName)
        );
    }

    private IllegalArgumentException typeNotFound(String typeName) {
        return new IllegalArgumentException("Type not found in schema: " + typeName);
    }

    private IllegalArgumentException typeAlreadyExists(String typeName) {
        return new IllegalArgumentException("Type already exists in schema: " + typeName);
    }

    private IllegalArgumentException labelNotFound(String typeName, String label) {
        return new IllegalArgumentException(
            "Label not found in type %s: %s".formatted(typeName, label)
        );
    }

    private IllegalArgumentException labelAlreadyExists(String typeName, String label) {
        return new IllegalArgumentException(
            "Label already exists in type %s: %s".formatted(typeName, label)
        );
    }

    private UnsupportedOperationException unsupportedStatement(Statement statement, int line) {
        return new UnsupportedOperationException(
            "Unsupported schema statement: %s at line %d"
                .formatted(statement.getClass().getSimpleName(), line)
        );
    }

    private Table parseTable(CreateTable createTable, List<EnumType> enums) {
        String tableName = createTable.getTable().getUnquotedName();

        List<Column> columns = new ArrayList<>();
        List<Constraint> constraints = new ArrayList<>();

        for (ColumnDefinition definition : createTable.getColumnDefinitions()) {
            Column column = parseColumn(definition, enums);

            if (indexOfColumn(columns, column.name()) >= 0) {
                throw columnAlreadyExists(tableName, column.name());
            }

            columns.add(column);
            constraints.addAll(parseColumnConstraints(definition));
        }

        constraints.addAll(parseTableConstraints(createTable));

        return new Table(
            tableName,
            columns,
            constraints
        );
    }

    /**
     * Parses one column, recording a column sqlcj cannot map with its declared
     * type text instead of failing the schema. An array column is such a column
     * regardless of its element type, because the mapped types and the modeled
     * enum types are all scalar.
     */
    private Column parseColumn(ColumnDefinition definition, List<EnumType> enums) {
        String typeName = typeName(definition);
        int arrayDimensions = arrayDimensions(definition);
        boolean nullable = !isSerial(typeName) && isNullable(definition);

        ColumnType type = arrayDimensions == 0
            ? columnType(typeName)
            : null;

        if (type != null) {
            return new Column(columnName(definition), type, nullable);
        }

        EnumType enumType = arrayDimensions == 0
            ? declaredEnum(definition, enums)
            : null;

        if (enumType != null) {
            return new Column(
                columnName(definition),
                ColumnType.ENUM,
                nullable,
                null,
                enumType.name()
            );
        }

        return new Column(
            columnName(definition),
            null,
            nullable,
            typeName + "[]".repeat(arrayDimensions)
        );
    }

    /**
     * The enum type a column of an unmapped type names, or {@code null} when
     * the schema declares no such type. The declared type is matched without
     * its SQL identifier delimiters and case-insensitively, as PostgreSQL
     * resolves an unquoted type name.
     */
    private EnumType declaredEnum(ColumnDefinition definition, List<EnumType> enums) {
        String declaredType = MultiPartName.unquote(
            definition.getColDataType().getDataType()
        );

        int index = indexOfEnum(enums, declaredType);

        return index < 0
            ? null
            : enums.get(index);
    }

    /**
     * Reports how many array dimensions a column declares. The parser reports
     * an array's dimensions separately from its element type, so a column with
     * any dimension is an array of the reported type.
     */
    private int arrayDimensions(ColumnDefinition definition) {
        List<Integer> arrayData = definition.getColDataType().getArrayData();

        return arrayData == null
            ? 0
            : arrayData.size();
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

    /** The mapped type of declared spelling, or {@code null} if unmapped. */
    private ColumnType columnType(String typeName) {
        return switch (typeName) {
            case "INTEGER", "INT", "INT4", "SERIAL", "SERIAL4" -> ColumnType.INTEGER;
            case "BIGINT", "INT8", "BIGSERIAL", "SERIAL8" -> ColumnType.BIGINT;
            case "SMALLINT", "INT2", "SMALLSERIAL", "SERIAL2" -> ColumnType.SMALLINT;
            case "BOOLEAN", "BOOL" -> ColumnType.BOOLEAN;
            case "VARCHAR", "CHARACTER VARYING", "CHAR", "CHARACTER" -> ColumnType.VARCHAR;
            case "TEXT" -> ColumnType.TEXT;
            case "DATE" -> ColumnType.DATE;
            case "TIME", "TIME WITHOUT TIME ZONE" -> ColumnType.TIME;
            case "TIMESTAMP", "TIMESTAMP WITHOUT TIME ZONE" -> ColumnType.TIMESTAMP;
            case "TIMESTAMP WITH TIME ZONE", "TIMESTAMPTZ" -> ColumnType.TIMESTAMP_WITH_TIME_ZONE;
            case "DECIMAL", "NUMERIC" -> ColumnType.DECIMAL;
            case "REAL", "FLOAT4" -> ColumnType.REAL;
            case "DOUBLE PRECISION", "FLOAT8" -> ColumnType.DOUBLE_PRECISION;
            case "UUID" -> ColumnType.UUID;
            case "BYTEA" -> ColumnType.BYTEA;
            case "JSON" -> ColumnType.JSON;
            case "JSONB" -> ColumnType.JSONB;
            default -> null;
        };
    }

    /**
     * PostgreSQL defines the serial spellings as an integer type with a
     * sequence default and {@code NOT NULL}, so such a column is never
     * nullable.
     */
    private boolean isSerial(String typeName) {
        return switch (typeName) {
            case "SERIAL", "BIGSERIAL", "SMALLSERIAL", "SERIAL2", "SERIAL4", "SERIAL8" -> true;
            default -> false;
        };
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
