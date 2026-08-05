package dev.sqlcj.generator;

import dev.sqlcj.analysis.QueryModel;

public interface CodeGenerator {

    GeneratedFile generate(QueryModel query);
}
