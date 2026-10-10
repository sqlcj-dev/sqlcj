package dev.sqlcj.schema.parser;

import dev.sqlcj.schema.Column;
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
import net.sf.jsqlparser.statement.CreateFunctionalStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.UnsupportedStatement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.alter.AlterOperation;
import net.sf.jsqlparser.statement.alter.AlterType;
import net.sf.jsqlparser.statement.create.table.CheckConstraint;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.ForeignKeyIndex;
import net.sf.jsqlparser.statement.create.table.Index;
import net.sf.jsqlparser.statement.create.type.CreateType;
import net.sf.jsqlparser.statement.create.type.EnumTypeDefinition;
import net.sf.jsqlparser.statement.drop.Drop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class DefaultSchemaParser implements SchemaParser {

    /**
     * The words PostgreSQL allows between the verb and {@code TABLE} of a table
     * statement.
     */
    private static final Set<String> TABLE_MODIFIERS = Set.of(
        "GLOBAL",
        "LOCAL",
        "TEMP",
        "TEMPORARY",
        "UNLOGGED",
        "FOREIGN"
    );

    /** Resolves the type a column declaration states. */
    private final ColumnTypeMapping columnTypeMapping = new ColumnTypeMapping();

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
     *
     * <p>Only the statements sqlcj models are applied: {@code CREATE TABLE},
     * {@code ALTER TABLE}, {@code DROP TABLE},
     * {@code CREATE TYPE ... AS ENUM}, and {@code ALTER TYPE ... ADD VALUE}.
     * Every other statement a schema source may record is ignored and is not
     * resolved against the schema at all, with two exceptions the parser forces:
     * a function or procedure whose body it captures through the end of the
     * source, and a statement it reports only as opaque text that opens as table
     * or type DDL, which may change what the schema models.
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
        } else if (statement instanceof AlterType alterType) {
            if (alterType.getAction() != AlterType.Action.ADD_VALUE) {
                throw unsupportedAlterTypeAction(line);
            }

            applyAddEnumValue(alterType, enums);
        } else if (statement instanceof CreateFunctionalStatement functional) {
            if (!hasDollarQuotedBody(functional)) {
                throw unsupportedFunctionBody(functional, line);
            }
        } else if (statement instanceof UnsupportedStatement unsupported) {
            String opening = tableOrTypeOpening(words(unsupported.toString()));

            if (opening != null) {
                throw unsupportedOpening(opening, line);
            }
        }
    }

    /** The whitespace-separated words of a statement's text. */
    private List<String> words(String text) {
        return List.of(text.trim().split("\\s+"));
    }

    /**
     * The table or type DDL opening that {@code words} begin with, in upper case
     * and separated by single spaces, or {@code null} when they begin with
     * anything else. The words are compared case-insensitively, as PostgreSQL
     * reads them.
     *
     * <p>A table opening is {@code CREATE}, {@code ALTER}, or {@code DROP}, then
     * any of PostgreSQL's table modifiers, then {@code TABLE}. A type opening is
     * {@code CREATE} or {@code ALTER}, then {@code TYPE}. A statement sqlcj
     * cannot read is ignored unless it opens this way, because such a statement
     * may change the tables or the enum types the schema models.
     */
    private String tableOrTypeOpening(List<String> words) {
        if (words.isEmpty()) {
            return null;
        }

        String verb = upperCase(words.get(0));

        if (!"CREATE".equals(verb) && !"ALTER".equals(verb) && !"DROP".equals(verb)) {
            return null;
        }

        if (!"DROP".equals(verb) && words.size() > 1 && "TYPE".equals(upperCase(words.get(1)))) {
            return verb + " TYPE";
        }

        int index = 1;

        while (index < words.size() && TABLE_MODIFIERS.contains(upperCase(words.get(index)))) {
            index++;
        }

        if (index >= words.size() || !"TABLE".equals(upperCase(words.get(index)))) {
            return null;
        }

        return String.join(
            " ",
            words.subList(0, index + 1).stream()
                .map(this::upperCase)
                .toList()
        );
    }

    private String upperCase(String word) {
        return word.toUpperCase(Locale.ROOT);
    }

    /**
     * Appends one enum type with its declared labels. A repeated type name
     * fails, as PostgreSQL rejects it, and so does a repeated label. An enum is
     * the only type sqlcj models; a {@code CREATE TYPE} of any other kind is
     * ignored, and a column of such a type is recorded with its declared type.
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
     * Reports whether a {@code CREATE FUNCTION} or {@code CREATE PROCEDURE}
     * states a dollar-quoted body, which the parser lexes as one literal token.
     * It captures a body written any other way through the end of the source, so
     * such a function or procedure is rejected instead of ignored.
     *
     * <p>A captured statement separator and everything after it is part of a
     * later statement rather than of this function, so only the parts before the
     * first {@code ";"} part decide.
     */
    private boolean hasDollarQuotedBody(CreateFunctionalStatement functional) {
        List<String> parts = functional.getFunctionDeclarationParts();

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
            tables.set(index, applyAlterExpression(line, expression, enums, tables, index));
        }
    }

    /**
     * Applies one {@code ALTER TABLE} action and returns the altered table. A
     * constraint action is accepted and leaves the table unchanged; an action
     * that cannot be recognized as one of those forms, such as
     * {@code SET DEFAULT}, is rejected naming the statement it belongs to.
     */
    private Table applyAlterExpression(
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
            return alterColumns(line, expression, enums, table);
        }

        if (isIgnoredConstraintAction(expression)) {
            return table;
        }

        throw unsupportedAlterTableAction(line);
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
        int line,
        AlterExpression expression,
        List<EnumType> enums,
        Table table
    ) {
        if (isNotEmpty(expression.getColDataTypeList())) {
            if (!statesNewTypes(expression.getColDataTypeList())) {
                throw unsupportedAlterTableAction(line);
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

        throw unsupportedAlterTableAction(line);
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
                column.enumType(),
                column.array(),
                column.blankPadded()
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
                    retyped.enumType(),
                    retyped.array(),
                    retyped.blankPadded()
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
                    column.enumType(),
                    column.array(),
                    column.blankPadded()
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

    private UnsupportedOperationException unsupportedAlterTableAction(int line) {
        return new UnsupportedOperationException(
            "Unsupported ALTER TABLE action at line %d".formatted(line)
        );
    }

    private UnsupportedOperationException unsupportedAlterTypeAction(int line) {
        return new UnsupportedOperationException(
            "Unsupported ALTER TYPE action at line %d".formatted(line)
        );
    }

    private UnsupportedOperationException unsupportedFunctionBody(
        CreateFunctionalStatement functional,
        int line
    ) {
        return new UnsupportedOperationException(
            "Unsupported schema statement: CREATE %s without a dollar-quoted body at line %d"
                .formatted(upperCase(functional.getKind()), line)
        );
    }

    private UnsupportedOperationException unsupportedOpening(String opening, int line) {
        return new UnsupportedOperationException(
            "Unsupported schema statement: %s at line %d".formatted(opening, line)
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
     * Parses one column, whose declared type the shared column type mapping
     * resolves or records with its declared type text instead of failing the
     * schema.
     */
    private Column parseColumn(ColumnDefinition definition, List<EnumType> enums) {
        ColumnTypeMapping.MappedType mappedType = columnTypeMapping.map(
            definition.getColDataType(),
            enums
        );

        return new Column(
            columnName(definition),
            mappedType.type(),
            !isSerial(mappedType.typeName()) && isNullable(definition),
            mappedType.unsupportedType(),
            mappedType.enumType(),
            mappedType.array(),
            mappedType.blankPadded()
        );
    }

    /**
     * Returns the canonical column name without SQL identifier delimiters.
     */
    private String columnName(ColumnDefinition definition) {
        return MultiPartName.unquote(definition.getColumnName());
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
