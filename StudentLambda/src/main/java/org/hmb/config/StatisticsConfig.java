package org.hmb.config;

import java.util.Map;

/**
 * Statistics settings read from environment variables.
 *
 * <p>{@code DAILY_APPLICATION_TARGET} (default 50) is the per-weekday target. The monthly target is this value
 * times the number of weekdays in the month.
 */
public final class StatisticsConfig {

    public static final String DAILY_TARGET_VAR = "DAILY_APPLICATION_TARGET";
    public static final int DEFAULT_DAILY_TARGET = 50;

    private StatisticsConfig() {
    }

    /** @throws IllegalStateException if the variable is set to something that is not a positive integer */
    public static int dailyTarget(Map<String, String> env) {
        String value = env.get(DAILY_TARGET_VAR);
        if (value == null || value.isBlank()) {
            return DEFAULT_DAILY_TARGET;
        }
        try {
            int target = Integer.parseInt(value.trim());
            if (target <= 0) {
                throw new NumberFormatException("not positive");
            }
            return target;
        } catch (NumberFormatException e) {
            throw new IllegalStateException(DAILY_TARGET_VAR + " must be a positive integer");
        }
    }
}
