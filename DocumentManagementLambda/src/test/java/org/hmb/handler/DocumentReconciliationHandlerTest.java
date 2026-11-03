package org.hmb.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import org.hmb.service.DocumentReconciliationService;
import org.hmb.service.DocumentReconciliationService.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentReconciliationHandlerTest {

    @Mock DocumentReconciliationService service;
    @Mock Context context;

    @Test
    void aCleanRunReturnsTheSummary() {
        when(service.reconcile(any(LongSupplier.class))).thenReturn(new Result(10, 2, 0, 0, true));

        String summary = new DocumentReconciliationHandler(service).handleRequest(new ScheduledEvent(), context);

        assertEquals("reconciliation checked=10 healed=2 missing=0 errors=0 complete=true", summary);
    }

    @Test
    void aMissingObjectFailsTheInvocationSoTheErrorsAlarmFires() {
        when(service.reconcile(any(LongSupplier.class))).thenReturn(new Result(10, 0, 1, 0, true));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new DocumentReconciliationHandler(service).handleRequest(new ScheduledEvent(), context));

        assertEquals("reconciliation checked=10 healed=0 missing=1 errors=0 complete=true", e.getMessage());
    }

    @Test
    void rowsThatCouldNotBeCheckedAndIncompleteRunsAlsoFailTheInvocation() {
        when(service.reconcile(any(LongSupplier.class))).thenReturn(new Result(10, 0, 0, 3, true))
                .thenReturn(new Result(4, 0, 0, 0, false));
        DocumentReconciliationHandler handler = new DocumentReconciliationHandler(service);

        assertThrows(IllegalStateException.class, () -> handler.handleRequest(new ScheduledEvent(), context));
        assertThrows(IllegalStateException.class, () -> handler.handleRequest(new ScheduledEvent(), context));
    }
}
