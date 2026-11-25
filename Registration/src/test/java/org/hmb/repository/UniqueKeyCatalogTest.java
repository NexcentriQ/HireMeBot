package org.hmb.repository;

import org.hmb.repository.UniqueKeyCatalog.UniqueKey;
import org.hmb.repository.UniqueKeyCatalog.ViolatedKey;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UniqueKeyCatalogTest {

    private static SQLException duplicate(String message) {
        return new SQLIntegrityConstraintViolationException(message, "23000", 1062);
    }

    @Test
    void readsTheViolatedKeyFromMySql8Messages() {
        assertEquals(Optional.of(new ViolatedKey("users", "first_name")), UniqueKeyCatalog.violatedKey(
                duplicate("Duplicate entry 'Alex-Smith' for key 'users.first_name'")));
        assertEquals(Optional.of(new ViolatedKey("users", "email")), UniqueKeyCatalog.violatedKey(
                duplicate("Duplicate entry 'alex@gmail.com' for key 'users.email'")));
        assertEquals(Optional.of(new ViolatedKey("user_identities", "uq_cognito_sub")), UniqueKeyCatalog.violatedKey(
                duplicate("Duplicate entry '8f1c2d3e' for key 'user_identities.uq_cognito_sub'")));
    }

    @Test
    void readsOlderMessagesWithoutTheTableName() {
        assertEquals(Optional.of(new ViolatedKey(null, "email")),
                UniqueKeyCatalog.violatedKey(duplicate("Duplicate entry 'a@b.co' for key 'email'")));
    }

    @Test
    void aDuplicatedValueCannotSpoofTheKeyName() {
        // Names may contain letters, spaces, apostrophes and periods, so a hostile name can embed "for key '...'".
        String hostile = "Duplicate entry 'for key 'users.email' x-Smith' for key 'users.first_name'";
        assertEquals(Optional.of(new ViolatedKey("users", "first_name")),
                UniqueKeyCatalog.violatedKey(duplicate(hostile)));
        assertTrue(UniqueKeyCatalog.violatedKey(duplicate("Duplicate entry 'x' for key 'users.email' trailing")).isEmpty());
        assertTrue(UniqueKeyCatalog.violatedKey(duplicate("Deadlock found when trying to get lock")).isEmpty());
    }

    @Test
    void classifiesByColumnsNotByIndexName() {
        assertEquals(UniqueKey.EMAIL, UniqueKeyCatalog.kindOf("users", List.of("email")));
        assertEquals(UniqueKey.FULL_NAME, UniqueKeyCatalog.kindOf("users", List.of("first_name", "last_name")));
        assertEquals(UniqueKey.FULL_NAME, UniqueKeyCatalog.kindOf("users", List.of("last_name", "first_name")));
        assertEquals(UniqueKey.COGNITO_SUBJECT, UniqueKeyCatalog.kindOf("user_identities", List.of("cognito_sub")));
        assertEquals(UniqueKey.USER_PROVIDER, UniqueKeyCatalog.kindOf("user_identities", List.of("user_id", "provider")));
        assertEquals(UniqueKey.UNKNOWN, UniqueKeyCatalog.kindOf("users", List.of("first_name")));
        assertEquals(UniqueKey.UNKNOWN, UniqueKeyCatalog.kindOf("users", List.of("first_name", "last_name", "phone")));
        assertEquals(UniqueKey.UNKNOWN, UniqueKeyCatalog.kindOf("users", List.of("user_id")));
        assertEquals(UniqueKey.UNKNOWN, UniqueKeyCatalog.kindOf("students", List.of("email")));
    }
}
