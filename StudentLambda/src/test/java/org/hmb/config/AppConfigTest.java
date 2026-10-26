package org.hmb.config;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppConfigTest {

    @Test
    void defaultsToChicago() {
        assertEquals(ZoneId.of("America/Chicago"), AppConfig.timeZone(Map.of()));
        assertEquals(ZoneId.of("America/Chicago"), AppConfig.timeZone(Map.of("APP_TIMEZONE", "  ")));
    }

    @Test
    void isConfigurable() {
        assertEquals(ZoneId.of("Asia/Kolkata"), AppConfig.timeZone(Map.of("APP_TIMEZONE", "Asia/Kolkata")));
    }

    @Test
    void invalidZoneFailsFast() {
        assertThrows(IllegalStateException.class, () -> AppConfig.timeZone(Map.of("APP_TIMEZONE", "Mars/Olympus")));
    }
}
