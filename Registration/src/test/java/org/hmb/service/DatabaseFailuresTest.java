package org.hmb.service;

import org.hmb.exception.RegistrationException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseFailuresTest {

    /** The failure behind "SQLTransientConnectionException (SQLState=08001)" with MySQL's cold sha2 password cache. */
    @Test
    void theRootCauseExplainsAPoolTimeout() {
        SQLException driver = new SQLNonTransientConnectionException("Public Key Retrieval is not allowed", "08001");
        SQLException pool = new SQLTransientConnectionException(
                "hiremebot-pool - Connection is not available, request timed out after 5001ms.", "08001", driver);

        assertEquals("SQLNonTransientConnectionException SQLState=08001: Public Key Retrieval is not allowed",
                DatabaseFailures.rootCause(pool));
        RegistrationException mapped = DatabaseFailures.map(pool);
        assertEquals(503, mapped.status());
        assertEquals("SERVICE_UNAVAILABLE", mapped.code());
        assertFalse(mapped.getMessage().contains("Public Key"), "the client never sees the cause");
    }

    @Test
    void theLoggedCauseNeverCarriesUrlsOrPasswords() {
        String url = "jdbc:mysql://db.internal:3306/hiremebot_db_v1?sslMode=VERIFY_IDENTITY"
                + "&trustCertificateKeyStorePassword=hunter2&connectTimeout=5000";
        String described = DatabaseFailures.rootCause(new SQLException("No suitable driver found for " + url));
        assertFalse(described.contains("hunter2"), described);
        assertFalse(described.contains("db.internal"), described);

        described = DatabaseFailures.rootCause(new SQLException("login failed, password=hunter2 rejected"));
        assertFalse(described.contains("hunter2"), described);
        assertTrue(described.contains("password=<redacted>"), described);
    }

    @Test
    void theLoggedCauseIsBoundedAndSingleLine() {
        String described = DatabaseFailures.rootCause(new SQLException("Communications link failure\n\nThe last packet "
                + "sent successfully to the server was 0 milliseconds ago. " + "x".repeat(500)));
        assertFalse(described.contains("\n"));
        assertTrue(described.length() < 300, "length " + described.length());
        assertTrue(described.endsWith("..."));
    }

    @Test
    void aCauseCycleDoesNotLoopForever() {
        SQLException a = new SQLException("a");
        SQLException b = new SQLException("b", a);
        a.initCause(b);
        assertTrue(DatabaseFailures.rootCause(a).startsWith("SQLException"));
    }
}
