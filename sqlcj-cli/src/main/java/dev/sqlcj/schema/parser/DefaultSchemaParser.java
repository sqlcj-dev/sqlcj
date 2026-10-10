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
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.MultiPartName;
import net.sf.jsqlparser.statement.LikeClause;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.UnsupportedStatement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.alter.AlterExpressionPartition;
import net.sf.jsqlparser.statement.alter.AlterOperation;
import net.sf.jsqlparser.statement.alter.AlterType;
import net.sf.jsqlparser.statement.create.table.CheckConstraint;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.ExcludeConstraint;
import net.sf.jsqlparser.statement.create.table.ForeignKeyIndex;
import net.sf.jsqlparser.statement.create.table.Index;
import net.sf.jsqlparser.statement.create.table.TableElement;
import net.sf.jsqlparser.statement.create.type.CreateType;
import net.sf.jsqlparser.statement.create.type.EnumTypeDefinition;
import net.sf.jsqlparser.statement.drop.Drop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
        List<Table> tables = new ArrayList<>(schema.tables());
        List<EnumType> enums = new ArrayList<>(schema.enums());

        for (SchemaSourceSplitter.SourceStatement statement : SchemaSourceSplitter.split(sql)) {
            apply(statement, tables, enums);
        }

        return new Schema(tables, enums);
    }

    /**
     * Parses one statement of a schema source alone and applies it, so that a
     * statement the parser cannot read fails only itself.
     *
     * <p>A statement the parser reads as exactly one statement of a kind of its
     * own is applied as any parsed statement is. Every other statement is
     * unreadable: the parser states a syntax failure for it, reports it only as
     * opaque text, or reads it as several statements. An unreadable statement is
     * ignored unless it opens as table or type DDL, which may change what the
     * schema models, and an unreadable {@code ALTER TABLE} is ignored as well
     * when every one of its top-level actions cannot change a modeled column.
     */
    private void apply(
        SchemaSourceSplitter.SourceStatement statement,
        List<Table> tables,
        List<EnumType> enums
    ) {
        Statements parsed = null;
        JSQLParserException failure = null;

        try {
            parsed = CCJSqlParserUtil.parseStatements(statement.sql());
        } catch (JSQLParserException e) {
            failure = e;
        }

        if (
            parsed != null
                && parsed.size() == 1
                && !(parsed.get(0) instanceof UnsupportedStatement)
        ) {
            apply(parsed.get(0), statement.line(), tables, enums);

            return;
        }

        String opening = tableOrTypeOpening(statement.tokens());

        if (opening == null || ignoresEveryAlterTableAction(opening, statement.tokens())) {
            return;
        }

        if (failure != null) {
            throw unreadableStatement(statement, failure);
        }

        throw unsupportedOpening(opening, statement.line());
    }

    /**
     * The failure of an unreadable statement, stating the parser's reason at the
     * position of the unexpected token in the file. The parser reads one
     * statement at a time and therefore reports positions within it, so a
     * rejected statement alone is parsed a second time preceded by the line
     * breaks and spaces that stand before it in the file. A second parse that
     * unexpectedly succeeds leaves the first reason, which names a position
     * within the statement.
     */
    private SchemaParseException unreadableStatement(
        SchemaSourceSplitter.SourceStatement statement,
        JSQLParserException failure
    ) {
        String padded = "\n".repeat(statement.line() - 1)
            + " ".repeat(statement.column() - 1)
            + statement.sql();

        JSQLParserException located = failure;

        try {
            CCJSqlParserUtil.parseStatements(padded);
        } catch (JSQLParserException e) {
            located = e;
        }

        return new SchemaParseException(SqlParseReason.of(located, true), located);
    }

    /**
     * Reports whether an unreadable statement is an {@code ALTER TABLE} whose
     * every top-level action is ignored, which is decided from the statement's
     * tokens alone, without rewriting it and without resolving its table.
     *
     * <p>None of those actions can change a modeled column's existence, name,
     * type, or nullability: nullability comes from {@code NOT NULL} alone, and
     * PostgreSQL requires a column to be {@code NOT NULL} already before
     * {@code ADD GENERATED ... AS IDENTITY}. Without this rule no
     * {@code pg_dump} snapshot with an identity column loads, because the parser
     * reads neither the identity form {@code pg_dump} writes nor an
     * {@code EXCLUDE}, {@code DEFERRABLE INITIALLY DEFERRED},
     * {@code UNIQUE USING INDEX}, or {@code NOT VALID} constraint, nor an
     * unnamed {@code ADD CHECK}.
     */
    private boolean ignoresEveryAlterTableAction(String opening, List<String> tokens) {
        if (!"ALTER TABLE".equals(opening)) {
            return false;
        }

        int index = afterAlteredTableName(tokens);

        if (index < 0) {
            return false;
        }

        return topLevelActions(tokens.subList(index, tokens.size())).stream()
            .allMatch(this::ignoresAlterTableAction);
    }

    /**
     * The index of the first action token of an {@code ALTER TABLE}, after the
     * optional {@code IF EXISTS} and {@code ONLY} and the altered table's
     * possibly qualified name, or {@code -1} when the statement ends inside
     * that name.
     */
    private int afterAlteredTableName(List<String> tokens) {
        int index = 2;

        if (statesWords(tokens, index, "IF", "EXISTS")) {
            index += 2;
        }

        if (statesWords(tokens, index, "ONLY")) {
            index++;
        }

        if (index >= tokens.size()) {
            return -1;
        }

        index++;

        while (statesWords(tokens, index, ".")) {
            if (index + 1 >= tokens.size()) {
                return -1;
            }

            index += 2;
        }

        return index;
    }

    /**
     * The actions of one {@code ALTER TABLE}, split at the commas that stand
     * outside parentheses, so a comma of a column or parameter list belongs to
     * its action.
     */
    private List<List<String>> topLevelActions(List<String> tokens) {
        List<List<String>> actions = new ArrayList<>();
        List<String> action = new ArrayList<>();
        int depth = 0;

        for (String token : tokens) {
            if ("(".equals(token)) {
                depth++;
            } else if (")".equals(token)) {
                depth--;
            } else if (",".equals(token) && depth == 0) {
                actions.add(action);
                action = new ArrayList<>();

                continue;
            }

            action.add(token);
        }

        actions.add(action);

        return actions;
    }

    /**
     * Reports whether one action of an unreadable {@code ALTER TABLE} begins as
     * an added constraint or as {@code ALTER [COLUMN] <name> ADD GENERATED}. The
     * words are compared case-insensitively, as PostgreSQL reads them.
     */
    private boolean ignoresAlterTableAction(List<String> action) {
        if (statesWords(action, 0, "ADD")) {
            return addsIgnoredConstraint(action);
        }

        if (!statesWords(action, 0, "ALTER")) {
            return false;
        }

        int index = statesWords(action, 1, "COLUMN")
            ? 2
            : 1;

        return index < action.size() && statesWords(action, index + 1, "ADD", "GENERATED");
    }

    private boolean addsIgnoredConstraint(List<String> action) {
        if (statesWords(action, 1, "CONSTRAINT")) {
            return action.size() > 2;
        }

        return statesWords(action, 1, "CHECK")
            || statesWords(action, 1, "UNIQUE")
            || statesWords(action, 1, "EXCLUDE")
            || statesWords(action, 1, "PRIMARY", "KEY")
            || statesWords(action, 1, "FOREIGN", "KEY");
    }

    /**
     * Reports whether the tokens from {@code index} on are {@code words},
     * compared case-insensitively. A list the parser did not state at all states
     * no words.
     */
    private boolean statesWords(List<String> tokens, int index, String... words) {
        if (tokens == null || index + words.length > tokens.size()) {
            return false;
        }

        for (int i = 0; i < words.length; i++) {
            if (!words[i].equalsIgnoreCase(tokens.get(index + i))) {
                return false;
            }
        }

        return true;
    }

    /**
     * Applies one statement to the tables and enum types the statements before
     * it left.
     *
     * <p>Only the statements sqlcj models are applied: {@code CREATE TABLE},
     * {@code ALTER TABLE}, {@code DROP TABLE},
     * {@code CREATE TYPE ... AS ENUM}, and {@code ALTER TYPE}. Every other
     * statement a schema source may record is ignored and is not resolved
     * against the schema at all.
     */
    private void apply(Statement statement, int line, List<Table> tables, List<EnumType> enums) {
        if (statement instanceof CreateTable createTable) {
            applyCreateTable(createTable, line, enums, tables);
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
            applyAlterType(alterType, line, enums, tables);
        }
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
     * Appends one enum type with its declared labels, and replaces a modeled
     * enum of the same name in its position, because the {@code DROP TYPE} that
     * freed the name is never seen. A repeated label fails, as PostgreSQL
     * rejects it. An enum is the only type sqlcj models; a {@code CREATE TYPE}
     * of any other kind is ignored, and a column of such a type is recorded
     * with its declared type.
     */
    private void applyCreateEnumType(
        CreateType createType,
        EnumTypeDefinition definition,
        List<EnumType> enums
    ) {
        String typeName = MultiPartName.unquote(createType.getName());

        int index = indexOfEnum(enums, typeName);

        List<String> labels = new ArrayList<>();

        for (StringValue declaredLabel : definition.getLabels()) {
            String label = declaredLabel.getNotExcapedValue();

            if (labels.contains(label)) {
                throw labelAlreadyExists(typeName, label);
            }

            labels.add(label);
        }

        EnumType enumType = new EnumType(typeName, labels);

        if (index < 0) {
            enums.add(enumType);

            return;
        }

        enums.set(index, enumType);
    }

    /**
     * Applies one {@code ALTER TYPE} action, resolving the stated type name,
     * with its SQL identifier delimiters removed, among the modeled enum types.
     *
     * <p>An action that cannot change a modeled enum is ignored without the
     * type being resolved at all: ownership and the attribute actions of a
     * composite type. Every other action sqlcj models resolves the type, and an
     * action sqlcj does not model is rejected naming the action.
     */
    private void applyAlterType(
        AlterType alterType,
        int line,
        List<EnumType> enums,
        List<Table> tables
    ) {
        AlterType.Action action = alterType.getAction();

        if (ignoresAlterTypeAction(action)) {
            return;
        }

        if (action == AlterType.Action.ADD_VALUE) {
            applyAddEnumValue(alterType, enums);

            return;
        }

        String typeName = MultiPartName.unquote(alterType.getName());
        int index = indexOfEnum(enums, typeName);

        switch (action) {
            case RENAME_VALUE -> renameEnumLabel(alterType, typeName, index, enums);
            case RENAME -> renameEnumType(alterType, typeName, index, enums, tables);
            case SET_SCHEMA -> moveEnumTypeToSchema(alterType, line, typeName, index);
            default -> throw unsupportedAlterTypeAction(String.valueOf(action), line);
        }
    }

    /**
     * Reports whether one {@code ALTER TYPE} action cannot change a modeled
     * enum type: {@code OWNER TO} and the attribute actions, which belong to a
     * composite type.
     */
    private boolean ignoresAlterTypeAction(AlterType.Action action) {
        return switch (action) {
            case OWNER, RENAME_ATTRIBUTE, ATTRIBUTES -> true;
            default -> false;
        };
    }

    /**
     * Renames one label of an enum type in its position, so the modeled labels
     * keep PostgreSQL's sort order. A missing old label and a new label the
     * type already has both fail, as PostgreSQL rejects them.
     */
    private void renameEnumLabel(
        AlterType alterType,
        String typeName,
        int index,
        List<EnumType> enums
    ) {
        if (index < 0) {
            throw typeNotFound(typeName);
        }

        EnumType enumType = enums.get(index);
        String label = alterType.getValue().getNotExcapedValue();
        String newLabel = alterType.getNewValue().getNotExcapedValue();

        int position = enumType.labels().indexOf(label);

        if (position < 0) {
            throw labelNotFound(typeName, label);
        }

        if (enumType.labels().contains(newLabel)) {
            throw labelAlreadyExists(typeName, newLabel);
        }

        List<String> labels = new ArrayList<>(enumType.labels());

        labels.set(position, newLabel);

        enums.set(index, new EnumType(enumType.name(), labels));
    }

    /**
     * Renames one enum type in its position, keeping its labels, and renames
     * the type of every column of it, scalar and array alike, because a column
     * names its enum type by that name. A modeled enum of the new name is
     * removed, because the {@code DROP TYPE} that freed the name is never seen;
     * a column still naming it keeps that name, so it is modeled as a column of
     * the renamed enum with its labels, the column PostgreSQL would have
     * removed.
     */
    private void renameEnumType(
        AlterType alterType,
        String typeName,
        int index,
        List<EnumType> enums,
        List<Table> tables
    ) {
        if (index < 0) {
            ignoreUnmodeledType(alterType, typeName);

            return;
        }

        String newName = MultiPartName.unquote(alterType.getNewName());
        int replaced = indexOfEnum(enums, newName);
        int renamed = index;

        if (replaced >= 0 && replaced != index) {
            enums.remove(replaced);

            if (replaced < index) {
                renamed--;
            }
        }

        enums.set(renamed, new EnumType(newName, enums.get(renamed).labels()));

        tables.replaceAll(table -> renameColumnEnumType(table, typeName, newName));
    }

    /**
     * Rejects {@code SET SCHEMA} of a modeled enum type, because its columns
     * keep the type in a namespace sqlcj does not model.
     */
    private void moveEnumTypeToSchema(
        AlterType alterType,
        int line,
        String typeName,
        int index
    ) {
        if (index < 0) {
            ignoreUnmodeledType(alterType, typeName);

            return;
        }

        throw unsupportedAlterTypeAction("SET SCHEMA " + alterType.getNewName(), line);
    }

    /**
     * Ignores an {@code ALTER TYPE} action on a type sqlcj does not model,
     * because such a type is none of its enum types.
     *
     * <p>A type name is the statement's own name with its SQL identifier
     * delimiters removed, so {@code public.mood} and {@code mood} are different
     * names. A qualified name that matches no modeled enum may therefore still
     * state one, and fails as a missing type rather than being ignored.
     */
    private void ignoreUnmodeledType(AlterType alterType, String typeName) {
        if (alterType.getName().contains(".")) {
            throw typeNotFound(typeName);
        }
    }

    /** Renames the enum type of every column of {@code oldName}. */
    private Table renameColumnEnumType(Table table, String oldName, String newName) {
        List<Column> columns = new ArrayList<>(table.columns());

        for (int i = 0; i < columns.size(); i++) {
            Column column = columns.get(i);

            if (column.enumType() == null || !column.enumType().equalsIgnoreCase(oldName)) {
                continue;
            }

            columns.set(
                i,
                new Column(
                    column.name(),
                    column.type(),
                    column.nullable(),
                    column.unsupportedType(),
                    newName,
                    column.array(),
                    column.blankPadded()
                )
            );
        }

        return new Table(table.name(), columns, table.constraints(), table.partitionOf());
    }

    /**
     * Inserts one label into an enum type: after the last label by default, and
     * otherwise directly before or after the stated neighbor, so the modeled
     * labels stay in PostgreSQL's sort order.
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
     * Appends one table. A repeated table name fails, as PostgreSQL rejects it,
     * unless the statement declares {@code IF NOT EXISTS}. A clause that states
     * columns sqlcj cannot determine is rejected naming the clause.
     */
    private void applyCreateTable(
        CreateTable createTable,
        int line,
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

        rejectUnsupportedCreateTableClause(createTable, line);

        tables.add(parseTable(createTable, line, enums, tables));
    }

    /**
     * Rejects the {@code CREATE TABLE} clauses whose columns stand nowhere in
     * the schema source: a query, a composite type, and an inherited table,
     * whose parent's columns PostgreSQL copies and keeps following.
     */
    private void rejectUnsupportedCreateTableClause(CreateTable createTable, int line) {
        if (createTable.getSelect() != null) {
            throw unsupportedCreateTableClause("AS SELECT", line);
        }

        if (createTable.getOfType() != null) {
            throw unsupportedCreateTableClause("OF " + createTable.getOfType(), line);
        }

        if (inheritsAnotherTable(createTable)) {
            throw unsupportedCreateTableClause("INHERITS", line);
        }
    }

    /**
     * Reports whether a {@code CREATE TABLE} states {@code INHERITS}, which the
     * parser reports only among the table options, with the case it was written
     * in.
     */
    private boolean inheritsAnotherTable(CreateTable createTable) {
        List<String> options = createTable.getTableOptionsStrings();

        return options != null && options.stream().anyMatch("INHERITS"::equalsIgnoreCase);
    }

    /**
     * Removes every named table, and every partition of one, keeping the order
     * of the remaining ones. A name a preceding name of the same statement
     * already removed as a partition of it is left alone, because it is dropped
     * either way.
     */
    private void applyDropTable(Drop drop, List<Table> tables) {
        for (String tableName : droppedTableNames(drop, tables)) {
            int index = indexOfTable(tables, tableName);

            if (index >= 0) {
                dropTable(tables, index);
            }
        }
    }

    /**
     * The modeled names of one {@code DROP TABLE}, all resolved against the
     * tables as they stand before it drops anything, as PostgreSQL resolves
     * every name of one statement before deleting any of them. A name that is
     * not modeled fails, unless the statement declares {@code IF EXISTS}.
     */
    private List<String> droppedTableNames(Drop drop, List<Table> tables) {
        List<String> names = new ArrayList<>();

        for (net.sf.jsqlparser.schema.Table dropped : drop.getNames()) {
            String tableName = dropped.getUnquotedName();

            if (indexOfTable(tables, tableName) < 0) {
                if (drop.isIfExists()) {
                    continue;
                }

                throw tableNotFound(tableName);
            }

            names.add(tableName);
        }

        return names;
    }

    /**
     * Removes one table and, recursively, every partition of it, as PostgreSQL
     * drops the partitions of a partitioned table along with it.
     */
    private void dropTable(List<Table> tables, int index) {
        String tableName = tables.get(index).name();

        tables.remove(index);

        int partition = indexOfPartition(tables, tableName);

        while (partition >= 0) {
            dropTable(tables, partition);

            partition = indexOfPartition(tables, tableName);
        }
    }

    /** The first partition of {@code parentName}, or {@code -1}. */
    private int indexOfPartition(List<Table> tables, String parentName) {
        for (int i = 0; i < tables.size(); i++) {
            String parent = tables.get(i).partitionOf();

            if (parent != null && parent.equalsIgnoreCase(parentName)) {
                return i;
            }
        }

        return -1;
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
            applyToTableAndPartitions(line, expression, enums, tables, index);
        }
    }

    /**
     * Applies one {@code ALTER TABLE} action to the table at {@code index} and,
     * when the action changes a modeled column, to every partition of that
     * table, recursively, because PostgreSQL applies a partitioned table's
     * column changes to its partitions. A rename of the table itself is not
     * propagated: a partition keeps its own name.
     */
    private void applyToTableAndPartitions(
        int line,
        AlterExpression expression,
        List<EnumType> enums,
        List<Table> tables,
        int index
    ) {
        String tableName = tables.get(index).name();

        tables.set(index, applyAlterExpression(line, expression, enums, tables, index));

        if (!propagatesToPartitions(expression)) {
            return;
        }

        for (int i = 0; i < tables.size(); i++) {
            String parent = tables.get(i).partitionOf();

            if (parent != null && parent.equalsIgnoreCase(tableName)) {
                applyToTableAndPartitions(line, expression, enums, tables, i);
            }
        }
    }

    /**
     * Reports whether one {@code ALTER TABLE} action changes a modeled column
     * and therefore reaches the partitions of its table: {@code ADD COLUMN},
     * {@code DROP COLUMN}, {@code RENAME COLUMN}, a new column type,
     * {@code SET NOT NULL}, and {@code DROP NOT NULL}.
     */
    private boolean propagatesToPartitions(AlterExpression expression) {
        AlterOperation operation = expression.getOperation();

        if (operation == AlterOperation.ADD) {
            return isNotEmpty(expression.getColDataTypeList());
        }

        if (operation == AlterOperation.DROP) {
            return expression.getColumnName() != null;
        }

        if (operation == AlterOperation.RENAME) {
            return expression.getColumnOldName() != null;
        }

        if (operation != AlterOperation.ALTER) {
            return false;
        }

        if (isNotEmpty(expression.getColumnSetDefaultList()) || isNotEmpty(expression.getColumnDropDefaultList())) {
            return false;
        }

        if (isNotEmpty(expression.getColDataTypeList())) {
            return statesNewTypes(expression.getColDataTypeList());
        }

        return isNotEmpty(expression.getColumnSetNotNullList())
            || isNotEmpty(expression.getColumnDropNotNullList());
    }

    /**
     * Applies one {@code ALTER TABLE} action and returns the altered table.
     *
     * <p>An action that cannot change a modeled column's existence, name, type,
     * or nullability is accepted and leaves the table unchanged. An action that
     * would change a column in a way sqlcj does not model, and an action sqlcj
     * does not recognize, is rejected naming the action's own SQL.
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

        if (operation == AlterOperation.ATTACH_PARTITION || operation == AlterOperation.DETACH_PARTITION) {
            return changePartitionLink(expression, tables, table);
        }

        if (isIgnoredConstraintAction(expression) || isIgnoredTableAction(operation)) {
            return table;
        }

        if (operation == AlterOperation.UNSPECIFIC) {
            return ignoreUnspecificActions(line, expression, table);
        }

        throw unsupportedAlterTableAction(line, expression);
    }

    /**
     * Links or unlinks one partition of the altered table, leaving the altered
     * table itself unchanged. {@code ATTACH PARTITION} links the stated table to
     * the altered one, so it follows its column changes from then on, and
     * {@code DETACH PARTITION} ends that link. A partition sqlcj does not model
     * is ignored, like any other statement about an unmodeled table.
     */
    private Table changePartitionLink(
        AlterExpression expression,
        List<Table> tables,
        Table table
    ) {
        if (!(expression instanceof AlterExpressionPartition partition)) {
            return table;
        }

        int index = indexOfReferencedTable(tables, partition.getPartitionTable());

        if (index < 0) {
            return table;
        }

        Table attached = tables.get(index);

        tables.set(
            index,
            new Table(
                attached.name(),
                attached.columns(),
                attached.constraints(),
                expression.getOperation() == AlterOperation.ATTACH_PARTITION
                    ? table.name()
                    : null
            )
        );

        return table;
    }

    /**
     * Reports whether one {@code ALTER TABLE} action is a table-level action the
     * parser has a form of its own for that cannot change a column: the
     * row-level-security switches.
     */
    private boolean isIgnoredTableAction(AlterOperation operation) {
        return switch (operation) {
            case ENABLE_ROW_LEVEL_SECURITY,
                DISABLE_ROW_LEVEL_SECURITY,
                FORCE_ROW_LEVEL_SECURITY,
                NO_FORCE_ROW_LEVEL_SECURITY -> true;
            default -> false;
        };
    }

    /**
     * Accepts the actions the parser has no form of its own for, which it
     * reports as one piece of text running to the end of the statement, so
     * several written actions may stand in it. The text is lexed with the
     * parser's own lexer and split at the commas outside parentheses, and each
     * of its actions must begin as a table-level action that cannot change a
     * column. The first action that does not is rejected, so a modeled action
     * written after such an action is rejected rather than silently skipped.
     */
    private Table ignoreUnspecificActions(int line, AlterExpression expression, Table table) {
        String specifier = expression.getOptionalSpecifier();

        if (specifier == null) {
            throw unsupportedAlterTableAction(line, expression);
        }

        for (List<String> action : topLevelActions(SchemaSourceSplitter.tokens(specifier))) {
            if (!ignoresTableLevelAction(action)) {
                throw unsupportedAlterTableAction(line, String.join(" ", action));
            }
        }

        return table;
    }

    /**
     * Reports whether one action of the unspecific text begins as a
     * PostgreSQL 16 table-level action that cannot change a column: ownership,
     * trigger and rule switches, constraint validation, replica identity,
     * clustering, storage parameters, the tablespace, logging, the access
     * method, inheritance, and the typed-table form. {@code SET SCHEMA} is not
     * among them, because it moves the table out of the namespace sqlcj models.
     */
    private boolean ignoresTableLevelAction(List<String> action) {
        return statesWords(action, 0, "OWNER", "TO")
            || switchesTriggerOrRule(action)
            || statesWords(action, 0, "VALIDATE", "CONSTRAINT")
            || statesWords(action, 0, "REPLICA", "IDENTITY")
            || statesWords(action, 0, "CLUSTER", "ON")
            || statesWords(action, 0, "SET", "WITHOUT", "CLUSTER")
            || statesWords(action, 0, "SET", "WITHOUT", "OIDS")
            || statesWords(action, 0, "SET", "(")
            || statesWords(action, 0, "RESET", "(")
            || statesWords(action, 0, "SET", "TABLESPACE")
            || statesWords(action, 0, "SET", "LOGGED")
            || statesWords(action, 0, "SET", "UNLOGGED")
            || statesWords(action, 0, "SET", "ACCESS", "METHOD")
            || statesWords(action, 0, "INHERIT")
            || statesWords(action, 0, "NO", "INHERIT")
            || statesWords(action, 0, "OF")
            || statesWords(action, 0, "NOT", "OF");
    }

    /**
     * Reports whether one action switches a trigger or a rule:
     * {@code ENABLE} or {@code DISABLE}, and {@code ENABLE REPLICA} or
     * {@code ENABLE ALWAYS}, of either object.
     */
    private boolean switchesTriggerOrRule(List<String> action) {
        for (String object : List.of("TRIGGER", "RULE")) {
            if (
                statesWords(action, 0, "ENABLE", object)
                    || statesWords(action, 0, "DISABLE", object)
                    || statesWords(action, 0, "ENABLE", "REPLICA", object)
                    || statesWords(action, 0, "ENABLE", "ALWAYS", object)
            ) {
                return true;
            }
        }

        return false;
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
     * column's position and nullability, or a nullability change. A column
     * action that cannot change the column sqlcj models leaves the table
     * unchanged, without its column being resolved.
     */
    private Table alterColumns(
        int line,
        AlterExpression expression,
        List<EnumType> enums,
        Table table
    ) {
        if (isNotEmpty(expression.getColumnSetDefaultList()) || isNotEmpty(expression.getColumnDropDefaultList())) {
            return table;
        }

        if (isNotEmpty(expression.getColDataTypeList())) {
            if (statesNewTypes(expression.getColDataTypeList())) {
                return changeColumnTypes(expression, enums, table);
            }

            if (ignoresColumnActions(expression.getColDataTypeList())) {
                return table;
            }

            throw unsupportedAlterTableAction(line, expression);
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

        throw unsupportedAlterTableAction(line, expression);
    }

    /**
     * Reports whether every entry of an {@code ALTER COLUMN} action states a new
     * type. The parser reports other column actions, such as
     * {@code SET STATISTICS} or an identity change, in the same list without a
     * declared type.
     */
    private boolean statesNewTypes(List<AlterExpression.ColumnDataType> definitions) {
        return definitions.stream()
            .allMatch(
                definition -> definition.isWithType() && definition.getColDataType() != null
            );
    }

    /**
     * Reports whether every entry of an {@code ALTER COLUMN} action states a
     * column action that cannot change the column sqlcj models.
     */
    private boolean ignoresColumnActions(List<AlterExpression.ColumnDataType> definitions) {
        return definitions.stream().allMatch(this::ignoresColumnAction);
    }

    /**
     * Reports whether one entry of an {@code ALTER COLUMN} action states an
     * identity change, {@code SET STATISTICS}, {@code SET STORAGE},
     * {@code SET COMPRESSION}, or {@code DROP EXPRESSION}. None of them can
     * change a column's existence, name, type, or nullability, which comes from
     * {@code NOT NULL} alone; PostgreSQL requires a column to be
     * {@code NOT NULL} already before {@code ADD GENERATED ... AS IDENTITY}. The
     * parser reports each of them in the entry list of the action, without a
     * declared type.
     */
    private boolean ignoresColumnAction(AlterExpression.ColumnDataType definition) {
        if (definition.isWithType()) {
            return false;
        }

        if (isNotEmpty(definition.getIdentityAlterations())) {
            return true;
        }

        if (definition.getColDataType() == null) {
            return statesWords(definition.getColumnSpecs(), 0, "DROP", "EXPRESSION");
        }

        if (!"SET".equalsIgnoreCase(definition.getColDataType().getDataType())) {
            return false;
        }

        return statesWords(definition.getColumnSpecs(), 0, "STATISTICS")
            || statesWords(definition.getColumnSpecs(), 0, "STORAGE")
            || statesWords(definition.getColumnSpecs(), 0, "COMPRESSION");
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

        return new Table(table.name(), columns, constraints, table.partitionOf());
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

        return new Table(table.name(), columns, constraints, table.partitionOf());
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

        return new Table(table.name(), columns, constraints, table.partitionOf());
    }

    /**
     * Renames one table in its position, keeping its columns and constraints,
     * and names the new name in every partition of it, so a partition keeps
     * following its parent.
     */
    private Table renameTable(AlterExpression expression, List<Table> tables, int index) {
        String newName = MultiPartName.unquote(expression.getNewTableName());

        int existing = indexOfTable(tables, newName);

        if (existing >= 0 && existing != index) {
            throw tableAlreadyExists(newName);
        }

        Table table = tables.get(index);

        tables.replaceAll(partition -> renamePartitionParent(partition, table.name(), newName));

        return new Table(newName, table.columns(), table.constraints(), table.partitionOf());
    }

    /** Names {@code newName} as the parent of a partition of {@code oldName}. */
    private Table renamePartitionParent(Table table, String oldName, String newName) {
        if (table.partitionOf() == null || !table.partitionOf().equalsIgnoreCase(oldName)) {
            return table;
        }

        return new Table(table.name(), table.columns(), table.constraints(), newName);
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

        return new Table(table.name(), columns, table.constraints(), table.partitionOf());
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

        return new Table(table.name(), columns, table.constraints(), table.partitionOf());
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

    /**
     * The position of the table one statement names beside the table it
     * creates or alters, the source of a {@code LIKE} or {@code PARTITION OF}
     * and the partition of {@code ATTACH} or {@code DETACH PARTITION}, or
     * {@code -1} when sqlcj does not model it.
     *
     * <p>sqlcj models one namespace, so an unqualified and a
     * {@code public}-qualified name are the same table. A name qualified with
     * any other schema names a table sqlcj leaves unmodeled, and fails as a
     * missing table rather than resolving to a modeled table of the same
     * unqualified name.
     */
    private int indexOfReferencedTable(
        List<Table> tables,
        net.sf.jsqlparser.schema.Table referenced
    ) {
        String tableName = referenced.getUnquotedName();
        String declaredSchema = referenced.getSchemaName();

        if (declaredSchema != null) {
            String schemaName = MultiPartName.unquote(declaredSchema);

            if (!"public".equalsIgnoreCase(schemaName)) {
                throw tableNotFound(schemaName + "." + tableName);
            }
        }

        return indexOfTable(tables, tableName);
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

    /**
     * The failure of one rejected {@code ALTER TABLE} action, quoting the action
     * as the parser writes it back, with its whitespace runs collapsed to single
     * spaces.
     */
    private UnsupportedOperationException unsupportedAlterTableAction(
        int line,
        AlterExpression expression
    ) {
        return unsupportedAlterTableAction(
            line,
            expression.toString().replaceAll("\\s+", " ").trim()
        );
    }

    private UnsupportedOperationException unsupportedAlterTableAction(int line, String action) {
        return new UnsupportedOperationException(
            "Unsupported ALTER TABLE action: %s at line %d".formatted(action, line)
        );
    }

    /** The failure of one rejected {@code ALTER TYPE} action, naming the action. */
    private UnsupportedOperationException unsupportedAlterTypeAction(String action, int line) {
        return new UnsupportedOperationException(
            "Unsupported ALTER TYPE action: %s at line %d".formatted(action, line)
        );
    }

    private UnsupportedOperationException unsupportedOpening(String opening, int line) {
        return new UnsupportedOperationException(
            "Unsupported schema statement: %s at line %d".formatted(opening, line)
        );
    }

    /**
     * The failure of one rejected {@code CREATE TABLE} clause, naming the
     * clause.
     */
    private UnsupportedOperationException unsupportedCreateTableClause(String clause, int line) {
        return new UnsupportedOperationException(
            "Unsupported CREATE TABLE clause: %s at line %d".formatted(clause, line)
        );
    }

    /**
     * Parses one table, whose columns come from its own column definitions, in
     * written order, from the modeled source of each {@code LIKE} at its
     * position in the element list, and, for a partition, from its parent
     * before all of them, as PostgreSQL creates them. A repeated column name
     * fails, as PostgreSQL rejects it.
     *
     * <p>A {@code LIKE}'s {@code INCLUDING} and {@code EXCLUDING} options
     * cannot change a copied column's type or nullability and are ignored, and
     * neither {@code LIKE} nor {@code PARTITION OF} copies its source's
     * recorded constraints. Only the table's own table constraints are
     * recorded, in a partition as in any other table.
     *
     * <p>A {@code CREATE TABLE} with neither an element list, the empty element
     * list {@code ()}, nor {@code PARTITION OF} states columns sqlcj cannot
     * determine and is rejected as unsupported table DDL.
     */
    private Table parseTable(
        CreateTable createTable,
        int line,
        List<EnumType> enums,
        List<Table> tables
    ) {
        String tableName = createTable.getTable().getUnquotedName();

        List<Column> columns = new ArrayList<>();
        List<Constraint> constraints = new ArrayList<>();
        String partitionOf = null;

        if (createTable.getPartitionOf() != null) {
            Table parent = referencedTable(tables, createTable.getPartitionOf());

            partitionOf = parent.name();
            columns.addAll(parent.columns());
        }

        List<TableElement> elements = createTable.getTableElements();

        if (elements == null) {
            if (partitionOf == null && !statesEmptyElementList(createTable)) {
                throw unsupportedOpening("CREATE TABLE", line);
            }
        } else {
            for (TableElement element : elements) {
                if (element instanceof ColumnDefinition definition) {
                    addColumn(columns, tableName, parseColumn(definition, enums));
                    constraints.addAll(parseColumnConstraints(definition));
                } else if (element instanceof LikeClause like) {
                    for (Column column : referencedTable(tables, like.getTable()).columns()) {
                        addColumn(columns, tableName, column);
                    }
                }
            }
        }

        constraints.addAll(parseTableConstraints(createTable));

        return new Table(
            tableName,
            columns,
            constraints,
            partitionOf
        );
    }

    /**
     * Reports whether a {@code CREATE TABLE} states the empty element list
     * {@code ()}, a table without columns, which the parser reports as the
     * first of the table options instead of as an element list.
     */
    private boolean statesEmptyElementList(CreateTable createTable) {
        List<String> options = createTable.getTableOptionsStrings();

        return options != null && !options.isEmpty() && "()".equals(options.get(0));
    }

    /**
     * The modeled table a {@code LIKE} or {@code PARTITION OF} names as the
     * source of its columns. A source sqlcj does not model fails as a missing
     * table, because the created table's columns would otherwise be unknown.
     */
    private Table referencedTable(
        List<Table> tables,
        net.sf.jsqlparser.schema.Table referenced
    ) {
        int index = indexOfReferencedTable(tables, referenced);

        if (index < 0) {
            throw tableNotFound(referenced.getUnquotedName());
        }

        return tables.get(index);
    }

    private void addColumn(List<Column> columns, String tableName, Column column) {
        if (indexOfColumn(columns, column.name()) >= 0) {
            throw columnAlreadyExists(tableName, column.name());
        }

        columns.add(column);
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
     * Foreign key, check, and exclusion constraints are accepted but not
     * modeled, because they do not affect the generated Java types.
     */
    private boolean isIgnoredTableConstraint(Index index) {
        return index instanceof ForeignKeyIndex
            || index instanceof CheckConstraint
            || index instanceof ExcludeConstraint;
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
