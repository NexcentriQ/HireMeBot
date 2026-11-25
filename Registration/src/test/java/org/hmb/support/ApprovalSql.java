package org.hmb.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

/**
 * Executes the <b>operator's own script</b>, {@code Registration/db/recruiter-approval.sql}, in a scratch schema, so the
 * SQL documented for the manual approval is exactly the SQL that is tested (not a copy of it). Only test schemas are
 * ever passed in; the application never runs this script.
 */
public final class ApprovalSql {

    private static final String SCRIPT = "db/recruiter-approval.sql";

    /** The script's statements, comment lines removed, in file order. */
    private final List<String> statements;

    private ApprovalSql(List<String> statements) {
        this.statements = statements;
    }

    public static ApprovalSql load() throws IOException {
        Path base = Path.of(System.getProperty("basedir", System.getProperty("user.dir")));
        Path file = base.resolve(SCRIPT);
        if (!Files.exists(file)) {
            file = base.resolve("Registration").resolve(SCRIPT); // run from the repository root, e.g. in an IDE
        }
        String script = Files.readString(file, StandardCharsets.UTF_8).replaceAll("(?m)^\\s*--.*$", "");
        List<String> statements = Arrays.stream(script.split(";\\s*(\\r?\\n|$)"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        return new ApprovalSql(statements);
    }

    /** The approval statement exactly as the operator runs it. */
    public String updateStatement() {
        return statements.stream().filter(s -> s.startsWith("UPDATE users")).findFirst().orElseThrow();
    }

    /** The statement that reports how many rows the UPDATE changed. */
    public String rowCountStatement() {
        return statements.stream().filter(s -> s.startsWith("SELECT ROW_COUNT()")).findFirst().orElseThrow();
    }

    public int statementCount() {
        return statements.size();
    }

    /**
     * Performs the operator's steps 2-4 for one recruiter on one connection (user variables are per connection):
     * {@code SET @recruiter_id = <id>}, the script's UPDATE, then the script's {@code SELECT ROW_COUNT()}.
     *
     * @return {@code ROW_COUNT()} as the operator would read it: 1 if the recruiter was approved, 0 if nothing changed
     */
    public long approve(DataSource dataSource, long userId) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("SET @recruiter_id = " + userId);
            s.executeUpdate(updateStatement());
            try (ResultSet rs = s.executeQuery(rowCountStatement())) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Runs every statement of the script exactly as shipped (default {@code @recruiter_id = 0}, a harmless no-op). */
    public void runUnedited(DataSource dataSource) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String statement : statements) {
                s.execute(statement);
            }
        }
    }
}
