package com.example.app;

import com.example.app.db.AuthorRepository;
import com.example.app.db.AuthorsRow;
import com.example.app.db.BookFormat;
import com.example.app.db.BooksRow;
import dev.sqlcj.runtime.JdbcQueryExecutor;
import dev.sqlcj.runtime.QueryCardinalityException;
import dev.sqlcj.runtime.QueryExecutor;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs the documented sqlcj workflow against PostgreSQL through generated
 * code: create, read, optional read, list, update, a missing-row read, a
 * committed transaction, a rolled back transaction, a case-insensitive search,
 * a count, a page, a book insert, a left-joined projection, a cataloged book
 * insert, a read by enum value, an optional filter by name, a read by id list,
 * a grouped book count, a rename, an upsert, and delete.
 *
 * <p>All eighteen named queries are methods of one generated
 * {@link AuthorRepository}. The repository is constructed once per execution
 * context: once from the {@code DataSource}-backed executor, and once more per
 * transaction from a caller-owned connection.
 *
 * <p>Create, read, optional read, list, search, page, filter, upsert, and the
 * read by id list each return one complete {@code authors} row, so all nine
 * share the single top-level {@link AuthorsRow} record of the generated
 * package, and the two queries that return a complete {@code books} row share
 * the top-level {@link BooksRow} record. A query with its own result shape
 * generates its own nested record: {@link AuthorRepository.CountAuthorsResult}
 * carries the non-null {@code Long} count,
 * {@link AuthorRepository.ListAuthorBooksResult} carries an author name beside
 * the title of the left-joined {@code books} row, which is {@code null} for an
 * author without a book, and
 * {@link AuthorRepository.CountBooksByAuthorResult} carries an author id beside
 * the non-null {@code Long} number of that author's books.
 *
 * <p>Row absence is expressed by the {@code :optional} {@code FindAuthor}
 * query, which returns an empty {@link Optional}, while the {@code :one}
 * {@code GetAuthor} query requires exactly one row and raises a
 * {@link QueryCardinalityException} when none matches.
 *
 * <p>The third migration adds an enum, an array, and a {@code JSONB} column to
 * {@code books}, so {@link BooksRow} carries a {@link BookFormat} constant of
 * the generated Java enum, a {@code List<String>} of tags, and the
 * {@code JSONB} document as JSON text, which PostgreSQL returns normalized.
 * The enum is also bound as a query parameter to select books by format.
 *
 * <p>The fourth migration adds the nullable {@code updated_at} column to
 * {@code authors}, so {@link AuthorsRow} carries it as a
 * {@link java.time.LocalDateTime}. The rename and the upsert set it with
 * {@code now()} without binding a parameter for it, and the upsert targets the
 * existing author of the conflicting id, so the {@code BIGSERIAL} key is never
 * given an explicit new value. The filter binds its one {@code :name}
 * parameter to both of its occurrences, so a {@code null} argument returns
 * every author and a name returns only that author, and the read by id list
 * binds one {@code List} as one server array, so an empty list matches no row.
 *
 * <p>Every step is checked, so the process exits non-zero as soon as one
 * generated operation returns an unexpected result.
 *
 * <p>The verification harness applies the {@code sql/migrations} files in
 * version order to empty {@code authors} and {@code books} tables, without the
 * {@code book_format} type, before this application runs.
 */
public final class App {

    private App() {
    }

    public static void main(String[] args) throws SQLException {
        DataSource dataSource = dataSource();

        QueryExecutor executor = new JdbcQueryExecutor(dataSource);

        AuthorRepository authors = new AuthorRepository(executor);

        AuthorsRow created = authors.createAuthor("Ada Lovelace", "First programmer");

        check(created != null, "CreateAuthor returned no row");
        check(created.id() != null, "CreateAuthor returned no database-generated id");
        checkEquals("Ada Lovelace", created.name(), "CreateAuthor name");
        checkEquals("First programmer", created.bio(), "CreateAuthor bio");

        System.out.println("created: " + created.id() + " " + created.name());

        AuthorsRow read = authors.getAuthor(created.id());

        check(read != null, "GetAuthor returned no row for the created author");
        checkEquals(created.id(), read.id(), "GetAuthor id");
        checkEquals("Ada Lovelace", read.name(), "GetAuthor name");
        checkEquals("First programmer", read.bio(), "GetAuthor bio");

        System.out.println("read: " + read.name() + " / " + read.bio());

        Optional<AuthorsRow> found = authors.findAuthor(created.id());

        check(found.isPresent(), "FindAuthor returned no row for the created author");
        checkEquals("Ada Lovelace", found.get().name(), "FindAuthor name");

        System.out.println("found: " + found.get().name());

        List<AuthorsRow> listed = authors.listAuthors();

        checkEquals(1, listed.size(), "ListAuthors row count after create");
        checkEquals(created.id(), listed.get(0).id(), "ListAuthors id");
        checkEquals("Ada Lovelace", listed.get(0).name(), "ListAuthors name");

        System.out.println("listed: " + listed.get(0).id() + " " + listed.get(0).name());

        int updatedRows = authors.updateAuthorBio(created.id(), "Mathematician");

        checkEquals(1, updatedRows, "UpdateAuthorBio affected rows");
        checkEquals("Mathematician", authors.getAuthor(created.id()).bio(), "bio after update");

        System.out.println("updated rows: " + updatedRows);

        check(
            authors.findAuthor(-1L).isEmpty(),
            "FindAuthor must return an empty result for a missing row"
        );

        System.out.println("missing row: empty");

        try {
            authors.getAuthor(-1L);

            throw new IllegalStateException("GetAuthor must fail for a missing row");
        } catch (QueryCardinalityException e) {
            System.out.println("missing row rejected: " + e.getMessage());
        }

        Long committedId = writeAndCommit(dataSource);

        AuthorsRow committed = authors.getAuthor(committedId);

        check(committed != null, "the committed author is not readable after commit");
        checkEquals("Grace Hopper", committed.name(), "committed name");
        checkEquals("Compiler pioneer", committed.bio(), "committed bio");

        System.out.println("committed: " + committed.bio());

        Long discardedId = writeAndRollback(dataSource);

        check(
            authors.findAuthor(discardedId).isEmpty(),
            "the rolled back author must not be readable"
        );

        System.out.println("rolled back: empty");

        List<AuthorsRow> searched = authors.searchAuthors("%lovelace%");

        checkEquals(1, searched.size(), "SearchAuthors row count");
        checkEquals(created.id(), searched.get(0).id(), "SearchAuthors id");
        checkEquals("Ada Lovelace", searched.get(0).name(), "SearchAuthors name");

        System.out.println("searched: " + searched.get(0).id() + " " + searched.get(0).name());

        AuthorRepository.CountAuthorsResult count = authors.countAuthors();

        check(count != null, "CountAuthors returned no row");
        checkEquals(2L, count.total(), "CountAuthors total");

        System.out.println("count: " + count.total());

        List<AuthorsRow> page = authors.listAuthorPage(1, 1);

        checkEquals(1, page.size(), "ListAuthorPage row count");
        checkEquals(committedId, page.get(0).id(), "ListAuthorPage id");
        checkEquals("Grace Hopper", page.get(0).name(), "ListAuthorPage name");

        System.out.println("page: " + page.get(0).id() + " " + page.get(0).name());

        String bookTitle = "The Education of a Computer";

        int bookRows = authors.createBook(committedId, bookTitle);

        checkEquals(1, bookRows, "CreateBook affected rows");

        System.out.println("created book rows: " + bookRows);

        List<AuthorRepository.ListAuthorBooksResult> books = authors.listAuthorBooks();

        checkEquals(2, books.size(), "ListAuthorBooks row count");
        checkEquals("Ada Lovelace", books.get(0).name(), "ListAuthorBooks unmatched author name");
        checkEquals(null, books.get(0).title(), "ListAuthorBooks unmatched title");
        checkEquals("Grace Hopper", books.get(1).name(), "ListAuthorBooks matched author name");
        checkEquals(bookTitle, books.get(1).title(), "ListAuthorBooks matched title");

        System.out.println(
            "author books: " + books.get(0).name() + " / " + books.get(0).title()
                + ", " + books.get(1).name() + " / " + books.get(1).title()
        );

        List<String> catalogedTags = List.of("compilers", "history");
        String catalogedDetails = "{\"pages\": 320}";

        BooksRow cataloged = authors.createCatalogedBook(
            committedId,
            "Automatic Programming",
            BookFormat.HARDCOVER,
            catalogedTags,
            catalogedDetails
        );

        check(cataloged != null, "CreateCatalogedBook returned no row");
        check(cataloged.id() != null, "CreateCatalogedBook returned no database-generated id");
        checkEquals(committedId, cataloged.authorId(), "CreateCatalogedBook author id");
        checkEquals("Automatic Programming", cataloged.title(), "CreateCatalogedBook title");
        checkEquals(BookFormat.HARDCOVER, cataloged.format(), "CreateCatalogedBook format");
        checkEquals(catalogedTags, cataloged.tags(), "CreateCatalogedBook tags");
        checkEquals(catalogedDetails, cataloged.details(), "CreateCatalogedBook details");

        List<BooksRow> hardcovers = authors.listBooksByFormat(BookFormat.HARDCOVER);

        checkEquals(1, hardcovers.size(), "ListBooksByFormat row count");
        checkEquals(cataloged.id(), hardcovers.get(0).id(), "ListBooksByFormat id");
        checkEquals(BookFormat.HARDCOVER, hardcovers.get(0).format(), "ListBooksByFormat format");
        checkEquals(catalogedTags, hardcovers.get(0).tags(), "ListBooksByFormat tags");
        checkEquals(catalogedDetails, hardcovers.get(0).details(), "ListBooksByFormat details");

        System.out.println(
            "cataloged book: " + cataloged.id() + " " + cataloged.format()
                + " " + cataloged.tags() + " " + cataloged.details()
        );

        List<AuthorsRow> unfiltered = authors.filterAuthors(null);

        checkEquals(2, unfiltered.size(), "FilterAuthors row count without a name");
        checkEquals(created.id(), unfiltered.get(0).id(), "FilterAuthors first unfiltered id");
        checkEquals(committedId, unfiltered.get(1).id(), "FilterAuthors second unfiltered id");

        List<AuthorsRow> filtered = authors.filterAuthors("Ada Lovelace");

        checkEquals(1, filtered.size(), "FilterAuthors row count for one name");
        checkEquals(created.id(), filtered.get(0).id(), "FilterAuthors filtered id");
        checkEquals("Ada Lovelace", filtered.get(0).name(), "FilterAuthors filtered name");

        System.out.println(
            "filtered: " + unfiltered.size() + " without a name, "
                + filtered.get(0).name() + " by name"
        );

        List<AuthorsRow> byIds = authors.listAuthorsByIds(List.of(created.id(), committedId));

        checkEquals(2, byIds.size(), "ListAuthorsByIds row count for two ids");
        checkEquals(created.id(), byIds.get(0).id(), "ListAuthorsByIds first id");
        checkEquals(committedId, byIds.get(1).id(), "ListAuthorsByIds second id");
        checkEquals(
            0,
            authors.listAuthorsByIds(List.of()).size(),
            "ListAuthorsByIds row count for an empty list"
        );

        System.out.println("by ids: " + byIds.get(0).id() + " " + byIds.get(1).id());

        List<AuthorRepository.CountBooksByAuthorResult> bookCounts = authors.countBooksByAuthor();

        checkEquals(1, bookCounts.size(), "CountBooksByAuthor row count");
        checkEquals(committedId, bookCounts.get(0).authorId(), "CountBooksByAuthor author id");
        checkEquals(2L, bookCounts.get(0).books(), "CountBooksByAuthor count");

        System.out.println(
            "book counts: " + bookCounts.get(0).authorId() + " / " + bookCounts.get(0).books()
        );

        int renamedRows = authors.renameAuthor("Ada Byron", created.id());

        checkEquals(1, renamedRows, "RenameAuthor affected rows");

        AuthorsRow renamed = authors.getAuthor(created.id());

        checkEquals("Ada Byron", renamed.name(), "name after rename");
        check(renamed.updatedAt() != null, "RenameAuthor left updatedAt null");

        System.out.println("renamed: " + renamed.name() + " at " + renamed.updatedAt());

        AuthorsRow upserted = authors.upsertAuthor(
            committedId,
            "Grace Murray Hopper",
            "Rear admiral"
        );

        check(upserted != null, "UpsertAuthor returned no row");
        checkEquals(committedId, upserted.id(), "UpsertAuthor id");
        checkEquals("Grace Murray Hopper", upserted.name(), "UpsertAuthor name");
        checkEquals("Rear admiral", upserted.bio(), "UpsertAuthor bio");
        check(upserted.updatedAt() != null, "UpsertAuthor left updatedAt null");

        System.out.println("upserted: " + upserted.name() + " / " + upserted.bio());

        int deletedRows = authors.deleteAuthor(created.id());

        checkEquals(1, deletedRows, "DeleteAuthor affected rows");
        check(
            authors.findAuthor(created.id()).isEmpty(),
            "the deleted author must not be readable"
        );

        System.out.println("deleted rows: " + deletedRows);

        List<AuthorsRow> remaining = authors.listAuthors();

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

            AuthorRepository transactionalAuthors = new AuthorRepository(new JdbcQueryExecutor(connection));

            AuthorsRow author = transactionalAuthors.createAuthor("Grace Hopper", null);

            check(author != null, "CreateAuthor returned no row inside the committed transaction");
            checkEquals(null, author.bio(), "committed bio before update");

            int rows = transactionalAuthors.updateAuthorBio(author.id(), "Compiler pioneer");

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

            AuthorRepository transactionalAuthors = new AuthorRepository(new JdbcQueryExecutor(connection));

            AuthorsRow author = transactionalAuthors.createAuthor("Temporary Author", null);

            check(author != null, "CreateAuthor returned no row inside the rolled back transaction");

            int rows = transactionalAuthors.updateAuthorBio(author.id(), "never stored");

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
