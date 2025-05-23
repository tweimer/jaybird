// SPDX-FileCopyrightText: Copyright 2014-2025 Mark Rotteveel
// SPDX-FileCopyrightText: Copyright 2018 Artyom Smirnov
// SPDX-FileCopyrightText: Copyright 2019 Vasiliy Yashkov
// SPDX-License-Identifier: LGPL-2.1-or-later
package org.firebirdsql.gds.ng.jna;

import com.sun.jna.Memory;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.ShortByReference;
import org.firebirdsql.gds.ISCConstants;
import org.firebirdsql.gds.JaybirdErrorCodes;
import org.firebirdsql.gds.ng.*;
import org.firebirdsql.gds.ng.fields.FieldDescriptor;
import org.firebirdsql.gds.ng.fields.RowDescriptor;
import org.firebirdsql.gds.ng.fields.RowValue;
import org.firebirdsql.gds.ng.listeners.DatabaseListener;
import org.firebirdsql.jaybird.util.Cleaners;
import org.firebirdsql.jdbc.SQLStateConstants;
import org.firebirdsql.jna.fbclient.FbClientLibrary;
import org.firebirdsql.jna.fbclient.ISC_STATUS;
import org.firebirdsql.jna.fbclient.XSQLDA;
import org.firebirdsql.jna.fbclient.XSQLVAR;

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.util.Arrays;

import static java.util.Objects.requireNonNull;
import static org.firebirdsql.gds.ng.TransactionHelper.checkTransactionActive;

/**
 * Implementation of {@link org.firebirdsql.gds.ng.FbStatement} for native client access.
 *
 * @author Mark Rotteveel
 * @since 3.0
 */
public class JnaStatement extends AbstractFbStatement {

    private static final System.Logger log = System.getLogger(JnaStatement.class.getName());

    private final IntByReference handle = new IntByReference(0);
    private final JnaDatabase database;
    private final ISC_STATUS[] statusVector = new ISC_STATUS[JnaDatabase.STATUS_VECTOR_SIZE];
    private final FbClientLibrary clientLibrary;
    private XSQLDA inXSqlDa;
    private XSQLDA outXSqlDa;
    private Cleaner.Cleanable cleanable = Cleaners.getNoOp();

    public JnaStatement(JnaDatabase database) {
        this.database = requireNonNull(database, "database");
        clientLibrary = database.getClientLibrary();
    }

    @Override
    public final LockCloseable withLock() {
        return database.withLock();
    }

    @Override
    protected void setParameterDescriptor(RowDescriptor parameterDescriptor) {
        final XSQLDA xsqlda = allocateXSqlDa(parameterDescriptor);
        try (LockCloseable ignored = withLock()) {
            inXSqlDa = xsqlda;
            super.setParameterDescriptor(parameterDescriptor);
        }
    }

    @Override
    protected void setRowDescriptor(RowDescriptor fieldDescriptor) {
        final XSQLDA xsqlda = allocateXSqlDa(fieldDescriptor);
        try (LockCloseable ignored = withLock()) {
            outXSqlDa = xsqlda;
            super.setRowDescriptor(fieldDescriptor);
        }
    }

    @Override
    protected void free(int option) throws SQLException {
        try (LockCloseable ignored = withLock()) {
            clientLibrary.isc_dsql_free_statement(statusVector, handle, (short) option);
            processStatusVector();
            // Reset statement information
            reset(option == ISCConstants.DSQL_drop);
        } finally {
            if (option == ISCConstants.DSQL_drop) {
                // prevent attempt to call isc_dsql_free_statement in CleanupAction as well
                handle.setValue(0);
                cleanable.clean();
            }
        }
    }

    @Override
    protected boolean isValidTransactionClass(Class<? extends FbTransaction> transactionClass) {
        return JnaTransaction.class.isAssignableFrom(transactionClass);
    }

    @Override
    public JnaDatabase getDatabase() {
        return database;
    }

    @Override
    public int getHandle() {
        return handle.getValue();
    }

    @Override
    public JnaTransaction getTransaction() {
        return (JnaTransaction) super.getTransaction();
    }

    @Override
    public void prepare(String statementText) throws SQLException {
        try {
            boolean useNulTerminated = false;
            byte[] statementArray = getDatabase().getEncoding().encodeToCharset(statementText);
            if (statementArray.length > JnaDatabase.MAX_STATEMENT_LENGTH) {
                if (database.hasFeature(FbClientFeature.FB_PING)) {
                    // Presence of FB_PING feature means this is a Firebird 3.0 or higher fbclient,
                    // so we can use null-termination to send statement texts longer than 64KB
                    statementArray = Arrays.copyOf(statementArray, statementArray.length + 1);
                    useNulTerminated = true;
                } else {
                    throw FbExceptionBuilder.forException(JaybirdErrorCodes.jb_maxStatementLengthExceeded)
                            .messageParameter(JnaDatabase.MAX_STATEMENT_LENGTH, statementArray.length)
                            .toSQLException();
                }
            }
            try (var ignored = withLock()) {
                final StatementState initialState = checkPrepareAllowed();
                resetAll();

                final JnaDatabase db = getDatabase();
                if (initialState == StatementState.NEW) {
                    allocateImpl(db);
                } else {
                    checkStatementValid();
                }
                prepareImpl(useNulTerminated, statementArray, db);
            }
        } catch (SQLException e) {
            exceptionListenerDispatcher.errorOccurred(e);
            throw e;
        }
    }

    private void allocateImpl(JnaDatabase db) throws SQLException {
        try {
            clientLibrary.isc_dsql_allocate_statement(statusVector, db.getJnaHandle(), handle);
            if (handle.getValue() != 0) {
                cleanable = Cleaners.getJbCleaner().register(this, new CleanupAction(handle, database));
            }
            processStatusVector();
            reset();
            switchState(StatementState.ALLOCATED);
            setType(StatementType.NONE);
        } catch (SQLException e) {
            forceState(StatementState.NEW);
            throw e;
        }
    }

    private void prepareImpl(boolean useNulTerminated, byte[] statementArray, JnaDatabase db) throws SQLException {
        switchState(StatementState.PREPARING);
        try {
            // Information in tempXSqlDa is ignored, as we are retrieving more detailed information using getSqlInfo
            final XSQLDA tempXSqlDa = new XSQLDA();
            tempXSqlDa.setAutoRead(false);
            clientLibrary.isc_dsql_prepare(statusVector, getTransaction().getJnaHandle(), handle,
                    useNulTerminated ? 0 : (short) statementArray.length, statementArray,
                    db.getConnectionDialect(), tempXSqlDa);
            processStatusVector();

            final byte[] statementInfoRequestItems = getStatementInfoRequestItems();
            final int responseLength = getDefaultSqlInfoSize();
            byte[] statementInfo = getSqlInfo(statementInfoRequestItems, responseLength);
            parseStatementInfo(statementInfo);
            switchState(StatementState.PREPARED);
        } catch (SQLException e) {
            switchState(StatementState.ALLOCATED);
            throw e;
        }
    }

    @Override
    public void execute(RowValue parameters) throws SQLException {
        final StatementState initialState = getState();
        try (LockCloseable ignored = withLock()) {
            checkStatementValid();
            checkTransactionActive(getTransaction());
            validateParameters(parameters);
            reset(false);

            switchState(StatementState.EXECUTING);
            updateStatementTimeout();

            setXSqlDaData(inXSqlDa, getParameterDescriptor(), parameters);
            final StatementType statementType = getType();
            final boolean hasSingletonResult = hasSingletonResult();

            try (OperationCloseHandle operationCloseHandle = signalExecute()) {
                if (operationCloseHandle.isCancelled()) {
                    // operation was synchronously cancelled from an OperationAware implementation
                    throw FbExceptionBuilder.toException(ISCConstants.isc_cancelled);
                }
                if (hasSingletonResult) {
                    clientLibrary.isc_dsql_execute2(statusVector, getTransaction().getJnaHandle(), handle,
                            inXSqlDa.version, inXSqlDa, outXSqlDa);
                } else {
                    clientLibrary.isc_dsql_execute(statusVector, getTransaction().getJnaHandle(), handle,
                            inXSqlDa.version, inXSqlDa);
                }

                if (hasSingletonResult) {
                    /* A type with a singleton result (ie an execute procedure with return fields), doesn't actually
                     * have a result set that will be fetched, instead we have a singleton result if we have fields
                     */
                    statementListenerDispatcher.statementExecuted(this, false, true);
                    processStatusVector();
                    queueRowData(toRowValue(getRowDescriptor(), outXSqlDa));
                    setAfterLast();
                } else {
                    // A normal execute is never a singleton result (even if it only produces a single result)
                    statementListenerDispatcher.statementExecuted(this, hasFields(), false);
                    processStatusVector();
                }
            }

            if (getState() != StatementState.ERROR) {
                switchState(statementType.isTypeWithCursor() ? StatementState.CURSOR_OPEN : StatementState.PREPARED);
            }
        } catch (SQLException e) {
            if (getState() != StatementState.ERROR) {
                switchState(initialState);
            }
            exceptionListenerDispatcher.errorOccurred(e);
            throw e;
        }
    }

    /**
     * Populates an XSQLDA from the row descriptor and parameter values.
     *
     * @param xSqlDa
     *         XSQLDA
     * @param rowDescriptor
     *         Row descriptor
     * @param parameters
     *         Parameter values
     */
    protected void setXSqlDaData(final XSQLDA xSqlDa, final RowDescriptor rowDescriptor, final RowValue parameters) {
        for (int idx = 0; idx < parameters.getCount(); idx++) {
            final XSQLVAR xSqlVar = xSqlDa.sqlvar[idx];
            // Zero-fill sqldata
            xSqlVar.getSqlData().clear();

            byte[] fieldData = parameters.getFieldData(idx);
            if (fieldData == null) {
                // Note this only works because we mark the type as nullable in allocateXSqlDa
                xSqlVar.sqlind.setValue(XSQLVAR.SQLIND_NULL);
            } else {
                xSqlVar.sqlind.setValue(XSQLVAR.SQLIND_NOT_NULL);

                // TODO Throw truncation error if fieldData longer than sqllen?

                final FieldDescriptor fieldDescriptor = rowDescriptor.getFieldDescriptor(idx);
                int bufferOffset = 0;
                if (fieldDescriptor.isVarying()) {
                    // Only send the data we need
                    xSqlVar.sqllen = (short) Math.min(fieldDescriptor.getLength(), fieldData.length);
                    xSqlVar.writeField("sqllen");
                    xSqlVar.sqldata.setShort(0, (short) fieldData.length);
                    bufferOffset = 2;
                } else if (fieldDescriptor.isFbType(ISCConstants.SQL_TEXT)) {
                    // Only send the data we need
                    xSqlVar.sqllen = (short) Math.min(fieldDescriptor.getLength(), fieldData.length);
                    xSqlVar.writeField("sqllen");
                    if (fieldDescriptor.getSubType() != ISCConstants.CS_BINARY) {
                        // Non-binary CHAR field: fill with spaces
                        xSqlVar.sqldata.setMemory(0, xSqlVar.sqllen & 0xffff, (byte) ' ');
                    }
                }
                xSqlVar.sqldata.write(bufferOffset, fieldData, 0, fieldData.length);
            }
        }
    }

    /**
     * Creates an XSQLDA, populates type information and allocates memory for the sqldata fields.
     *
     * @param rowDescriptor
     *         The row descriptor
     * @return Allocated XSQLDA without data
     */
    protected XSQLDA allocateXSqlDa(RowDescriptor rowDescriptor) {
        if (rowDescriptor == null || rowDescriptor.getCount() == 0) {
            final XSQLDA xSqlDa = new XSQLDA(1);
            xSqlDa.setAutoSynch(false);
            xSqlDa.sqld = xSqlDa.sqln = 0;
            xSqlDa.write();
            return xSqlDa;
        }
        final XSQLDA xSqlDa = new XSQLDA(rowDescriptor.getCount());
        xSqlDa.setAutoSynch(false);

        for (int idx = 0; idx < rowDescriptor.getCount(); idx++) {
            final FieldDescriptor fieldDescriptor = rowDescriptor.getFieldDescriptor(idx);
            final XSQLVAR xSqlVar = xSqlDa.sqlvar[idx];

            populateXSqlVar(fieldDescriptor, xSqlVar);
        }
        xSqlDa.write();
        return xSqlDa;
    }

    private void populateXSqlVar(FieldDescriptor fieldDescriptor, XSQLVAR xSqlVar) {
        xSqlVar.setAutoSynch(false);
        xSqlVar.sqltype = (short) (fieldDescriptor.getType() | 1); // Always make nullable
        xSqlVar.sqlsubtype = (short) fieldDescriptor.getSubType();
        xSqlVar.sqlscale = (short) fieldDescriptor.getScale();
        xSqlVar.sqllen = (short) fieldDescriptor.getLength();
        xSqlVar.sqlind = new ShortByReference();

        final int requiredDataSize = fieldDescriptor.isVarying()
                ? fieldDescriptor.getLength() + 3 // 2 bytes for length, 1 byte for nul terminator
                : fieldDescriptor.getLength() + 1; // 1 byte for nul terminator

        xSqlVar.sqldata = new Memory(requiredDataSize);
        xSqlVar.write();
    }

    /**
     * Converts the data from an XSQLDA to a RowValue.
     *
     * @param rowDescriptor
     *         Row descriptor
     * @param xSqlDa
     *         XSQLDA
     * @return Row value
     */
    protected RowValue toRowValue(RowDescriptor rowDescriptor, XSQLDA xSqlDa) {
        final RowValue row = rowDescriptor.createDefaultFieldValues();

        for (int idx = 0; idx < xSqlDa.sqlvar.length; idx++) {
            final XSQLVAR xSqlVar = xSqlDa.sqlvar[idx];

            if (xSqlVar.sqlind.getValue() == XSQLVAR.SQLIND_NULL) {
                row.setFieldData(idx, null);
            } else {
                int bufferOffset;
                int bufferLength;

                if (rowDescriptor.getFieldDescriptor(idx).isVarying()) {
                    bufferOffset = 2;
                    bufferLength = xSqlVar.sqldata.getShort(0) & 0xffff;
                } else {
                    bufferOffset = 0;
                    bufferLength = xSqlVar.sqllen & 0xffff;
                }

                byte[] data = new byte[bufferLength];
                xSqlVar.sqldata.read(bufferOffset, data, 0, bufferLength);
                row.setFieldData(idx, data);
            }
        }
        return row;
    }

    /**
     * {@inheritDoc}
     * <p>
     * The JNA implementation ignores the specified {@code fetchSize} to prevent problems with - for example -
     * positioned updates with named cursors. For the wire protocol that case is handled by the server ignoring the
     * fetch size. Internally the native fetch will batch a number of records, but the number is outside our control.
     * </p>
     */
    @Override
    public void fetchRows(int fetchSize) throws SQLException {
        try (var ignored = withLock()) {
            checkStatementHasOpenCursor();
            checkFetchSize(fetchSize);
            if (isAfterLast()) return;

            try (OperationCloseHandle operationCloseHandle = signalFetch()) {
                if (operationCloseHandle.isCancelled()) {
                    // operation was synchronously cancelled from an OperationAware implementation
                    throw FbExceptionBuilder.toException(ISCConstants.isc_cancelled);
                }
                final ISC_STATUS fetchStatus = clientLibrary.isc_dsql_fetch(statusVector, handle, outXSqlDa.version,
                        outXSqlDa);
                processStatusVector();

                switch (fetchStatus.intValue()) {
                case ISCConstants.FETCH_OK -> {
                    queueRowData(toRowValue(getRowDescriptor(), outXSqlDa));
                    statementListenerDispatcher.fetchComplete(this, FetchDirection.FORWARD, 1);
                }
                case ISCConstants.FETCH_NO_MORE_ROWS -> {
                    statementListenerDispatcher.fetchComplete(this, FetchDirection.FORWARD, 0);
                    setAfterLast();
                    // Note: we are not explicitly 'closing' the cursor here
                }
                default -> {
                    final String message = "Unexpected fetch status (expected 0 or 100): " + fetchStatus;
                    log.log(System.Logger.Level.DEBUG, message);
                    throw new SQLException(message, SQLStateConstants.SQL_STATE_GENERAL_ERROR);
                }
                }
            }
        } catch (SQLException e) {
            exceptionListenerDispatcher.errorOccurred(e);
            throw e;
        }
    }

    @Override
    public byte[] getSqlInfo(byte[] requestItems, int bufferLength) throws SQLException {
        try {
            final ByteBuffer responseBuffer = ByteBuffer.allocateDirect(bufferLength);

            try (LockCloseable ignored = withLock()) {
                checkStatementValid();
                clientLibrary.isc_dsql_sql_info(statusVector, handle,
                        (short) requestItems.length, requestItems,
                        (short) bufferLength, responseBuffer);
                processStatusVector();
            }

            byte[] responseArr = new byte[bufferLength];
            responseBuffer.get(responseArr);
            return responseArr;
        } catch (SQLException e) {
            exceptionListenerDispatcher.errorOccurred(e);
            throw e;
        }
    }

    @Override
    public int getDefaultSqlInfoSize() {
        return getMaxSqlInfoSize();
    }

    @Override
    public int getMaxSqlInfoSize() {
        // Theoretically, also Firebird 2.1, but not supported since before Jaybird 5
        return getDatabase().getServerVersion().isEqualOrAbove(2, 5) ? 65535 : 32767;
    }

    @Override
    protected void setCursorNameImpl(String cursorName) throws SQLException {
        final JnaDatabase db = getDatabase();
        clientLibrary.isc_dsql_set_cursor_name(statusVector, handle,
                // Null termination is needed due to a quirk of the protocol
                db.getEncoding().encodeToCharset(cursorName + '\0'),
                // Cursor type
                (short) 0);
        processStatusVector();
    }

    private void updateStatementTimeout() throws SQLException {
        if (!database.hasFeature(FbClientFeature.STATEMENT_TIMEOUT)) {
            // no statement timeouts, do nothing
            return;
        }
        int allowedTimeout = (int) getAllowedTimeout();
        clientLibrary.fb_dsql_set_timeout(statusVector, handle, allowedTimeout);
        processStatusVector();
    }

    @Override
    public final RowDescriptor emptyRowDescriptor() {
        return database.emptyRowDescriptor();
    }

    private void processStatusVector() throws SQLException {
        getDatabase().processStatusVector(statusVector, getStatementWarningCallback());
    }

    private static final class CleanupAction implements Runnable, DatabaseListener {

        private final IntByReference handle;
        @SuppressWarnings("java:S3077")
        private volatile JnaDatabase database;

        private CleanupAction(IntByReference handle, JnaDatabase database) {
            this.handle = handle;
            this.database = database;
            database.addWeakDatabaseListener(this);
        }

        @Override
        public void detaching(FbDatabase database) {
            this.database = null;
            database.removeDatabaseListener(this);
        }

        @Override
        public void run() {
            JnaDatabase database = this.database;
            if (database == null) return;
            detaching(database);
            if (handle.getValue() == 0 || !database.isAttached()) return;
            database.getClientLibrary().isc_dsql_free_statement(
                    new ISC_STATUS[JnaDatabase.STATUS_VECTOR_SIZE], handle, (short) ISCConstants.DSQL_drop);
        }
    }
}
