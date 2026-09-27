package dev.sqlcj.schema.parser;

import dev.sqlcj.schema.Schema;

import java.util.List;

public interface SchemaParser {

    /**
     * Applies the statements of one schema source, in order, to {@code schema}
     * and returns the schema they leave, so a statement of a later source sees
     * the tables of the earlier ones.
     */
    Schema parse(Schema schema, String sql);

    /** Applies one schema source to an empty schema. */
    default Schema parse(String sql) {
        return parse(new Schema(List.of()), sql);
    }
}
