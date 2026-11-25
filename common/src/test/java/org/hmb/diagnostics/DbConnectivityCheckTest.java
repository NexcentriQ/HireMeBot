package org.hmb.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class DbConnectivityCheckTest {

    private static final Map<String, String> ENV = Map.of(
            "DB_HOST", "db.example.internal", "DB_USERNAME", "appuser", "DB_PASSWORD", "s3cret-pass");

    @Test
    void masksHostUserAndPasswordInDriverMessages() {
        String masked = DbConnectivityCheck.mask(ENV,
                "Access denied for user 'appuser'@'db.example.internal' (using password: s3cret-pass)");
        assertFalse(masked.contains("appuser"));
        assertFalse(masked.contains("db.example.internal"));
        assertFalse(masked.contains("s3cret-pass"));
        assertEquals("Access denied for user '***'@'***' (using password: ***)", masked);
    }

    @Test
    void nullAndLongMessages() {
        assertNull(DbConnectivityCheck.mask(ENV, null));
        assertEquals(403, DbConnectivityCheck.mask(ENV, "x".repeat(1000)).length());
    }
}
