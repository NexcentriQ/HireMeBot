package org.hmb.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseSecretResolverTest {

    @Test
    void withoutASecretTheEnvironmentIsUsedAsIs() {
        Map<String, String> env = Map.of("DB_USERNAME", "u", "DB_PASSWORD", "p");
        assertSame(env, DatabaseSecretResolver.resolve(env, id -> {
            throw new AssertionError("Secrets Manager must not be called");
        }));
    }

    @Test
    void credentialsComeFromTheSecretAndExplicitEnvironmentValuesWin() {
        Map<String, String> env = new HashMap<>();
        env.put("DB_SECRET_ARN", "arn:aws:secretsmanager:us-east-2:123456789012:secret:db-abc");
        env.put("DB_HOST", "db.internal");
        env.put("DB_NAME", "hiremebot_db_v1");
        env.put("DB_PASSWORD", "stale-plaintext");

        Map<String, String> resolved = DatabaseSecretResolver.resolve(env, id -> """
                {"username":"registration","password":"s3cr3t","host":"other.host","port":3307,"dbname":"x"}""");

        assertEquals("registration", resolved.get("DB_USERNAME"));
        assertEquals("s3cr3t", resolved.get("DB_PASSWORD"));
        assertEquals("db.internal", resolved.get("DB_HOST"));
        assertEquals("3307", resolved.get("DB_PORT"));
        assertEquals("hiremebot_db_v1", resolved.get("DB_NAME"));
    }

    @Test
    void anIncompleteOrMalformedSecretFailsWithoutRevealingIt() {
        Map<String, String> env = Map.of("DB_SECRET_ARN", "arn:aws:secretsmanager:us-east-2:1:secret:x");

        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> DatabaseSecretResolver.resolve(env, id -> "{\"username\":\"u\"}"));
        assertEquals("The database secret has no 'password'", missing.getMessage());

        IllegalStateException malformed = assertThrows(IllegalStateException.class,
                () -> DatabaseSecretResolver.resolve(env, id -> "{\"password\":\"hunter2\""));
        assertFalse(malformed.getMessage().contains("hunter2"));
        assertNull(malformed.getCause());
    }

    @Test
    void secretsManagerErrorsKeepTheirCause() {
        Map<String, String> env = Map.of("DB_SECRET_ARN", "arn:aws:secretsmanager:us-east-2:1:secret:x");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DatabaseSecretResolver.resolve(env, id -> {
                    throw new RuntimeException("AccessDeniedException");
                }));
        assertNotNull(e.getCause());
    }

    @Test
    void aBlankSecretArnIsTreatedAsNotSet() {
        Map<String, String> env = Map.of("DB_SECRET_ARN", "  ", "DB_USERNAME", "u", "DB_PASSWORD", "p");
        assertSame(env, DatabaseSecretResolver.resolve(env, id -> {
            throw new AssertionError("Secrets Manager must not be called");
        }));
    }

    @Test
    void theSecretArnIsTrimmedAndTheInputMapIsNotModified() {
        Map<String, String> env = new HashMap<>();
        env.put("DB_SECRET_ARN", " arn:aws:secretsmanager:us-east-2:1:secret:x ");
        String[] requested = new String[1];

        Map<String, String> resolved = DatabaseSecretResolver.resolve(env, id -> {
            requested[0] = id;
            return "{\"username\":\"u\",\"password\":\"p\",\"host\":\"h\"}";
        });

        assertEquals("arn:aws:secretsmanager:us-east-2:1:secret:x", requested[0]);
        assertEquals("h", resolved.get("DB_HOST"));
        assertFalse(env.containsKey("DB_USERNAME"));
    }

    @Test
    void aSecretThatIsNotAJsonObjectOrIsEmptyIsRejected() {
        Map<String, String> env = Map.of("DB_SECRET_ARN", "arn:aws:secretsmanager:us-east-2:1:secret:x");

        assertEquals("The database secret must be a JSON object", assertThrows(IllegalStateException.class,
                () -> DatabaseSecretResolver.resolve(env, id -> "[\"u\",\"p\"]")).getMessage());
        assertEquals("The database secret must be a JSON object", assertThrows(IllegalStateException.class,
                () -> DatabaseSecretResolver.resolve(env, id -> null)).getMessage());
        assertEquals("The database secret has no 'password'", assertThrows(IllegalStateException.class,
                () -> DatabaseSecretResolver.resolve(env, id -> "{\"username\":\"u\",\"password\":\"\"}"))
                .getMessage());
    }
}
