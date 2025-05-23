// SPDX-FileCopyrightText: Copyright 2015-2025 Mark Rotteveel
// SPDX-License-Identifier: LGPL-2.1-or-later
package org.firebirdsql.gds.ng;

import org.firebirdsql.gds.JaybirdErrorCodes;
import org.firebirdsql.gds.ServiceParameterBuffer;
import org.firebirdsql.gds.ServiceRequestBuffer;
import org.firebirdsql.gds.ng.listeners.ServiceListener;
import org.firebirdsql.gds.ng.listeners.ServiceListenerDispatcher;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

import static org.firebirdsql.gds.ISCConstants.*;
import static org.firebirdsql.gds.VaxEncoding.iscVaxInteger2;

/**
 * Abstract service implementation.
 *
 * @author Mark Rotteveel
 * @since 3.0
 */
public abstract class AbstractFbService<T extends AbstractConnection<IServiceProperties, ? extends FbService>>
        extends AbstractFbAttachment<T> implements FbService {

    private static final System.Logger log = System.getLogger(AbstractFbService.class.getName());
    protected final ServiceListenerDispatcher serviceListenerDispatcher = new ServiceListenerDispatcher();
    private final WarningMessageCallback serviceWarningCallback =
            warning -> serviceListenerDispatcher.warningReceived(AbstractFbService.this, warning);

    protected AbstractFbService(T connection, DatatypeCoder datatypeCoder) {
        super(connection, datatypeCoder);
    }

    @Override
    public final <R> R getServiceInfo(ServiceParameterBuffer serviceParameterBuffer,
            ServiceRequestBuffer serviceRequestBuffer, int bufferLength, InfoProcessor<R> infoProcessor)
            throws SQLException {
        final byte[] responseBuffer = getServiceInfo(serviceParameterBuffer, serviceRequestBuffer, bufferLength);
        try {
            return infoProcessor.process(responseBuffer);
        } catch (SQLException e) {
            exceptionListenerDispatcher.errorOccurred(e);
            throw e;
        }
    }

    @Override
    public final void addServiceListener(ServiceListener listener) {
        serviceListenerDispatcher.addListener(listener);
    }

    @Override
    public final void removeServiceListener(ServiceListener listener) {
        serviceListenerDispatcher.removeListener(listener);
    }

    /**
     * Actual implementation of service detach.
     * <p>
     * Implementations of this method should only be called from {@link #close()}, and should <strong>not</strong>
     * notify service listeners of the service {@link ServiceListener#detaching(FbService)} and
     * {@link ServiceListener#detached(FbService)} events.
     * </p>
     */
    protected abstract void internalDetach() throws SQLException;

    /**
     * {@inheritDoc}
     * <p>
     * Implementation note: Calls {@link #checkConnected()} and notifies service listeners of the detaching event, then
     * calls {@link #internalDetach()} and finally notifies service listeners of database detach and removes all
     * listeners.
     * </p>
     */
    @Override
    public final void close() throws SQLException {
        try (LockCloseable ignored = withLock()) {
            if (!isConnected()) return;
            serviceListenerDispatcher.detaching(this);
            try {
                internalDetach();
            } finally {
                serviceListenerDispatcher.detached(this);
                serviceListenerDispatcher.shutdown();
            }
        } catch (SQLException e) {
            exceptionListenerDispatcher.errorOccurred(e);
            throw e;
        } finally {
            exceptionListenerDispatcher.shutdown();
        }
    }

    /**
     * @return The warning callback for this service.
     */
    protected final WarningMessageCallback getServiceWarningCallback() {
        return serviceWarningCallback;
    }

    protected ServiceRequestBuffer getDescribeServiceRequestBuffer() {
        ServiceRequestBuffer srb = createServiceRequestBuffer();
        srb.addArgument(isc_info_svc_server_version);
        return srb;
    }

    protected InfoProcessor<FbService> getServiceInformationProcessor() {
        return new ServiceInformationProcessor();
    }

    private class ServiceInformationProcessor implements InfoProcessor<FbService> {
        @Override
        public FbService process(byte[] info) throws SQLException {
            if (info.length == 0) {
                throw FbExceptionBuilder.forException(JaybirdErrorCodes.jb_infoResponseEmpty)
                        .messageParameter("service")
                        .toSQLException();
            }
            int len;
            int i = 0;
            while (info[i] != isc_info_end) {
                switch (info[i++]) {
                case isc_info_svc_server_version -> {
                    len = iscVaxInteger2(info, i);
                    i += 2;
                    String firebirdVersion = new String(info, i, len, StandardCharsets.UTF_8);
                    i += len;
                    setServerVersion(firebirdVersion);
                }
                case isc_info_truncated -> {
                    log.log(System.Logger.Level.DEBUG, "Received isc_info_truncated");
                    return AbstractFbService.this;
                }
                default -> throw FbExceptionBuilder.toException(isc_infunk);
                }
            }
            return AbstractFbService.this;
        }
    }
}
