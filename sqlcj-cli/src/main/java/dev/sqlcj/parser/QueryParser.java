package dev.sqlcj.parser;

import java.util.List;

public interface QueryParser {

    List<Query> parse(String source);
}
