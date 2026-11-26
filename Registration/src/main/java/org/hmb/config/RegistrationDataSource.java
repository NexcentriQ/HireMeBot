package org.hmb.config;

import javax.sql.DataSource;
import java.util.Map;

/**
 * The registration Lambda's connection pool, built from an injected environment map (so tests can supply one). The
 * credentials are resolved by the shared {@link DatabaseSecretResolver} and the pool by {@link DatabaseConfig}.
 */
public final class RegistrationDataSource {

    private static volatile DataSource instance;

    private RegistrationDataSource() {
    }

    /** Returns the shared pool, creating it on first use (warm invocations reuse it). */
    public static DataSource get(Map<String, String> env) {
        DataSource local = instance;
        if (local == null) {
            synchronized (RegistrationDataSource.class) {
                local = instance;
                if (local == null) {
                    local = DatabaseConfig.create(DatabaseSecretResolver.resolve(env));
                    instance = local;
                }
            }
        }
        return local;
    }
}
