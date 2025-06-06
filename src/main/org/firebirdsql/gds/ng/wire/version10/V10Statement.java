// SPDX-FileCopyrightText: Copyright 2013-2025 Mark Rotteveel
// SPDX-FileCopyrightText: Copyright 2019 Vasiliy Yashkov
// SPDX-License-Identifier: LGPL-2.1-or-later
package org.firebirdsql.gds.ng.wire.version10;

import org.firebirdsql.gds.ISCConstants;
import org.firebirdsql.gds.impl.wire.WireProtocolConstants;
import org.firebirdsql.gds.impl.wire.XdrInputStream;
import org.firebirdsql.gds.impl.wire.XdrOutputStream;
import org.firebirdsql.gds.ng.*;
import org.firebirdsql.gds.ng.fields.*;
import org.firebirdsql.gds.ng.wire.*;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLWarning;

import static org.firebirdsql.gds.ng.TransactionHelper.checkTransactionActive;

/**
 * {@link org.firebirdsql.gds.ng.wire.FbWireStatement} implementation for the version 10 wire protocol.
 *
 * @author Mark Rotteveel
 * @since 3.0
 */
public class V10Statement extends AbstractFbWireStatement implements FbWireStatement {

    // TODO Handle error state in a consistent way (eg when does an exception lead to the error state, or when is it 'just' valid feedback)
    // TODO Fix state transitions

    private static final int NULL_INDICATOR_NOT_NULL = 0;
    private static final int NULL_INDICATOR_NULL = -1;

    private static final System.Logger log = System.getLogger(V10Statement.class.getName());

    /**
     * Creates a new instance of V10Statement for the specified database.
     *
     * @param database
     *         FbWireDatabase implementation
     */
    public V10Statement(FbWireDatabase database) {
        super(database);
    }

    @Override
    protected void free(final int option) throws SQLException {
        try (LockCloseable ignored = withLock()) {
            try {
                doFreePacket(option);
                getXdrOut().flush();
            } catch (IOException e) {
                switchState(StatementState.ERROR);
                throw FbExceptionBuilder.ioWriteError(e);
            }
            try {
                processFreeResponse(getDatabase().readResponse(getStatementWarningCallback()));
            } catch (IOException e) {
                switchState(StatementState.ERROR);
                throw FbExceptionBuilder.ioReadError(e);
            }
        }
    }

    /**
     * Handles sending the <em>free statement</em> packet and associated state changes on this statement
     *
     * @param option
     *         <em>free statement</em> option
     */
    protected void doFreePacket(int option) throws SQLException, IOException {
        sendFree(option);

        // Reset statement information
        reset(option == ISCConstants.DSQL_drop);
    }

    /**
     * Sends the <em>free statement</em> to the database
     *
     * @param option
     *         Free statement option
     */
    protected void sendFree(int option) throws IOException, SQLException {
        final XdrOutputStream xdrOut = getXdrOut();
        xdrOut.writeInt(WireProtocolConstants.op_free_statement); // p_operation
        xdrOut.writeInt(getHandle()); // p_sqlfree_statement
        xdrOut.writeInt(option); // p_sqlfree_option
    }

    /**
     * Processes the response to the <em>free statement</em>.
     *
     * @param response
     *         Response object
     */
    protected void processFreeResponse(@SuppressWarnings("unused") Response response) {
        // No processing needed
    }

    @Override
    public void prepare(final String statementText) throws SQLException {
        try (var ignored = withLock()) {
            final StatementState initialState = checkPrepareAllowed();
            resetAll();

            if (initialState == StatementState.NEW) {
                sendAllocate0();
                receiveAllocate0Response();
            } else {
                checkStatementValid();
            }

            sendPrepare0(statementText);
            receivePrepare0Response();
        } catch (SQLException e) {
            exceptionListenerDispatcher.errorOccurred(e);
            throw e;
        }
    }

    private void sendAllocate0() throws SQLException {
        try {
            sendAllocate();
            getXdrOut().flush();
        } catch (IOException e) {
            switchState(StatementState.ERROR);
            throw FbExceptionBuilder.ioWriteError(e);
        }
    }

    private void receiveAllocate0Response() throws SQLException {
        try {
            processAllocateResponse(getDatabase().readGenericResponse(getStatementWarningCallback()));
            switchState(StatementState.ALLOCATED);
        } catch (IOException e) {
            switchState(StatementState.ERROR);
            throw FbExceptionBuilder.ioReadError(e);
        } catch (SQLException e) {
            forceState(StatementState.NEW);
            throw e;
        }
    }

    private void sendPrepare0(String statementText) throws SQLException {
        try {
            sendPrepare(statementText);
            getXdrOut().flush();
        } catch (IOException e) {
            switchState(StatementState.ERROR);
            throw FbExceptionBuilder.ioWriteError(e);
        }
    }

    private void receivePrepare0Response() throws SQLException {
        try {
            processPrepareResponse(getDatabase().readGenericResponse(getStatementWarningCallback()));
        } catch (IOException e) {
            switchState(StatementState.ERROR);
            throw FbExceptionBuilder.ioReadError(e);
        } catch (SQLException e) {
            switchState(StatementState.ALLOCATED);
            throw e;
        }
    }

    /**
     * Sends the statement <em>prepare</em> to the connection.
     *
     * @param statementText
     *         Statement
     */
    protected void sendPrepare(final String statementText) throws SQLException, IOException {
        switchState(StatementState.PREPARING);
        final XdrOutputStream xdrOut = getXdrOut();
        xdrOut.writeInt(WireProtocolConstants.op_prepare_statement); // p_operation
        xdrOut.writeInt(getTransaction().getHandle()); // p_sqlst_transaction
        xdrOut.writeInt(getHandle()); // p_sqlst_statement
        xdrOut.writeInt(getDatabase().getConnectionDialect()); // p_sqlst_SQL_dialect
        xdrOut.writeString(statementText, getDatabase().getEncoding()); // p_sqlst_SQL_str
        xdrOut.writeBuffer(getStatementInfoRequestItems()); // p_sqlst_items
        xdrOut.writeInt(getDefaultSqlInfoSize()); // p_sqlst_buffer_length
    }

    /**
     * Processes the <em>prepare</em> response from the server.
     *
     * @param genericResponse
     *         GenericResponse
     */
    protected void processPrepareResponse(final GenericResponse genericResponse) throws SQLException {
        parseStatementInfo(genericResponse.data());
        switchState(StatementState.PREPARED);
    }

    @Override
    protected void setCursorNameImpl(String cursorName)throws SQLException {
        try {
            final XdrOutputStream xdrOut = getXdrOut();
            xdrOut.writeInt(WireProtocolConstants.op_set_cursor); // p_operation
            xdrOut.writeInt(getHandle()); // p_sqlcur_statement
            // Null termination is needed due to a quirk of the protocol
            xdrOut.writeString(cursorName + '\0', getDatabase().getEncoding()); // p_sqlcur_cursor_name
            xdrOut.writeInt(0); // // p_sqlcur_type
            xdrOut.flush();
        } catch (IOException e) {
            switchState(StatementState.ERROR);
            throw FbExceptionBuilder.ioWriteError(e);
        }
        try {
            getDatabase().readGenericResponse(getStatementWarningCallback());
        } catch (IOException e) {
            switchState(StatementState.ERROR);
            throw FbExceptionBuilder.ioReadError(e);
        }
    }

    @Override
    public void execute(final RowValue parameters) throws SQLException {
        final StatementState initialState = getState();
        try (LockCloseable ignored = withLock()) {
            checkStatementValid();
            checkTransactionActive(getTransaction());
            validateParameters(parameters);
            reset(false);

            switchState(StatementState.EXECUTING);

            final StatementType statementType = getType();
            final boolean hasSingletonResult = hasSingletonResult();
            int expectedResponseCount = 0;

            try (OperationCloseHandle operationCloseHandle = signalExecute()) {
                if (operationCloseHandle.isCancelled()) {
                    // operation was synchronously cancelled from an OperationAware implementation
                    throw FbExceptionBuilder.toException(ISCConstants.isc_cancelled);
                }
                try {
                    if (hasSingletonResult) {
                        expectedResponseCount++;
                    }
                    sendExecute(hasSingletonResult
                                    ? WireProtocolConstants.op_execute2
                                    : WireProtocolConstants.op_execute,
                            parameters);
                    expectedResponseCount++;
                    getXdrOut().flush();
                } catch (IOException e) {
                    switchState(StatementState.ERROR);
                    throw FbExceptionBuilder.ioWriteError(e);
                }

                final WarningMessageCallback statementWarningCallback = getStatementWarningCallback();
                try {
                    final FbWireDatabase db = getDatabase();
                    try {
                        expectedResponseCount--;
                        Response response = db.readResponse(statementWarningCallback);
                        if (hasSingletonResult) {
                            /* A type with a singleton result (ie an execute procedure with return fields), doesn't actually
                             * have a result set that will be fetched, instead we have a singleton result if we have fields
                             */
                            statementListenerDispatcher.statementExecuted(this, false, true);
                            if (response instanceof SqlResponse sqlResponse) {
                                processExecuteSingletonResponse(sqlResponse);
                                expectedResponseCount--;
                                response = db.readResponse(statementWarningCallback);
                            } else {
                                // We didn't get an op_sql_response first, something is iffy, maybe cancellation or very low level problem?
                                // We don't expect any more responses after this
                                expectedResponseCount = 0;
                                SQLWarning sqlWarning = new SQLWarning(
                                        "Expected an SqlResponse, instead received a " + response.getClass().getName());
                                log.log(System.Logger.Level.WARNING, "Unexpected response; see debug level for stacktrace");
                                log.log(System.Logger.Level.DEBUG, "Unexpected response", sqlWarning);
                                statementWarningCallback.processWarning(sqlWarning);
                            }
                            setAfterLast();
                        } else {
                            // A normal execute is never a singleton result (even if it only produces a single result)
                            statementListenerDispatcher.statementExecuted(this, hasFields(), false);
                        }

                        // This should always be a GenericResponse, otherwise something went fundamentally wrong anyway
                        processExecuteResponse((GenericResponse) response);
                    } catch (SQLException e) {
                        if (e.getErrorCode() == ISCConstants.isc_cancelled) {
                            expectedResponseCount = 0;
                        }
                        throw e;
                    } finally {
                        db.consumePackets(expectedResponseCount, getStatementWarningCallback());
                    }

                    if (getState() != StatementState.ERROR) {
                        switchState(statementType.isTypeWithCursor() ? StatementState.CURSOR_OPEN : StatementState.PREPARED);
                    }
                } catch (IOException e) {
                    switchState(StatementState.ERROR);
                    throw FbExceptionBuilder.ioReadError(e);
                }
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
     * Sends the <em>execute</em> (for {@code op_execute} or {@code op_execute2}) to the database.
     *
     * @param operation
     *         Operation ({@code op_execute} or {@code op_execute2})
     * @param parameters
     *         Parameters
     */
    protected void sendExecute(final int operation, final RowValue parameters) throws IOException, SQLException {
        assert operation == WireProtocolConstants.op_execute || operation == WireProtocolConstants.op_execute2 : "Needs to be called with operation op_execute or op_execute2";
        final XdrOutputStream xdrOut = getXdrOut();
        xdrOut.writeInt(operation); // p_operation
        xdrOut.writeInt(getHandle()); // p_sqldata_statement
        xdrOut.writeInt(getTransaction().getHandle()); // p_sqldata_transaction

        if (parameters != null && parameters.getCount() > 0) {
            final RowDescriptor parameterDescriptor = getParameterDescriptor();
            xdrOut.writeBuffer(calculateBlr(parameterDescriptor, parameters)); // p_sqldata_blr
            xdrOut.writeInt(0); // p_sqldata_message_number
            xdrOut.writeInt(1); // p_sqldata_messages
            writeSqlData(parameterDescriptor, parameters, true); // parameter data
        } else {
            xdrOut.writeBuffer(null); // p_sqldata_blr
            xdrOut.writeInt(0); // p_sqldata_message_number
            xdrOut.writeInt(0); // p_sqldata_messages
        }

        if (operation == WireProtocolConstants.op_execute2) {
            final RowDescriptor fieldDescriptor = getRowDescriptor();
            xdrOut.writeBuffer(
                    fieldDescriptor != null && fieldDescriptor.getCount() > 0 ? calculateBlr(fieldDescriptor) : null); // p_sqldata_out_blr
            xdrOut.writeInt(0); // p_sqldata_out_message_number
        }
    }

    /**
     * Process the <em>execute</em> response for statements with a singleton response ({@code op_execute2}; stored
     * procedures).
     *
     * @param sqlResponse
     *         SQL response object
     */
    protected void processExecuteSingletonResponse(SqlResponse sqlResponse) throws SQLException, IOException {
        if (sqlResponse.count() > 0) {
            queueRowData(readSqlData());
        }
    }

    /**
     * Process the <em>execute</em> response.
     *
     * @param genericResponse
     *         Generic response object
     */
    @SuppressWarnings("unused")
    protected void processExecuteResponse(GenericResponse genericResponse) {
        // Nothing to do here
    }

    @Override
    public void fetchRows(int fetchSize) throws SQLException {
        try (LockCloseable ignored = withLock()) {
            checkStatementHasOpenCursor();
            checkFetchSize(fetchSize);
            if (isAfterLast()) return;

            try (OperationCloseHandle operationCloseHandle = signalFetch()) {
                if (operationCloseHandle.isCancelled()) {
                    // operation was synchronously cancelled from an OperationAware implementation
                    throw FbExceptionBuilder.toException(ISCConstants.isc_cancelled);
                }
                sendFetch0(fetchSize);
                receiveFetch0Response();
            }
        } catch (SQLException e) {
            exceptionListenerDispatcher.errorOccurred(e);
            throw e;
        }
    }

    private void sendFetch0(int fetchSize) throws SQLException {
        try {
            sendFetch(fetchSize);
            getXdrOut().flush();
        } catch (IOException e) {
            switchState(StatementState.ERROR);
            throw FbExceptionBuilder.ioWriteError(e);
        }
    }

    private void receiveFetch0Response() throws SQLException {
        try {
            processFetchResponse(FetchDirection.FORWARD);
        } catch (IOException e) {
            switchState(StatementState.ERROR);
            throw FbExceptionBuilder.ioReadError(e);
        }
    }

    /**
     * Process the <em>fetch</em> response by reading the returned rows and queuing them.
     *
     * @param direction
     *         fetch direction
     */
    protected void processFetchResponse(FetchDirection direction) throws IOException, SQLException {
        processFetchResponse(direction, null);
    }

    /**
     * Process the <em>fetch</em> response by reading the returned rows and queuing them.
     *
     * @param direction
     *         fetch direction
     * @param response initial response, or {@code null} to retrieve initial response
     */
    protected void processFetchResponse(FetchDirection direction, Response response) throws IOException, SQLException {
        int rowsFetched = 0;
        FbWireDatabase database = getDatabase();
        if (response == null) {
            response = getDatabase().readResponse(getStatementWarningCallback());
        }
        do {
            if (!(response instanceof FetchResponse fetchResponse)) break;
            if (fetchResponse.status() == ISCConstants.FETCH_OK && fetchResponse.count() > 0) {
                // Received a row
                queueRowData(readSqlData());
                rowsFetched++;
            } else if (fetchResponse.status() == ISCConstants.FETCH_OK && fetchResponse.count() == 0) {
                // end of batch, but not end of cursor
                // Exit loop
                break;
            } else if (fetchResponse.status() == ISCConstants.FETCH_NO_MORE_ROWS) {
                // end of cursor
                switch (direction) {
                case IN_PLACE -> {
                    if (isBeforeFirst()) {
                        setBeforeFirst();
                    } else {
                        setAfterLast();
                    }
                }

                case FORWARD,
                        // Not generally applicable, but handling same as FORWARD (as after-last)
                        UNKNOWN -> setAfterLast();
                case REVERSE -> setBeforeFirst();
                }
                // Note: we are not explicitly 'closing' the cursor here as we might be scrolling
                // Exit loop
                break;
            } else {
                log.log(System.Logger.Level.DEBUG, "Received unexpected fetch response {0}, ignored", fetchResponse);
                break;
            }
        } while ((response = database.readResponse(getStatementWarningCallback())) instanceof FetchResponse);
        statementListenerDispatcher.fetchComplete(this, direction, rowsFetched);
        // TODO Handle other response type?
    }

    /**
     * Sends the <em>fetch</em> request to the database.
     *
     * @param fetchSize Number of rows to fetch.
     */
    protected void sendFetch(int fetchSize) throws SQLException, IOException {
        final XdrOutputStream xdrOut = getXdrOut();
        xdrOut.writeInt(WireProtocolConstants.op_fetch); // p_operation
        xdrOut.writeInt(getHandle()); // p_sqldata_statement
        xdrOut.writeBuffer(hasFetched() ? null : calculateBlr(getRowDescriptor())); // p_sqldata_blr
        xdrOut.writeInt(0); // p_sqldata_message_number
        xdrOut.writeInt(fetchSize); // p_sqldata_messages
    }

    /**
     * Reads a single row from the database.
     *
     * @return Row as a {@link RowValue}
     */
    protected RowValue readSqlData() throws SQLException, IOException {
        final RowDescriptor rowDescriptor = getRowDescriptor();
        final RowValue rowValue = rowDescriptor.createDefaultFieldValues();
        final BlrCalculator blrCalculator = getBlrCalculator();

        final XdrInputStream xdrIn = getXdrIn();

        for (int idx = 0; idx < rowDescriptor.getCount(); idx++) {
            final FieldDescriptor fieldDescriptor = rowDescriptor.getFieldDescriptor(idx);
            final int len = blrCalculator.calculateIoLength(fieldDescriptor);
            byte[] buffer = readColumnData(xdrIn, len);
            if (xdrIn.readInt() == NULL_INDICATOR_NULL) {
                buffer = null;
            }
            rowValue.setFieldData(idx, buffer);
        }
        return rowValue;
    }

    protected byte[] readColumnData(XdrInputStream xdrIn, int len) throws IOException {
        if (len == 0) {
            // Length specified in response
            return xdrIn.readBuffer();
        } else if (len < 0) {
            // Buffer is not padded
            return xdrIn.readRawBuffer(-len);
        } else {
            // len is incremented in calculateIoLength to avoid value 0, so it must be decremented
            return xdrIn.readBuffer(len - 1);
        }
    }

    /**
     * Write a set of SQL data from a {@link RowValue}.
     *
     * @param rowDescriptor
     *         The row descriptor
     * @param fieldValues
     *         The List containing the SQL data to be written
     * @param useActualLength
     *         Should actual field length be used (applies to CHAR)
     * @throws IOException
     *         if an error occurs while writing to the underlying output stream
     */
    protected void writeSqlData(RowDescriptor rowDescriptor, RowValue fieldValues, boolean useActualLength)
        throws IOException, SQLException {
        final XdrOutputStream xdrOut = getXdrOut();
        final BlrCalculator blrCalculator = getBlrCalculator();
        for (int idx = 0; idx < fieldValues.getCount(); idx++) {
            final FieldDescriptor fieldDescriptor = rowDescriptor.getFieldDescriptor(idx);
            final byte[] buffer = fieldValues.getFieldData(idx);
            final int len = useActualLength
                    ? blrCalculator.calculateIoLength(fieldDescriptor, buffer)
                    : blrCalculator.calculateIoLength(fieldDescriptor);
            writeColumnData(xdrOut, len, buffer, fieldDescriptor);
            // sqlind (null indicator)
            xdrOut.writeInt(buffer != null ? NULL_INDICATOR_NOT_NULL : NULL_INDICATOR_NULL);
        }
    }

    protected void writeColumnData(XdrOutputStream xdrOut, int len, byte[] buffer, FieldDescriptor fieldDescriptor)
            throws IOException {
        // Nothing to write for SQL_NULL (except null indicator in v10 - v12, which happens at end in writeSqlData)
        if (fieldDescriptor.isFbType(ISCConstants.SQL_NULL)) return;

        if (len == 0) {
            writeLengthPrefixedBuffer(xdrOut, buffer, fieldDescriptor);
        } else if (len < 0) {
            writeFixedLengthBuffer(xdrOut, -len, buffer);
        } else {
            // decrement length because it was incremented before; increment happens in BlrCalculator.calculateIoLength
            writePaddedBuffer(xdrOut, len - 1, buffer, fieldDescriptor);
        }
    }

    /**
     * Writes the entire buffer prefixed with length and suffixed with padding using the padding byte of the descriptor.
     */
    private static void writeLengthPrefixedBuffer(XdrOutputStream xdrOut, byte[] buffer,
            FieldDescriptor fieldDescriptor) throws IOException {
        if (buffer != null) {
            int len = buffer.length;
            xdrOut.writeInt(len);
            xdrOut.write(buffer, 0, len);
            xdrOut.writePadding((4 - len) & 3, fieldDescriptor.getPaddingByte());
        } else {
            xdrOut.writeInt(0);
        }
    }

    /**
     * Writes {@code len} bytes of the buffer, or if {@code buffer == null}, {@code len} bytes of zero padding.
     */
    private static void writeFixedLengthBuffer(XdrOutputStream xdrOut, int len, byte[] buffer) throws IOException {
        if (buffer != null) {
            xdrOut.write(buffer, 0, len);
        } else {
            xdrOut.writeZeroPadding(len);
        }
    }

    /**
     * Writes at most {@code len} bytes from {@code buffer}, padding upto {@code len} if the buffer is shorter or
     * {@code null}, suffixed with the normal buffer padding. Padding is done with the padding byte from the descriptor.
     */
    private static void writePaddedBuffer(XdrOutputStream xdrOut, int len, byte[] buffer,
            FieldDescriptor fieldDescriptor) throws IOException {
        if (buffer != null) {
            final int buflen = buffer.length;
            if (buflen >= len) {
                xdrOut.write(buffer, 0, len);
                xdrOut.writePadding((4 - len) & 3, fieldDescriptor.getPaddingByte());
            } else {
                xdrOut.write(buffer, 0, buflen);
                xdrOut.writePadding(len - buflen + ((4 - len) & 3), fieldDescriptor.getPaddingByte());
            }
        } else {
            xdrOut.writePadding(len + ((4 - len) & 3), fieldDescriptor.getPaddingByte());
        }
    }

    /**
     * Sends the <em>allocate</em> request to the server.
     */
    protected void sendAllocate() throws SQLException, IOException {
        final XdrOutputStream xdrOut = getXdrOut();
        xdrOut.writeInt(WireProtocolConstants.op_allocate_statement);
        xdrOut.writeInt(0);
    }

    /**
     * Processes the <em>allocate</em> response from the server.
     *
     * @param response
     *         GenericResponse
     */
    @SuppressWarnings("java:S1130")
    protected void processAllocateResponse(GenericResponse response) throws SQLException {
        try (LockCloseable ignored = withLock()) {
            setHandle(response.objectHandle());
            reset();
            setType(StatementType.NONE);
        }
    }

    @Override
    public int getDefaultSqlInfoSize() {
        return getMaxSqlInfoSize();
    }

    @Override
    public int getMaxSqlInfoSize() {
        return 32767;
    }
}
