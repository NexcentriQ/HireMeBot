package org.hmb.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StatisticsConfigTest {

    @Test
    void defaultsToFifty() {
        assertEquals(50, StatisticsConfig.dailyTarget(Map.of()));
        assertEquals(50, StatisticsConfig.dailyTarget(Map.of("DAILY_APPLICATION_TARGET", "  ")));
    }

    @Test
    void isConfigurable() {
        assertEquals(30, StatisticsConfig.dailyTarget(Map.of("DAILY_APPLICATION_TARGET", "30")));
    }

    @Test
    void rejectsNonPositiveOrNonNumericValues() {
        assertThrows(IllegalStateException.class,
                () -> StatisticsConfig.dailyTarget(Map.of("DAILY_APPLICATION_TARGET", "0")));
        assertThrows(IllegalStateException.class,
                () -> StatisticsConfig.dailyTarget(Map.of("DAILY_APPLICATION_TARGET", "-5")));
        assertThrows(IllegalStateException.class,
                () -> StatisticsConfig.dailyTarget(Map.of("DAILY_APPLICATION_TARGET", "fifty")));
    }
}
