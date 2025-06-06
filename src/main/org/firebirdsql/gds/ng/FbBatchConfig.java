// SPDX-FileCopyrightText: Copyright 2022-2024 Mark Rotteveel
// SPDX-License-Identifier: LGPL-2.1-or-later OR BSD-3-Clause
package org.firebirdsql.gds.ng;

import org.firebirdsql.gds.BatchParameterBuffer;

import java.util.stream.IntStream;

import static org.firebirdsql.jaybird.fb.constants.BatchItems.BLOB_ID_USER;
import static org.firebirdsql.jaybird.fb.constants.BatchItems.TAG_BLOB_POLICY;
import static org.firebirdsql.jaybird.fb.constants.BatchItems.TAG_BUFFER_BYTES_SIZE;
import static org.firebirdsql.jaybird.fb.constants.BatchItems.TAG_DETAILED_ERRORS;
import static org.firebirdsql.jaybird.fb.constants.BatchItems.TAG_MULTIERROR;
import static org.firebirdsql.jaybird.fb.constants.BatchItems.TAG_RECORD_COUNTS;

/**
 * Configuration for a Firebird 4+ batch.
 *
 * @author Mark Rotteveel
 * @since 5
 */
public interface FbBatchConfig {

    // Constants exist to write more readable code when using FbBatchConfig.of(..) without resorting to a builder

    /**
     * {@link #multiError()} value to signal to continue execution on errors.
     */
    boolean CONTINUE_ON_ERROR = true;
    /**
     * {@link #multiError()} value to signal to halt execution at first error.
     */
    boolean HALT_AT_FIRST_ERROR = false;
    /**
     * {@link #updateCounts()} value to signal to supply update counts.
     */
    boolean UPDATE_COUNTS = true;
    /**
     * {@link #updateCounts()} value to signal to no supply update counts.
     */
    @SuppressWarnings("unused")
    boolean NO_UPDATE_COUNTS = false;
    /**
     * {@link #detailedErrors()} value to signal to use server default detailed errors.
     * <p>
     * NOTE: <b>all</b> negative values signal this. If you want to use this to check against a value,
     * use {@code <= SERVER_DEFAULT_DETAILED_ERRORS}.
     * </p>
     */
    int SERVER_DEFAULT_DETAILED_ERRORS = -1;
    /**
     * {@link #batchBufferSize()} value to signal to use server maximum batch buffer size.
     */
    @SuppressWarnings("unused")
    int SERVER_MAXIMUM_BUFFER_SIZE = 0;
    /**
     * {@link #batchBufferSize()} value to signal to use server default batch buffer size.
     * <p>
     * NOTE: <b>all</b> negative values signal this. If you want to use this constant  to check against a value,
     * use {@code <= SERVER_DEFAULT_BUFFER_SIZE}.
     * </p>
     */
    int SERVER_DEFAULT_BUFFER_SIZE = -1;

    /**
     * Multi-error behaviour.
     *
     * @return {@code true} request multi-error (continue on failure), or {@code false} to halt execution on first error
     */
    boolean multiError();

    /**
     * Report update counts per element (called "record counts" in Firebird).
     *
     * @return {@code true} report update counts, {@code false} do not report update counts
     */
    boolean updateCounts();

    /**
     * Number of detailed errors to return.
     * <p>
     * A value of {@code 0} disables detailed errors. When a value {@code < 0} is returned, the server-side default is
     * used ({@code 64 as of Firebird 4}). Requesting detailed errors exceeding the maximum (256 as of Firebird 4) will
     * be silently set to the server maximum.
     * </p>
     *
     * @return number of detailed errors, {@code 0} to disable detailed errors, {@code < 0} for server default
     */
    int detailedErrors();

    /**
     * Server-side batch buffer size in bytes.
     * <p>
     * When a value {@code < 0} is returned, the server-side default (16MB as of Firebird 4) will be used.
     * When a value of {@code 0} is returned , the server-side maximum will be used. Requesting buffer sizes exceeding
     * the maximum (256MB as of Firebird 4) will be silently set to the server maximum.
     * </p>
     *
     * @return maximum batch buffer size, return {@code < 0} to use server-side default (16MB as of Firebird 4),
     * {@code 0} for the server-side maximum (256MB as of Firebird 4)
     */
    int batchBufferSize();

    // No config option for blob-policy, as we haven't implemented batch blob handling yet

    /**
     * @return immutable, frozen copy of this batch config
     */
    default FbBatchConfig immutable() {
        return new Immutable(this);
    }

    /**
     * Creates an immutable batch config.
     *
     * @return an immutable batch config with the specified configuration
     */
    static FbBatchConfig of(boolean multiError, boolean updateCounts, int detailedErrors, int batchBufferSize) {
        return new Immutable(multiError, updateCounts, detailedErrors, batchBufferSize);
    }

    /**
     * Populates the provided batch parameter buffer with the configuration.
     * <p>
     * This method will remove previously set items which are managed by this method. Items not managed by this method
     * are untouched. At minimum this method manages items {@code TAG_MULTIERROR}, {@code TAG_RECORD_COUNTS},
     * {@code TAG_DETAILED_ERRORS}, and {@code TAG_BUFFER_BYTES_SIZE}.
     * </p>
     *
     * @param batchPb
     *         batch parameter buffer to populate.
     */
    default void populateBatchParameterBuffer(BatchParameterBuffer batchPb) {
        IntStream.of(TAG_MULTIERROR, TAG_RECORD_COUNTS, TAG_DETAILED_ERRORS, TAG_BUFFER_BYTES_SIZE, TAG_BLOB_POLICY)
                .forEach(batchPb::removeArgument);
        if (multiError()) {
            batchPb.addArgument(TAG_MULTIERROR, 1);
        }
        if (updateCounts()) {
            batchPb.addArgument(TAG_RECORD_COUNTS, 1);
        }
        if (detailedErrors() >= 0) {
            batchPb.addArgument(TAG_DETAILED_ERRORS, detailedErrors());
        }
        if (batchBufferSize() >= 0) {
            batchPb.addArgument(TAG_BUFFER_BYTES_SIZE, batchBufferSize());
        }
        // Doesn't seem to make a difference in current implementation, in practice we register existing blobs under
        // their own id (see registerBlobs in V16Statement)
        // TODO Might need to change when implementing batching of blobs
        batchPb.addArgument(TAG_BLOB_POLICY, BLOB_ID_USER);
    }

    /**
     * Class to provide an immutable copy of a batch config.
     */
    final class Immutable implements FbBatchConfig {

        private final boolean multiError;
        private final boolean updateCounts;
        private final int detailedErrors;
        private final int batchBufferSize;

        private Immutable(FbBatchConfig config) {
            this(config.multiError(), config.updateCounts(), config.detailedErrors(), config.batchBufferSize());
        }

        private Immutable(boolean multiError, boolean updateCounts, int detailedErrors, int batchBufferSize) {
            this.multiError = multiError;
            this.updateCounts = updateCounts;
            this.detailedErrors = detailedErrors;
            this.batchBufferSize = batchBufferSize;
        }

        @Override
        public boolean multiError() {
            return multiError;
        }

        @Override
        public boolean updateCounts() {
            return updateCounts;
        }

        @Override
        public int detailedErrors() {
            return detailedErrors;
        }

        @Override
        public int batchBufferSize() {
            return batchBufferSize;
        }

        @Override
        public FbBatchConfig immutable() {
            return this;
        }
    }

}
