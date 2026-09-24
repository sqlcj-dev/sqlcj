package dev.sqlcj.generator;

import dev.sqlcj.analysis.QueryGroupModel;

public interface CodeGenerator {

    GeneratedFile generate(QueryGroupModel group);
}
