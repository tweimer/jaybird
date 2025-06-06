// SPDX-FileCopyrightText: Copyright 2023-2024 Mark Rotteveel
// SPDX-License-Identifier: LGPL-2.1-or-later
package org.firebirdsql.gds.ng.wire;

import java.sql.SQLException;
import java.util.Optional;

/**
 * Asynchronous fetch status.
 *
 * @author Mark Rotteveel
 * @since 6
 */
public sealed interface AsyncFetchStatus
        permits AsyncFetchStatus.NonePending, AsyncFetchStatus.Pending {

    /**
     * Is this a status signalling an async fetch is pending (which includes having completed with an exception).
     *
     * @return {@code true} if this status is pending
     */
    boolean isPending();

    /**
     * @return exception from the last unsuccessfully completed asynchronous fetch
     */
    default Optional<SQLException> exception() {
        return Optional.empty();
    }

    /**
     * @return status instance which represents no asynchronous fetch is pending
     */
    static AsyncFetchStatus nonePending() {
        return NonePending.INSTANCE;
    }

    /**
     * @return status instance which represents a pending asynchronous fetch
     */
    static AsyncFetchStatus pending() {
        return Pending.INSTANCE;
    }

    /**
     * @return status instance which represents a successfully completed asynchronous fetch (same as
     * {@link #nonePending()})
     * @see #nonePending()
     * @see #completedWithException(SQLException)
     */
    static AsyncFetchStatus completed() {
        return nonePending();
    }

    /**
     * @param exception
     *         exception resulting from the asynchronous completion
     * @return status instance which represents an asynchronous fetch which completed with an exception; this is
     * considered a still pending operation, where the pending action is throwing the exception
     */
    static AsyncFetchStatus completedWithException(SQLException exception) {
        return new CompletedWithException(exception);
    }

    /**
     * Signals no async fetch pending.
     */
    final class NonePending implements AsyncFetchStatus {

        private static final NonePending INSTANCE = new NonePending();

        private NonePending() {
        }

        @Override
        public boolean isPending() {
            return false;
        }
    }

    /**
     * Signals an async fetch pending.
     */
    @SuppressWarnings("java:S6217")
    sealed class Pending implements AsyncFetchStatus permits AsyncFetchStatus.CompletedWithException {

        private static final Pending INSTANCE = new Pending();

        private Pending() {
        }

        @Override
        public final boolean isPending() {
            return true;
        }
    }

    /**
     * Signals an async fetch which completed with an exception.
     * <p>
     * Although the async operation itself completed, this is considered to be still pending, where the completion
     * operation is throwing the exception to the caller of a {@code fetch}.
     * </p>
     */
    @SuppressWarnings("java:S2166")
    final class CompletedWithException extends Pending {

        private final SQLException completionException;

        private CompletedWithException(SQLException completionException) {
            this.completionException = completionException;
        }

        @Override
        public Optional<SQLException> exception() {
            return Optional.of(completionException);
        }

    }

}
