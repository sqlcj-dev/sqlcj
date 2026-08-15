package dev.sqlcj.schema.parser;

import dev.sqlcj.schema.Schema;

public interface SchemaParser {

    Schema parse(String sql);
}
