package org.hmb.config;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Map;

/**
 * Application-level settings read from environment variables.
 *
 * <p>{@code APP_TIMEZONE} (default {@code America/Chicago}) decides which calendar day a student's
 * {@code date} query parameter refers to, and what "today" means when the parameter is omitted.
 */
public final class AppConfig {

    public static final String TIMEZONE_VAR = "APP_TIMEZONE";
    public static final String DEFAULT_TIMEZONE = "America/Chicago";

    private AppConfig() {
    }

    /** @throws IllegalStateException if the configured zone id is not a valid region id */
    public static ZoneId timeZone(Map<String, String> env) {
        String value = env.get(TIMEZONE_VAR);
        String id = (value == null || value.isBlank()) ? DEFAULT_TIMEZONE : value.trim();
        try {
            return ZoneId.of(id);
        } catch (DateTimeException e) {
            throw new IllegalStateException(TIMEZONE_VAR + " is not a valid time zone id");
        }
    }
}
