package org.hmb.service;

import org.hmb.config.DocumentUploadConfig;
import org.hmb.repository.DocumentRepository;
import org.hmb.repository.StoredDocument;
import org.hmb.storage.ObjectMissingException;
import org.hmb.storage.S3DocumentStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Heals the one gap that can open between MySQL and S3: a row was inserted but the object is still tagged
 * {@code doc-state=pending} (the tagging step failed or the Lambda died after the insert). Left alone, the lifecycle
 * rule would eventually expire that object, so it is retagged {@code registered}.
 *
 * <p>Rules that keep it safe:
 * <ul>
 *   <li>It is driven by the database: only objects that have a row are ever touched, so it cannot interfere with an
 *       upload or registration that is still in flight.</li>
 *   <li>It <b>never deletes</b> an object and never writes to MySQL (SELECT only).</li>
 *   <li>Retagging is idempotent, so it can overlap with the API and with other runs.</li>
 *   <li>The lookback (default 5 days) is shorter than the 7-day expiry of {@code pending} objects.</li>
 *   <li>A row whose object is missing is reported (ERROR log with the document id only, never the key) and is not
 *       repaired automatically.</li>
 * </ul>
 * Rows are read in keyset batches. The pass stops early, reporting {@code complete=false}, when the Lambda is close to
 * its time limit; the next run starts over.
 */
public class DocumentReconciliationService {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentReconciliationService.class);

    /** Stop this long before the Lambda's deadline. */
    static final long SAFETY_MARGIN_MILLIS = 30_000;

    /**
     * @param checked  rows examined
     * @param healed   objects retagged from pending to registered
     * @param missing  rows whose S3 object does not exist
     * @param errors   rows that could not be checked or healed
     * @param complete false if the pass stopped early because time ran short
     */
    public record Result(int checked, int healed, int missing, int errors, boolean complete) {

        /** Anything an operator must look at. */
        public boolean needsAttention() {
            return missing > 0 || errors > 0 || !complete;
        }

        public String summary() {
            return "reconciliation checked=%d healed=%d missing=%d errors=%d complete=%s"
                    .formatted(checked, healed, missing, errors, complete);
        }
    }

    private final DataSource dataSource;
    private final DocumentUploadConfig config;
    private final DocumentRepository documents;
    private final S3DocumentStorageService storage;
    private final Clock clock;

    public DocumentReconciliationService(DataSource dataSource, DocumentUploadConfig config,
                                         DocumentRepository documents, S3DocumentStorageService storage, Clock clock) {
        this.dataSource = dataSource;
        this.config = config;
        this.documents = documents;
        this.storage = storage;
        this.clock = clock;
    }

    /** @param remainingMillis milliseconds left before the Lambda deadline */
    public Result reconcile(LongSupplier remainingMillis) {
        LocalDateTime since = LocalDateTime.ofInstant(
                clock.instant().minus(Duration.ofDays(config.reconcileLookbackDays())), ZoneOffset.UTC);
        int batchSize = config.reconcileBatchSize();

        int checked = 0;
        int healed = 0;
        int missing = 0;
        int errors = 0;
        long afterId = 0;

        while (true) {
            List<StoredDocument> batch = nextBatch(since, afterId, batchSize);
            if (batch.isEmpty()) {
                break;
            }
            for (StoredDocument document : batch) {
                if (remainingMillis.getAsLong() < SAFETY_MARGIN_MILLIS) {
                    return new Result(checked, healed, missing, errors, false);
                }
                afterId = document.objectId();
                checked++;
                try {
                    switch (storage.state(document.objectKey())) {
                        case REGISTERED, UNTAGGED -> {
                            // Nothing to do: already protected, or never subject to the pending-expiry rule.
                        }
                        case PENDING -> {
                            storage.markRegistered(document.objectKey());
                            healed++;
                        }
                        case MISSING -> {
                            missing++;
                            LOG.error("Reconcile: document {} has a row but its S3 object is missing", document.objectId());
                        }
                    }
                } catch (ObjectMissingException e) {
                    missing++;
                    LOG.error("Reconcile: document {} has a row but its S3 object is missing", document.objectId());
                } catch (RuntimeException e) {
                    errors++;
                    LOG.warn("Reconcile: could not check document {} ({})", document.objectId(),
                            e.getClass().getSimpleName());
                }
            }
            if (batch.size() < batchSize) {
                break;
            }
        }
        return new Result(checked, healed, missing, errors, true);
    }

    private List<StoredDocument> nextBatch(LocalDateTime since, long afterId, int batchSize) {
        try (Connection connection = dataSource.getConnection()) {
            return documents.findRecentAfter(connection, since, afterId, batchSize);
        } catch (SQLException e) {
            // The database being unavailable fails the whole run (visible through the Errors alarm); it is retried.
            throw FailureMapper.fromSql(e);
        }
    }
}
