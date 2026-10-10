package dev.sqlcj.schema.parser;

import net.sf.jsqlparser.schema.MultiPartName;
import net.sf.jsqlparser.schema.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * The one PostgreSQL namespace sqlcj models.
 *
 * <p>An unqualified and a {@code public}-qualified name are the same name, so
 * {@code users}, {@code public.users}, and {@code "public"."users"} name one
 * table and {@code mood}, {@code public.mood}, and {@code "public"."mood"} name
 * one type. A name qualified with any other schema names an object sqlcj leaves
 * unmodeled. A schema source's {@code search_path} is never read, so it never
 * decides which schema an unqualified name belongs to.
 *
 * <p>A schema qualifier is compared without its SQL identifier delimiters and
 * case-insensitively, as every other name sqlcj resolves is.
 */
public final class SchemaNamespace {

    /** The one schema sqlcj models. */
    private static final String MODELED_SCHEMA = "public";

    private SchemaNamespace() {
    }

    /**
     * Reports whether a declared schema qualifier names the modeled schema. A
     * name without a qualifier does, and a qualified name does when its
     * qualifier is {@code public}.
     */
    public static boolean isModeled(String declaredSchema) {
        return declaredSchema == null || MODELED_SCHEMA.equalsIgnoreCase(MultiPartName.unquote(declaredSchema));
    }

    /**
     * Reports whether sqlcj models the schema of the table one statement or
     * query names.
     */
    public static boolean isModeled(Table table) {
        return isModeled(table.getSchemaName());
    }

    /**
     * The name a diagnostic states for the table one statement or query names:
     * its own name, qualified with its schema when sqlcj does not model that
     * schema, each part without its SQL identifier delimiters.
     */
    public static String declaredName(Table table) {
        String declaredSchema = table.getSchemaName();

        return isModeled(declaredSchema)
            ? table.getUnquotedName()
            : MultiPartName.unquote(declaredSchema) + "." + table.getUnquotedName();
    }

    /**
     * The modeled name a declared type name states, or {@code null} when it is
     * qualified with a schema sqlcj does not model.
     *
     * <p>The parser reports a type name as one text, its qualifier and its SQL
     * identifier delimiters included, so the text is split at the dots that
     * stand outside double quotes and each part is read without its delimiters.
     */
    public static String modeledTypeName(String declaredType) {
        List<String> parts = parts(declaredType);

        if (parts.size() == 1) {
            return parts.get(0);
        }

        return parts.size() == 2 && MODELED_SCHEMA.equalsIgnoreCase(parts.get(0))
            ? parts.get(1)
            : null;
    }

    /**
     * The parts of one possibly qualified name text, split at the dots that
     * stand outside double quotes, each without its SQL identifier delimiters.
     */
    private static List<String> parts(String declaredName) {
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        boolean delimited = false;

        for (int i = 0; i < declaredName.length(); i++) {
            char character = declaredName.charAt(i);

            if (character == '"') {
                delimited = !delimited;
            } else if (character == '.' && !delimited) {
                parts.add(part.toString());
                part.setLength(0);
            } else {
                part.append(character);
            }
        }

        parts.add(part.toString());

        return parts;
    }
}
