package com.example.app;

import com.example.app.db.CreateAuthor;
import com.example.app.db.DeleteAuthor;
import com.example.app.db.GetAuthor;
import com.example.app.db.ListAuthors;
import com.example.app.db.UpdateAuthorBio;
import dev.sqlcj.runtime.JdbcQueryExecutor;
import dev.sqlcj.runtime.QueryExecutor;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * Runs the documented sqlcj workflow against PostgreSQL through generated
 * code: create, read, list, update, a missing-row read, a committed
 * transaction, a rolled back transaction, and delete.
 *
 * <p>Every step is checked, so the process exits non-zero as soon as one
 * generated operation returns an unexpected result.
 *
 * <p>The verification harness applies {@code sql/schema.sql} to an empty
 * {@code authors} table before this application runs.
 */
public final class App {

    private App() {
    }

    public static void main(String[] args) throws SQLException {
        DataSource dataSource = dataSource();

        QueryExecutor executor = new JdbcQueryExecutor(dataSource);

        GetAuthor getAuthor = new GetAuthor(executor);
        ListAuthors listAuthors = new ListAuthors(executor);

        CreateAuthor.CreateAuthorResult created = new CreateAuthor(executor)
                .createAuthor("Ada Lovelace", "First programmer");

        check(created != null, "CreateAuthor returned no row");
        check(created.id() != null, "CreateAuthor returned no database-generated id");
        checkEquals("Ada Lovelace", created.name(), "CreateAuthor name");
        checkEquals("First programmer", created.bio(), "CreateAuthor bio");

        System.out.println("created: " + created.id() + " " + created.name());

        GetAuthor.GetAuthorResult read = getAuthor.getAuthor(created.id());

        check(read != null, "GetAuthor returned no row for the created author");
        checkEquals(created.id(), read.id(), "GetAuthor id");
        checkEquals("Ada Lovelace", read.name(), "GetAuthor name");
        checkEquals("First programmer", read.bio(), "GetAuthor bio");

        System.out.println("read: " + read.name() + " / " + read.bio());

        List<ListAuthors.ListAuthorsResult> listed = listAuthors.listAuthors();

        checkEquals(1, listed.size(), "ListAuthors row count after create");
        checkEquals(created.id(), listed.get(0).id(), "ListAuthors id");
        checkEquals("Ada Lovelace", listed.get(0).name(), "ListAuthors name");

        System.out.println("listed: " + listed.get(0).id() + " " + listed.get(0).name());

        int updatedRows = new UpdateAuthorBio(executor).updateAuthorBio(created.id(), "Mathematician");

        checkEquals(1, updatedRows, "UpdateAuthorBio affected rows");
        checkEquals("Mathematician", getAuthor.getAuthor(created.id()).bio(), "bio after update");

        System.out.println("updated rows: " + updatedRows);

        check(getAuthor.getAuthor(-1L) == null, "GetAuthor must return null for a missing row");

        System.out.println("missing row: null");

        Long committedId = writeAndCommit(dataSource);

        GetAuthor.GetAuthorResult committed = getAuthor.getAuthor(committedId);

        check(committed != null, "the committed author is not readable after commit");
        checkEquals("Grace Hopper", committed.name(), "committed name");
        checkEquals("Compiler pioneer", committed.bio(), "committed bio");

        System.out.println("committed: " + committed.bio());

        Long discardedId = writeAndRollback(dataSource);

        check(getAuthor.getAuthor(discardedId) == null, "the rolled back author must not be readable");

        System.out.println("rolled back: null");

        int deletedRows = new DeleteAuthor(executor).deleteAuthor(created.id());

        checkEquals(1, deletedRows, "DeleteAuthor affected rows");
        check(getAuthor.getAuthor(created.id()) == null, "the deleted author must not be readable");

        System.out.println("deleted rows: " + deletedRows);

        List<ListAuthors.ListAuthorsResult> remaining = listAuthors.listAuthors();

        checkEquals(1, remaining.size(), "ListAuthors row count after delete");
        checkEquals(committedId, remaining.get(0).id(), "remaining author id");

        System.out.println("sqlcj sample verification passed");
    }

    /**
     * Writes through a caller-owned connection with auto-commit disabled and
     * commits, so the written row must be visible to a later read.
     */
    private static Long writeAndCommit(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            QueryExecutor transactional = new JdbcQueryExecutor(connection);

            CreateAuthor.CreateAuthorResult author = new CreateAuthor(transactional)
                    .createAuthor("Grace Hopper", null);

            check(author != null, "CreateAuthor returned no row inside the committed transaction");
            checkEquals(null, author.bio(), "committed bio before update");

            int rows = new UpdateAuthorBio(transactional).updateAuthorBio(author.id(), "Compiler pioneer");

            checkEquals(1, rows, "UpdateAuthorBio affected rows inside the committed transaction");

            connection.commit();

            return author.id();
        }
    }

    /**
     * Writes through a caller-owned connection with auto-commit disabled and
     * rolls back, so the written row must not be visible to a later read.
     */
    private static Long writeAndRollback(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            QueryExecutor transactional = new JdbcQueryExecutor(connection);

            CreateAuthor.CreateAuthorResult author = new CreateAuthor(transactional)
                    .createAuthor("Temporary Author", null);

            check(author != null, "CreateAuthor returned no row inside the rolled back transaction");

            int rows = new UpdateAuthorBio(transactional).updateAuthorBio(author.id(), "never stored");

            checkEquals(1, rows, "UpdateAuthorBio affected rows inside the rolled back transaction");

            connection.rollback();

            return author.id();
        }
    }

    private static DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();

        dataSource.setUrl(environment("SQLCJ_SAMPLE_JDBC_URL", "jdbc:postgresql://localhost:5432/quickstart"));
        dataSource.setUser(environment("SQLCJ_SAMPLE_DB_USER", "quickstart"));
        dataSource.setPassword(environment("SQLCJ_SAMPLE_DB_PASSWORD", "quickstart"));

        return dataSource;
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);

        return value == null || value.isBlank() ? fallback : value;
    }

    private static void checkEquals(Object expected, Object actual, String description) {
        check(
                Objects.equals(expected, actual),
                description + ": expected " + expected + " but was " + actual
        );
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
