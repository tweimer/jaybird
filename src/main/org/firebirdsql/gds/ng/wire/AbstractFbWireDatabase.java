// SPDX-FileCopyrightText: Copyright 2013-2025 Mark Rotteveel
// SPDX-License-Identifier: LGPL-2.1-or-later
package org.firebirdsql.gds.ng.wire;

import org.firebirdsql.gds.*;
import org.firebirdsql.gds.impl.wire.XdrInputStream;
import org.firebirdsql.gds.impl.wire.XdrOutputStream;
import org.firebirdsql.gds.ng.*;

import java.io.IOException;
import java.sql.SQLException;

import static java.util.Objects.requireNonNull;
import static org.firebirdsql.gds.JaybirdErrorCodes.jb_notAttachedToDatabase;
import static org.firebirdsql.gds.JaybirdErrorCodes.jb_notConnectedToServer;
import static org.firebirdsql.gds.JaybirdErrorCodes.jb_unableToCancelEventReasonNotConnected;
import static org.firebirdsql.gds.impl.wire.WireProtocolConstants.PROTOCOL_VERSION18;
import static org.firebirdsql.gds.impl.wire.WireProtocolConstants.op_info_blob;
import static org.firebirdsql.gds.impl.wire.WireProtocolConstants.op_info_cursor;
import static org.firebirdsql.gds.impl.wire.WireProtocolConstants.op_info_database;
import static org.firebirdsql.gds.impl.wire.WireProtocolConstants.op_info_request;
import static org.firebirdsql.gds.impl.wire.WireProtocolConstants.op_info_sql;
import static org.firebirdsql.gds.impl.wire.WireProtocolConstants.op_info_transaction;

/**
 * Abstract class for operations common to all version of the wire protocol implementation.
 *
 * @author Mark Rotteveel
 * @since 3.0
 */
public abstract class AbstractFbWireDatabase extends AbstractFbDatabase<WireDatabaseConnection>
        implements FbWireDatabase {

    protected final ProtocolDescriptor protocolDescriptor;
    protected final FbWireOperations wireOperations;
    private FbWireAsynchronousChannel asynchronousChannel;

    /**
     * Creates an AbstractFbWireDatabase instance.
     *
     * @param connection
     *         A WireConnection with an established connection to the server.
     * @param descriptor
     *         The ProtocolDescriptor that created this connection (this is
     *         used for creating further dependent objects).
     */
    protected AbstractFbWireDatabase(WireDatabaseConnection connection, ProtocolDescriptor descriptor) {
        super(connection, DefaultDatatypeCoder.forEncodingFactory(connection.getEncodingFactory()));
        protocolDescriptor = requireNonNull(descriptor, "parameter descriptor should be non-null");
        wireOperations = descriptor.createWireOperations(connection, getDatabaseWarningCallback());
    }

    @Override
    public final int getHandle() {
        // The handle is always 0 for a TCP/IP database
        return 0;
    }

    @Override
    public void forceClose() throws SQLException {
        try {
            if (connection.isConnected()) {
                connection.close();
            }
        } catch (IOException e) {
            throw FbExceptionBuilder.ioWriteError(e);
        } finally {
            databaseListenerDispatcher.detached(this);
            databaseListenerDispatcher.shutdown();
            exceptionListenerDispatcher.shutdown();
        }
    }

    /**
     * Gets the XdrInputStream.
     *
     * @return Instance of XdrInputStream
     * @throws SQLException
     *         If no connection is opened or when exceptions occur
     *         retrieving the InputStream
     */
    protected final XdrInputStream getXdrIn() throws SQLException {
        return getXdrStreamAccess().getXdrIn();
    }

    /**
     * Gets the XdrOutputStream.
     *
     * @return Instance of XdrOutputStream
     * @throws SQLException
     *         If no connection is opened or when exceptions occur
     *         retrieving the OutputStream
     */
    protected final XdrOutputStream getXdrOut() throws SQLException {
        return getXdrStreamAccess().getXdrOut();
    }

    @Override
    public final XdrStreamAccess getXdrStreamAccess() {
        return connection.getXdrStreamAccess();
    }

    @Override
    public final FbWireOperations getWireOperations() {
        return wireOperations;
    }

    @Override
    public final boolean isAttached() {
        return super.isAttached() && isConnected();
    }

    /**
     * Checks if a physical connection to the server is established.
     *
     * @throws SQLException
     *         if not connected
     */
    @Override
    protected final void checkConnected() throws SQLException {
        if (!isConnected()) {
            throw FbExceptionBuilder.toNonTransientConnectionException(jb_notConnectedToServer);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true} if a physical connection to the server is established
     */
    @Override
    protected final boolean isConnected() {
        return connection.isConnected();
    }

    /**
     * Checks if a physical connection to the server is established and if the
     * connection is attached to a database.
     * <p>
     * This method calls {@link #checkConnected()}, so it is not necessary to
     * call both.
     * </p>
     *
     * @throws SQLException
     *         If the database not connected or attached.
     */
    protected final void checkAttached() throws SQLException {
        checkConnected();
        if (!isAttached()) {
            throw FbExceptionBuilder.toNonTransientConnectionException(jb_notAttachedToDatabase);
        }
    }

    /**
     * Closes the WireConnection associated with this connection.
     *
     * @throws IOException
     *         For errors closing the connection.
     */
    protected final void closeConnection() throws IOException {
        if (!connection.isConnected()) return;
        try (LockCloseable ignored = withLock()) {
            try {
                connection.close();
            } finally {
                setDetached();
            }
        }
    }

    @Override
    public void setNetworkTimeout(int milliseconds) throws SQLException {
        try (LockCloseable ignored = withLock()) {
            checkConnected();
            wireOperations.setNetworkTimeout(milliseconds);
        } catch (SQLException e) {
            exceptionListenerDispatcher.errorOccurred(e);
            throw e;
        }
    }

    @Override
    public final FbBlob createBlobForOutput(FbTransaction transaction, BlobParameterBuffer blobParameterBuffer)
            throws SQLException {
        final FbWireBlob outputBlob =
                protocolDescriptor.createOutputBlob(this, (FbWireTransaction) transaction, blobParameterBuffer);
        outputBlob.addExceptionListener(exceptionListenerDispatcher);
        return outputBlob;
    }

    @Override
    public final FbBlob createBlobForInput(FbTransaction transaction, BlobParameterBuffer blobParameterBuffer,
            long blobId) throws SQLException {
        final FbWireBlob inputBlob =
                protocolDescriptor.createInputBlob(this, (FbWireTransaction) transaction, blobParameterBuffer, blobId);
        inputBlob.addExceptionListener(exceptionListenerDispatcher);
        return inputBlob;
    }

    @Override
    public final void consumePackets(int numberOfResponses, WarningMessageCallback warningCallback) {
        // TODO Should consumePackets notify the exception listener or not?
        wireOperations.consumePackets(numberOfResponses, warningCallback);
    }

    @Override
    public final GenericResponse readGenericResponse(WarningMessageCallback warningCallback)
            throws SQLException, IOException {
        return wireOperations.readGenericResponse(warningCallback);
    }

    @Override
    public final SqlResponse readSqlResponse(WarningMessageCallback warningCallback) throws SQLException, IOException {
        return wireOperations.readSqlResponse(warningCallback);
    }

    @Override
    public final Response readResponse(WarningMessageCallback warningCallback) throws SQLException, IOException {
        return wireOperations.readResponse(warningCallback);
    }

    @Override
    public final void enqueueDeferredAction(DeferredAction deferredAction) throws SQLException {
        wireOperations.enqueueDeferredAction(deferredAction);
    }

    @Override
    public final void completeDeferredActions() throws SQLException {
        wireOperations.completeDeferredActions();
    }

    @Override
    public final EventHandle createEventHandle(String eventName, EventHandler eventHandler) {
        return new WireEventHandle(eventName, eventHandler, getEncoding());
    }

    @Override
    public final void queueEvent(EventHandle eventHandle) throws SQLException {
        try (LockCloseable ignored = withLock()) {
            checkAttached();
            FbWireAsynchronousChannel channel = asynchronousChannel;
            if (channel == null || !channel.isConnected()) {
                var asynchronousProcessor = AsynchronousProcessor.getInstance();
                if (channel != null) {
                    asynchronousProcessor.unregisterAsynchronousChannel(channel);
                }
                channel = asynchronousChannel = initAsynchronousChannel();
                asynchronousProcessor.registerAsynchronousChannel(channel);
            }
            channel.queueEvent(eventHandle);
        } catch (SQLException e) {
            exceptionListenerDispatcher.errorOccurred(e);
            throw e;
        }
    }

    @Override
    public final void cancelEvent(EventHandle eventHandle) throws SQLException {
        try (LockCloseable ignored = withLock()) {
            checkAttached();
            FbWireAsynchronousChannel channel = asynchronousChannel;
            if (channel == null || !channel.isConnected()) {
                if (channel != null) {
                    AsynchronousProcessor.getInstance().unregisterAsynchronousChannel(channel);
                }
                throw FbExceptionBuilder.toNonTransientException(jb_unableToCancelEventReasonNotConnected);
            }
            channel.cancelEvent(eventHandle);
        } catch (SQLException e) {
            exceptionListenerDispatcher.errorOccurred(e);
            throw e;
        }
    }

    @Override
    public final void countEvents(EventHandle eventHandle) throws SQLException {
        try {
            if (!(eventHandle instanceof WireEventHandle))
                throw new SQLException("Invalid event handle, type: " + eventHandle.getClass().getName());

            ((WireEventHandle) eventHandle).calculateCount();
        } catch (SQLException e) {
            exceptionListenerDispatcher.errorOccurred(e);
            throw e;
        }
    }

    @Override
    public final byte[] getDatabaseInfo(byte[] requestItems, int maxBufferLength) throws SQLException {
        try {
            checkAttached();
            return getInfo(op_info_database, 0, requestItems, maxBufferLength, null);
        } catch (SQLException e) {
            exceptionListenerDispatcher.errorOccurred(e);
            throw e;
        }
    }

    @Override
    @SuppressWarnings("java:S4274")
    public byte[] getInfo(int operation, int handle, byte[] requestItems, int maxBufferLength,
            WarningMessageCallback warningMessageCallback) throws SQLException {
        assert operation == op_info_sql || operation == op_info_blob || operation == op_info_database
                || operation == op_info_transaction || operation == op_info_request
                || operation == op_info_cursor && protocolDescriptor.getVersion() >= PROTOCOL_VERSION18
                : "Unsupported operation code for info request " + operation;
        try (LockCloseable ignored = withLock()) {
            try {
                final XdrOutputStream xdrOut = getXdrOut();
                xdrOut.writeInt(operation); // p_operation
                xdrOut.writeInt(handle); // p_info_object
                xdrOut.writeInt(0); // p_info_incarnation
                xdrOut.writeBuffer(requestItems); // p_info_items
                xdrOut.writeInt(maxBufferLength); // p_info_buffer_length
                xdrOut.flush();
            } catch (IOException e) {
                throw FbExceptionBuilder.ioWriteError(e);
            }
            try {
                final GenericResponse genericResponse = readGenericResponse(null);
                return genericResponse.data();
            } catch (IOException e) {
                throw FbExceptionBuilder.ioReadError(e);
            }
        }
    }

    /**
     * Initializes the asynchronous channel (for event notification).
     *
     * @throws java.sql.SQLException
     *         For errors establishing the channel, or if the channel already exists.
     */
    public abstract FbWireAsynchronousChannel initAsynchronousChannel() throws SQLException;

}
