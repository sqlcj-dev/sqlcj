package dev.sqlcj.generator;

import dev.sqlcj.analysis.QueryGroupModel;

import java.util.List;

/**
 * Generates the source files of one package from the query groups of that
 * package.
 *
 * <p>A generator is used for one package: every group of the package is
 * generated through the same generator, in configuration order, and
 * {@link #generateRows()} then yields the row records those groups share.
 */
public interface CodeGenerator {

    /**
     * Generates the repository of one query group and records the row records
     * it returns, which the package generates once and every repository
     * returning them shares.
     */
    GeneratedFile generate(QueryGroupModel group);

    /**
     * Generates one row record per table whose complete row a generated group
     * returns, in the order the groups first returned them.
     */
    List<GeneratedFile> generateRows();
}
