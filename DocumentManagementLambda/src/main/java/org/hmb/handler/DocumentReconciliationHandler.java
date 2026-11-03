package org.hmb.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import org.hmb.config.DatabaseConfig;
import org.hmb.config.DocumentUploadConfig;
import org.hmb.repository.DocumentRepository;
import org.hmb.service.DocumentReconciliationService;
import org.hmb.service.DocumentReconciliationService.Result;
import org.hmb.storage.S3DocumentStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Entry point of the scheduled reconciler (an EventBridge schedule invokes it; it is not an API route and takes no
 * input). It heals registered-but-still-pending objects; see {@link DocumentReconciliationService}.
 *
 * <p>If the run finds a missing S3 object, cannot check a row, or could not finish, it logs the summary and then
 * <em>fails the invocation</em> so the function's {@code Errors} alarm fires and Lambda retries the (idempotent) run.
 */
public class DocumentReconciliationHandler implements RequestHandler<ScheduledEvent, String> {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentReconciliationHandler.class);

    private static volatile DocumentReconciliationService defaultService;

    private final Supplier<DocumentReconciliationService> serviceSupplier;

    /** Used by the Lambda runtime. Resources are created lazily and reused while the environment is warm. */
    public DocumentReconciliationHandler() {
        this(DocumentReconciliationHandler::defaultService);
    }

    /** For tests. */
    public DocumentReconciliationHandler(DocumentReconciliationService service) {
        this(() -> service);
    }

    private DocumentReconciliationHandler(Supplier<DocumentReconciliationService> serviceSupplier) {
        this.serviceSupplier = serviceSupplier;
    }

    private static DocumentReconciliationService defaultService() {
        DocumentReconciliationService local = defaultService;
        if (local == null) {
            synchronized (DocumentReconciliationHandler.class) {
                local = defaultService;
                if (local == null) {
                    Map<String, String> env = System.getenv();
                    DocumentUploadConfig config = DocumentUploadConfig.fromEnv(env);
                    local = new DocumentReconciliationService(DatabaseConfig.getDataSource(), config,
                            new DocumentRepository(), S3DocumentStorageService.create(config.bucket()),
                            Clock.systemUTC());
                    defaultService = local;
                }
            }
        }
        return local;
    }

    @Override
    public String handleRequest(ScheduledEvent event, Context context) {
        Result result = serviceSupplier.get().reconcile(context::getRemainingTimeInMillis);
        LOG.info(result.summary());
        if (result.needsAttention()) {
            throw new IllegalStateException(result.summary());
        }
        return result.summary();
    }
}
