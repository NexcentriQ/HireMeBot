package org.hmb.logging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestIdsTest {

    @Test
    void theGatewayIdIsUsedWhenDeployed() {
        assertEquals("Ab1CdEfGh=", RequestIds.of("Ab1CdEfGh=", "lambda-1", false));
    }

    @Test
    void underSamLocalTheEmulatedGatewayIdIsIgnoredBecauseItNeverChanges() {
        assertEquals("lambda-1", RequestIds.of("req-same-every-time", "lambda-1", true));
    }

    @Test
    void unsafeOrMissingIdsFallBackToTheLambdaIdThenARandomOne() {
        assertEquals("lambda-1", RequestIds.of("bad id\nwith newline", "lambda-1", false));
        assertEquals("lambda-1", RequestIds.of(null, "lambda-1", false));
        assertEquals(36, RequestIds.of(null, null, false).length());
        assertEquals(36, RequestIds.of("x".repeat(101), "bad id", false).length());
    }
}
