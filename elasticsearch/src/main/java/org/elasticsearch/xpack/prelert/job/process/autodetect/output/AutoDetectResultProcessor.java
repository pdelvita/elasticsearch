/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.job.process.autodetect.output;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.util.Supplier;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.xpack.prelert.job.ModelSizeStats;
import org.elasticsearch.xpack.prelert.job.ModelSnapshot;
import org.elasticsearch.xpack.prelert.job.persistence.JobResultsPersister;
import org.elasticsearch.xpack.prelert.job.process.normalizer.Renormaliser;
import org.elasticsearch.xpack.prelert.job.quantiles.Quantiles;
import org.elasticsearch.xpack.prelert.job.results.AnomalyRecord;
import org.elasticsearch.xpack.prelert.job.results.AutodetectResult;
import org.elasticsearch.xpack.prelert.job.results.Bucket;
import org.elasticsearch.xpack.prelert.job.results.CategoryDefinition;
import org.elasticsearch.xpack.prelert.job.results.Influencer;
import org.elasticsearch.xpack.prelert.job.results.ModelDebugOutput;
import org.elasticsearch.xpack.prelert.utils.CloseableIterator;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

/**
 * A runnable class that reads the autodetect process output
 * and writes the results via the {@linkplain JobResultsPersister}
 * passed in the constructor.
 * <p>
 * Has methods to register and remove alert observers.
 * Also has a method to wait for a flush to be complete.
 */
public class AutoDetectResultProcessor {

    private static final Logger LOGGER = Loggers.getLogger(AutoDetectResultProcessor.class);

    private final Renormaliser renormaliser;
    private final JobResultsPersister persister;
    private final AutodetectResultsParser parser;

    final CountDownLatch completionLatch = new CountDownLatch(1);
    private final FlushListener flushListener;

    private volatile ModelSizeStats latestModelSizeStats;

    public AutoDetectResultProcessor(Renormaliser renormaliser, JobResultsPersister persister, AutodetectResultsParser parser) {
        this.renormaliser = renormaliser;
        this.persister = persister;
        this.parser = parser;
        this.flushListener = new FlushListener();
    }

    AutoDetectResultProcessor(Renormaliser renormaliser, JobResultsPersister persister, AutodetectResultsParser parser,
                              FlushListener flushListener) {
        this.renormaliser = renormaliser;
        this.persister = persister;
        this.parser = parser;
        this.flushListener = flushListener;
    }

    public void process(String jobId, InputStream in, boolean isPerPartitionNormalisation) {
        try (CloseableIterator<AutodetectResult> iterator = parser.parseResults(in)) {
            int bucketCount = 0;
            Context context = new Context(jobId, isPerPartitionNormalisation);
            while (iterator.hasNext()) {
                AutodetectResult result = iterator.next();
                processResult(context, result);
                if (result.getBucket() != null) {
                    bucketCount++;
                    LOGGER.trace("[{}] Bucket number {} parsed from output", jobId, bucketCount);
                }
            }
            LOGGER.info("[{}] {} buckets parsed from autodetect output - about to refresh indexes", jobId, bucketCount);
            LOGGER.info("[{}] Parse results Complete", jobId);
        } catch (Exception e) {
            LOGGER.error((Supplier<?>) () -> new ParameterizedMessage("[{}] error parsing autodetect output", new Object[] {jobId}, e));
        } finally {
            completionLatch.countDown();
            flushListener.clear();
            renormaliser.shutdown();
        }
    }

    void processResult(Context context, AutodetectResult result) {
        Bucket bucket = result.getBucket();
        if (bucket != null) {
            if (context.deleteInterimRequired) {
                // Delete any existing interim results at the start
                // of a job upload:
                // these are generated by a Flush command, and will
                // be replaced or
                // superseded by new results
                LOGGER.trace("[{}] Deleting interim results", context.jobId);
                // TODO: Is this the right place to delete results?
                persister.deleteInterimResults(context.jobId);
                context.deleteInterimRequired = false;
            }
            if (context.isPerPartitionNormalization) {
                bucket.calcMaxNormalizedProbabilityPerPartition();
            }
            persister.persistBucket(bucket);
        }
        List<AnomalyRecord> records = result.getRecords();
        if (records != null && !records.isEmpty()) {
            persister.persistRecords(records);
        }
        List<Influencer> influencers = result.getInfluencers();
        if (influencers != null && !influencers.isEmpty()) {
            persister.persistInfluencers(influencers);
        }
        CategoryDefinition categoryDefinition = result.getCategoryDefinition();
        if (categoryDefinition != null) {
            persister.persistCategoryDefinition(categoryDefinition);
        }
        ModelDebugOutput modelDebugOutput = result.getModelDebugOutput();
        if (modelDebugOutput != null) {
            persister.persistModelDebugOutput(modelDebugOutput);
        }
        ModelSizeStats modelSizeStats = result.getModelSizeStats();
        if (modelSizeStats != null) {
            LOGGER.trace(String.format(Locale.ROOT, "[%s] Parsed ModelSizeStats: %d / %d / %d / %d / %d / %s",
                    context.jobId, modelSizeStats.getModelBytes(), modelSizeStats.getTotalByFieldCount(),
                    modelSizeStats.getTotalOverFieldCount(), modelSizeStats.getTotalPartitionFieldCount(),
                    modelSizeStats.getBucketAllocationFailuresCount(), modelSizeStats.getMemoryStatus()));

            latestModelSizeStats = modelSizeStats;
            persister.persistModelSizeStats(modelSizeStats);
        }
        ModelSnapshot modelSnapshot = result.getModelSnapshot();
        if (modelSnapshot != null) {
            persister.persistModelSnapshot(modelSnapshot);
        }
        Quantiles quantiles = result.getQuantiles();
        if (quantiles != null) {
            persister.persistQuantiles(quantiles);

            LOGGER.debug("[{}] Quantiles parsed from output - will " + "trigger renormalisation of scores", context.jobId);
            if (context.isPerPartitionNormalization) {
                renormaliser.renormaliseWithPartition(quantiles);
            } else {
                renormaliser.renormalise(quantiles);
            }
        }
        FlushAcknowledgement flushAcknowledgement = result.getFlushAcknowledgement();
        if (flushAcknowledgement != null) {
            LOGGER.debug("[{}] Flush acknowledgement parsed from output for ID {}", context.jobId, flushAcknowledgement.getId());
            // Commit previous writes here, effectively continuing
            // the flush from the C++ autodetect process right
            // through to the data store
            persister.commitWrites(context.jobId);
            flushListener.acknowledgeFlush(flushAcknowledgement.getId());
            // Interim results may have been produced by the flush,
            // which need to be
            // deleted when the next finalized results come through
            context.deleteInterimRequired = true;
        }
    }

    public void awaitCompletion() {
        try {
            completionLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /**
     * Blocks until a flush is acknowledged or the timeout expires, whichever happens first.
     *
     * @param flushId the id of the flush request to wait for
     * @param timeout the timeout
     * @return {@code true} if the flush has completed or the parsing finished; {@code false} if the timeout expired
     */
    public boolean waitForFlushAcknowledgement(String flushId, Duration timeout) {
        return flushListener.waitForFlush(flushId, timeout.toMillis());
    }

    public void waitUntilRenormaliserIsIdle() {
        renormaliser.waitUntilIdle();
    }

    static class Context {

        private final String jobId;
        private final boolean isPerPartitionNormalization;

        boolean deleteInterimRequired;

        Context(String jobId, boolean isPerPartitionNormalization) {
            this.jobId = jobId;
            this.isPerPartitionNormalization = isPerPartitionNormalization;
            this.deleteInterimRequired = true;
        }
    }

    public Optional<ModelSizeStats> modelSizeStats() {
        return Optional.ofNullable(latestModelSizeStats);
    }

}

