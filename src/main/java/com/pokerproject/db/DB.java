package com.pokerproject.db;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

// Mirrors csci440.util.DB from the CSCI 440 coursework this format is borrowed from: one static
// connect() against a hardcoded file path, a separate test-mode database, PRAGMA foreign_keys on
// every connection, getLastId() wrapping last_insert_rowid(). No pooling - SQLite doesn't want a
// long-held writer, and this app's connection volume never justifies one.
public final class DB {

    private static boolean TEST_MODE = false;
    private static final String MAIN_DB_PATH = "db/poker.db";
    private static final String TEST_DB_PATH = "db/poker-test.db";
    private static final String DDL_PATH = "db/poker.ddl";

    private DB() {
    }

    public static void useTestMode() {
        TEST_MODE = true;
    }

    private static String dbPath() {
        return TEST_MODE ? TEST_DB_PATH : MAIN_DB_PATH;
    }

    public static Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
    }

    public static long getLastId(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet results = stmt.executeQuery("SELECT last_insert_rowid() as ID")) {
            if (results.next()) {
                return results.getLong("ID");
            }
            throw new IllegalStateException("Could not get last ID");
        }
    }

    // Idempotent - safe to call on every startup. The DDL itself uses IF NOT EXISTS, so this
    // never needs its own existence check. sqlite-jdbc's Statement.executeUpdate accepts multiple
    // ;-separated statements in one call, unlike stock JDBC - poker.ddl stays the single source
    // of truth for the schema instead of being duplicated as a Java string here.
    public static void initSchema() {
        try {
            String ddl = Files.readString(Path.of(DDL_PATH));
            try (Connection conn = connect();
                 Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(ddl);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // Test support: wipe the (test-mode) database file and rebuild the schema fresh. No seed
    // data to restore (unlike Chinook's copy-from-.original backup) - the poker app always
    // starts empty, so recreating the schema from poker.ddl is the whole reset.
    public static void reset() {
        try {
            Files.deleteIfExists(Path.of(dbPath()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        initSchema();
    }
}
