// SPDX-FileCopyrightText: Copyright 2015-2025 Mark Rotteveel
// SPDX-License-Identifier: LGPL-2.1-or-later
package org.firebirdsql.gds.ng.jna;

import com.sun.jna.ptr.IntByReference;
import org.firebirdsql.gds.ISCConstants;
import org.firebirdsql.gds.JaybirdErrorCodes;
import org.firebirdsql.gds.ServiceParameterBuffer;
import org.firebirdsql.gds.ServiceRequestBuffer;
import org.firebirdsql.gds.impl.ServiceParameterBufferImp;
import org.firebirdsql.gds.impl.ServiceRequestBufferImp;
import org.firebirdsql.gds.ng.AbstractFbService;
import org.firebirdsql.gds.ng.FbExceptionBuilder;
import org.firebirdsql.gds.ng.LockCloseable;
import org.firebirdsql.gds.ng.ParameterConverter;
import org.firebirdsql.gds.ng.WarningMessageCallback;
import org.firebirdsql.jaybird.util.Cleaners;
import org.firebirdsql.jdbc.FBDriverNotCapableException;
import org.firebirdsql.jna.fbclient.FbClientLibrary;
import org.firebirdsql.jna.fbclient.ISC_STATUS;

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.sql.SQLException;

/**
 * Implementation of {@link org.firebirdsql.gds.ng.FbService} for native client access.
 *
 * @author Mark Rotteveel
 * @since 3.0
 */
public final class JnaService extends AbstractFbService<JnaServiceConnection> implements JnaAttachment {

    private static final ParameterConverter<?, JnaServiceConnection> PARAMETER_CONVERTER = new JnaParameterConverter();
    public static final int STATUS_VECTOR_SIZE = 20;

    private final FbClientLibrary clientLibrary;
    private final IntByReference handle = new IntByReference(0);
    private final ISC_STATUS[] statusVector = new ISC_STATUS[STATUS_VECTOR_SIZE];
    private Cleaner.Cleanable cleanable = Cleaners.getNoOp();

    public JnaService(JnaServiceConnection connection) {
        super(connection, connection.createDatatypeCoder());
        clientLibrary = connection.getClientLibrary();
    }

    private void setDetachedJna() {
        try {
            cleanable.clean();
        } finally {
            setDetached();
        }
    }

    @Override
    public ServiceParameterBuffer createServiceParameterBuffer() {
        // TODO When Firebird 3, use UTF-8; implement similar mechanism as ProtocolDescriptor of wire?
        return new ServiceParameterBufferImp(ServiceParameterBufferImp.SpbMetaData.SPB_VERSION_2, getEncoding());
    }

    @Override
    public ServiceRequestBuffer createServiceRequestBuffer() {
        // TODO When Firebird 3, use UTF-8; implement similar mechanism as ProtocolDescriptor of wire?
        return new ServiceRequestBufferImp(ServiceRequestBufferImp.SrbMetaData.SRB_VERSION_2, getEncoding());
    }

    @Override
    protected void checkConnected() throws SQLException {
        if (!isAttached()) {
            throw FbExceptionBuilder.toException(JaybirdErrorCodes.jb_notAttachedToDatabase);
        }
    }

    @Override
    protected boolean isConnected() {
        return isAttached();
    }

    @Override
    public byte[] getServiceInfo(ServiceParameterBuffer serviceParameterBuffer,
            ServiceRequestBuffer serviceRequestBuffer, int maxBufferLength) throws SQLException {
        try {
            final byte[] serviceParameterBufferBytes = serviceParameterBuffer == null ? null
                    : serviceParameterBuffer.toBytes();
            final byte[] serviceRequestBufferBytes =
                    serviceRequestBuffer == null ? null : serviceRequestBuffer.toBytes();
            final ByteBuffer responseBuffer = ByteBuffer.allocateDirect(maxBufferLength);
            try (LockCloseable ignored = withLock()) {
                clientLibrary.isc_service_query(statusVector, handle, new IntByReference(0),
                        (short) (serviceParameterBufferBytes != null ? serviceParameterBufferBytes.length
                                : 0), serviceParameterBufferBytes,
                        (short) (serviceRequestBufferBytes != null ? serviceRequestBufferBytes.length
                                : 0), serviceRequestBufferBytes,
                        (short) maxBufferLength, responseBuffer);
                processStatusVector();
            }
            byte[] responseArray = new byte[maxBufferLength];
            responseBuffer.get(responseArray);
            return responseArray;
        } catch (SQLException e) {
            exceptionListenerDispatcher.errorOccurred(e);
            throw e;
        }
    }

    @Override
    public void startServiceAction(ServiceRequestBuffer serviceRequestBuffer) throws SQLException {
        byte[] serviceRequestBufferBytes = serviceRequestBuffer == null ? null : serviceRequestBuffer.toBytes();
        try (LockCloseable ignored = withLock()) {
            clientLibrary.isc_service_start(statusVector, handle, new IntByReference(0),
                    (short) (serviceRequestBufferBytes != null ? serviceRequestBufferBytes.length : 0),
                    serviceRequestBufferBytes);
            processStatusVector();
        } catch (SQLException e) {
            exceptionListenerDispatcher.errorOccurred(e);
            throw e;
        }
    }

    @Override
    public void attach() throws SQLException {
        try {
            requireNotAttached();
            try (var ignored = withLock()) {
                attachImpl();
                setAttached();
                afterAttachActions();
            }
        } catch (SQLException e) {
            exceptionListenerDispatcher.errorOccurred(e);
            throw e;
        }
    }

    private void attachImpl() throws SQLException {
        try {
            byte[] spbArray = PARAMETER_CONVERTER.toServiceParameterBuffer(connection).toBytesWithType();
            byte[] serviceName = getEncoding().encodeToCharset(connection.getAttachUrl());
            clientLibrary.isc_service_attach(statusVector, (short) serviceName.length, serviceName, handle,
                    (short) spbArray.length, spbArray);
            if (handle.getValue() != 0) {
                cleanable = Cleaners.getJbCleaner().register(this, new CleanupAction(handle, clientLibrary));
            }
            processStatusVector();
        } catch (SQLException ex) {
            safelyDetach();
            throw ex;
        } catch (Exception ex) {
            safelyDetach();
            // TODO Replace with specific error (eg native client error)
            throw FbExceptionBuilder.forException(ISCConstants.isc_network_error)
                    .messageParameter(connection.getAttachUrl())
                    .cause(ex)
                    .toSQLException();
        }
    }

    /**
     * Additional tasks to execute directly after attach operation.
     * <p>
     * Implementation retrieves service information like server version.
     * </p>
     *
     * @throws SQLException
     *         For errors reading or writing database information.
     */
    private void afterAttachActions() throws SQLException {
        getServiceInfo(null, getDescribeServiceRequestBuffer(), 1024, getServiceInformationProcessor());
    }

    @Override
    protected void internalDetach() throws SQLException {
        try (LockCloseable ignored = withLock()) {
            try {
                clientLibrary.isc_service_detach(statusVector, handle);
                processStatusVector();
            } finally {
                setDetachedJna();
            }
        } catch (SQLException ex) {
            throw ex;
        } catch (Exception ex) {
            // TODO Replace with specific error (eg native client error)
            throw FbExceptionBuilder.forException(ISCConstants.isc_network_error)
                    .messageParameter(connection.getAttachUrl())
                    .cause(ex)
                    .toSQLException();
        }
    }

    @Override
    public int getHandle() {
        return handle.getValue();
    }

    public IntByReference getJnaHandle() {
        return handle;
    }

    @Override
    public void setNetworkTimeout(int milliseconds) throws SQLException {
        throw new FBDriverNotCapableException(
                "Setting network timeout not supported in native implementation");
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        throw new FBDriverNotCapableException(
                "Getting network timeout not supported in native implementation");
    }

    private void processStatusVector() throws SQLException {
        processStatusVector(statusVector, getServiceWarningCallback());
    }

    public void processStatusVector(ISC_STATUS[] statusVector, WarningMessageCallback warningMessageCallback)
            throws SQLException {
        if (warningMessageCallback == null) {
            warningMessageCallback = getServiceWarningCallback();
        }
        connection.processStatusVector(statusVector, warningMessageCallback);
    }

    private record CleanupAction(IntByReference handle, FbClientLibrary library) implements Runnable {
        @Override
        public void run() {
            if (handle.getValue() == 0) return;
            try {
                library.isc_service_detach(new ISC_STATUS[STATUS_VECTOR_SIZE], handle);
            } finally {
                handle.setValue(0);
            }
        }
    }

}
