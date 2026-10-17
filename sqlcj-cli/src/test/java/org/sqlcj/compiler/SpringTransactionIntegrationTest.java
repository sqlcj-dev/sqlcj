package org.sqlcj.compiler;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlcj.config.Config;
import org.sqlcj.config.JavaConfig;
import org.sqlcj.config.SqlConfig;
import org.sqlcj.runtime.JdbcQueryExecutor;
import org.sqlcj.runtime.QueryExecutor;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import javax.sql.DataSource;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves against a real PostgreSQL instance that a generated repository built
 * over {@code new JdbcQueryExecutor(new TransactionAwareDataSourceProxy(
 * dataSource))} takes part in a transaction Spring manages, both with
 * {@code DataSourceTransactionManager} and with {@code JpaTransactionManager}
 * beside a JPA write, while a repository built over the plain
 * {@code DataSource} stays outside that transaction.
 *
 * <p>Spring and Hibernate are test-scoped only: the runtime and the generated
 * code depend on neither. Each transaction manager is given the plain
 * {@code DataSource}, or the entity factory built over it, and never the
 * transaction-aware proxy.
 *
 * <p>The whole class is skipped when Docker is unavailable, so that
 * {@code mvn test} stays runnable without Docker. Setting
 * {@code -Dsqlcj.test.requireDocker=true}, as continuous integration does,
 * turns an unavailable Docker into a failure instead of a skip.
 */
@EnabledIf("dockerAvailable")
class SpringTransactionIntegrationTest {

    /**
     * System property that forbids skipping these tests. Continuous
     * integration sets it so that a broken Docker environment fails the build
     * instead of silently reducing coverage.
     */
    private static final String REQUIRE_DOCKER_PROPERTY = "sqlcj.test.requireDocker";

    /** The configured query-group name, which generates {@code AuthorsRepository}. */
    private static final String GROUP = "Authors";

    /** The table both the generated repository and the test entity map. */
    private static final String SCHEMA = """
        CREATE TABLE authors
        (
            id   BIGINT PRIMARY KEY,
            name VARCHAR(255) NOT NULL
        );
        """;

    /** One write and one ordered read, which is all these tests need. */
    private static final String QUERIES = """
        -- name: InsertAuthor :exec
        INSERT INTO authors (id, name)
        VALUES ($1, $2);

        -- name: ListAuthorNames :many
        SELECT name
        FROM authors
        ORDER BY id;
        """;

    @TempDir
    static Path tempDir;

    private static PostgreSQLContainer<?> postgres;

    /** The plain data source, which every transaction manager is given. */
    private static DataSource dataSource;

    /** The data source a repository is bound to in order to take part. */
    private static DataSource transactionalDataSource;

    private static LocalContainerEntityManagerFactoryBean entityManagerFactoryBean;

    private static EntityManagerFactory entityManagerFactory;

    /** The shared {@code EntityManager} {@code @PersistenceContext} injects. */
    private static EntityManager entityManager;

    private static URLClassLoader classLoader;

    static boolean dockerAvailable() {
        boolean available = DockerClientFactory.instance().isDockerAvailable();

        if (!available && Boolean.getBoolean(REQUIRE_DOCKER_PROPERTY)) {
            throw new IllegalStateException(
                "Docker is unavailable, but " + REQUIRE_DOCKER_PROPERTY + " requires these tests to run."
            );
        }

        return available;
    }

    /**
     * Starts the database, generates and compiles the one query group these
     * tests run, and builds the entity factory over the plain data source.
     */
    @BeforeAll
    static void startPostgresAndBuildFixtures() throws Exception {
        postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine")
        );

        postgres.start();

        PGSimpleDataSource postgresDataSource = new PGSimpleDataSource();

        postgresDataSource.setUrl(postgres.getJdbcUrl());
        postgresDataSource.setUser(postgres.getUsername());
        postgresDataSource.setPassword(postgres.getPassword());

        dataSource = postgresDataSource;
        transactionalDataSource = new TransactionAwareDataSourceProxy(dataSource);

        classLoader = classLoader(generateAndCompile());

        LocalContainerEntityManagerFactoryBean factoryBean = new LocalContainerEntityManagerFactoryBean();

        factoryBean.setDataSource(dataSource);
        factoryBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factoryBean.setManagedTypes(
            PersistenceManagedTypes.of(AuthorEntity.class.getName())
        );
        factoryBean.afterPropertiesSet();

        entityManagerFactoryBean = factoryBean;
        entityManagerFactory = factoryBean.getObject();
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(
            entityManagerFactory
        );
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (classLoader != null) {
            classLoader.close();
        }

        if (entityManagerFactoryBean != null) {
            entityManagerFactoryBean.destroy();
        }

        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void resetDatabase() throws Exception {
        execute("DROP TABLE IF EXISTS authors");
        execute(SCHEMA);
    }

    /**
     * Two generated writes made through the proxied repository inside a
     * {@code DataSourceTransactionManager} transaction run on the
     * transaction's own connection: a read on the plain data source sees
     * neither of them while the transaction is open, and both are stored once
     * it commits.
     */
    @Test
    void shouldCommitGeneratedWritesWithTheDataSourceTransactionManager() {
        Object transactional = repository(transactionalDataSource);
        Object plain = repository(dataSource);

        dataSourceTransactions().execute(status -> {
            assertEquals(1, insertAuthor(transactional, 1L, "Alice"));
            assertEquals(1, insertAuthor(transactional, 2L, "Bob"));

            assertEquals(List.of(), authorNames(plain));

            return null;
        });

        assertEquals(List.of("Alice", "Bob"), authorNames(plain));
    }

    /**
     * The same two generated writes are rolled back when the callback fails:
     * the failure leaves the transaction as the same instance, and no row
     * remains.
     */
    @Test
    void shouldRollBackGeneratedWritesWithTheDataSourceTransactionManager() {
        Object transactional = repository(transactionalDataSource);
        Object plain = repository(dataSource);

        CallbackFailure failure = new CallbackFailure();

        assertSame(
            failure,
            assertThrows(
                CallbackFailure.class,
                () -> dataSourceTransactions().execute(status -> {
                    assertEquals(1, insertAuthor(transactional, 1L, "Alice"));
                    assertEquals(1, insertAuthor(transactional, 2L, "Bob"));

                    throw failure;
                })
            )
        );

        assertEquals(List.of(), authorNames(plain));
    }

    /**
     * A repository bound to the plain data source does not take part: its
     * write runs on a connection of its own and survives the rollback that
     * removes the write made through the proxied repository.
     */
    @Test
    void shouldLeaveAPlainDataSourceRepositoryOutsideTheTransaction() {
        Object transactional = repository(transactionalDataSource);
        Object plain = repository(dataSource);

        CallbackFailure failure = new CallbackFailure();

        assertSame(
            failure,
            assertThrows(
                CallbackFailure.class,
                () -> dataSourceTransactions().execute(status -> {
                    assertEquals(1, insertAuthor(transactional, 1L, "Transactional"));
                    assertEquals(1, insertAuthor(plain, 2L, "Outside"));

                    throw failure;
                })
            )
        );

        assertEquals(List.of("Outside"), authorNames(plain));
    }

    /**
     * A JPA write and a generated write made in one
     * {@code JpaTransactionManager} transaction share that transaction's
     * connection: a read on the plain data source sees neither while the
     * transaction is open, and both are stored once it commits.
     */
    @Test
    void shouldCommitAJpaWriteAndAGeneratedWriteInOneTransaction() {
        Object transactional = repository(transactionalDataSource);
        Object plain = repository(dataSource);

        jpaTransactions().execute(status -> {
            entityManager.persist(new AuthorEntity(1L, "Persisted"));

            assertEquals(1, insertAuthor(transactional, 2L, "Generated"));

            assertEquals(List.of(), authorNames(plain));

            return null;
        });

        assertEquals(List.of("Persisted", "Generated"), authorNames(plain));
    }

    /**
     * The flushed JPA write and the generated write are rolled back together
     * when the callback fails, so neither row remains.
     */
    @Test
    void shouldRollBackAJpaWriteAndAGeneratedWriteInOneTransaction() {
        Object transactional = repository(transactionalDataSource);
        Object plain = repository(dataSource);

        CallbackFailure failure = new CallbackFailure();

        assertSame(
            failure,
            assertThrows(
                CallbackFailure.class,
                () -> jpaTransactions().execute(status -> {
                    entityManager.persist(new AuthorEntity(1L, "Persisted"));
                    entityManager.flush();

                    assertEquals(1, insertAuthor(transactional, 2L, "Generated"));

                    throw failure;
                })
            )
        );

        assertEquals(List.of(), authorNames(plain));
    }

    /**
     * A generated read reaches the database, not the persistence context, so a
     * persisted entity whose insert Hibernate still defers is invisible to it;
     * the same read sees the row after {@code flush} writes it on the
     * transaction's connection.
     */
    @Test
    void shouldSeeAPendingJpaWriteOnlyAfterAFlush() {
        Object transactional = repository(transactionalDataSource);

        jpaTransactions().execute(status -> {
            entityManager.persist(new AuthorEntity(1L, "Deferred"));

            assertEquals(List.of(), authorNames(transactional));

            entityManager.flush();

            assertEquals(List.of("Deferred"), authorNames(transactional));

            return null;
        });
    }

    /** A transaction over the plain data source, without the proxy. */
    private TransactionTemplate dataSourceTransactions() {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    /** A transaction over the entity factory built on the plain data source. */
    private TransactionTemplate jpaTransactions() {
        return new TransactionTemplate(new JpaTransactionManager(entityManagerFactory));
    }

    /** A generated repository bound to the given data source. */
    private static Object repository(DataSource repositoryDataSource) {
        try {
            Class<?> generatedClass = Class.forName(
                "generated." + GROUP + "Repository",
                true,
                classLoader
            );

            Constructor<?> constructor = generatedClass.getConstructor(QueryExecutor.class);

            return constructor.newInstance(
                new JdbcQueryExecutor(repositoryDataSource)
            );
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                "Failed to construct the generated repository",
                failure
            );
        }
    }

    private static int insertAuthor(Object repository, long id, String name) {
        try {
            return (int) repository
                .getClass()
                .getMethod("insertAuthor", Long.class, String.class)
                .invoke(repository, id, name);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Failed to run the generated write", failure);
        }
    }

    private static List<String> authorNames(Object repository) {
        try {
            List<?> rows = (List<?>) repository
                .getClass()
                .getMethod("listAuthorNames")
                .invoke(repository);

            List<String> names = new ArrayList<>();

            for (Object row : rows) {
                names.add((String) row.getClass().getMethod("name").invoke(row));
            }

            return names;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Failed to run the generated read", failure);
        }
    }

    /**
     * Compiles the schema snapshot and queries, then compiles every generated
     * Java file into an isolated temporary classes directory.
     */
    private static Path generateAndCompile() throws Exception {
        Path schemaFile = tempDir.resolve("schema.sql");
        Path queriesFile = tempDir.resolve("queries.sql");
        Path generatedDirectory = tempDir.resolve("generated");
        Path classesDirectory = tempDir.resolve("classes");

        Files.writeString(schemaFile, SCHEMA);
        Files.writeString(queriesFile, QUERIES);

        Config config = new Config(
            List.of(
                new SqlConfig(
                    GROUP,
                    schemaFile.toString(),
                    queriesFile.toString()
                )
            ),
            new JavaConfig(
                generatedDirectory.toString(),
                "generated"
            )
        );

        new SqlcjCompiler().compile(config);

        Files.createDirectories(classesDirectory);

        JavaCompiler compilerApi = ToolProvider.getSystemJavaCompiler();

        assertNotNull(compilerApi);

        List<String> arguments = new ArrayList<>(
            List.of(
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                classesDirectory.toString()
            )
        );

        try (Stream<Path> generatedFiles = Files.list(generatedDirectory.resolve("generated"))) {
            generatedFiles
                .map(Path::toString)
                .sorted()
                .forEach(arguments::add);
        }

        int compilationResult = compilerApi.run(
            null,
            null,
            null,
            arguments.toArray(new String[0])
        );

        assertEquals(0, compilationResult);

        return classesDirectory;
    }

    private static URLClassLoader classLoader(Path classesDirectory) throws Exception {
        return new URLClassLoader(
            new URL[] { classesDirectory.toUri().toURL() },
            SpringTransactionIntegrationTest.class.getClassLoader()
        );
    }

    private static void execute(String sql) throws Exception {
        try (
            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement()
        ) {
            statement.execute(sql);
        }
    }

    /**
     * The JPA view of the same table the generated repository writes. Its key
     * is assigned, so Hibernate defers its insert until a flush.
     */
    @Entity
    @Table(name = "authors")
    public static class AuthorEntity {

        @Id
        private Long id;

        private String name;

        public AuthorEntity() {
        }

        AuthorEntity(Long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /**
     * The failure these tests throw from a transaction callback to force a
     * rollback.
     */
    private static final class CallbackFailure extends RuntimeException {
    }
}
