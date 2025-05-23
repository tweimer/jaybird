// SPDX-FileCopyrightText: Copyright 2014-2023 Mark Rotteveel
// SPDX-License-Identifier: LGPL-2.1-or-later
package org.firebirdsql.gds.ng.wire.version11;

import org.firebirdsql.gds.DatabaseParameterBuffer;
import org.firebirdsql.gds.JaybirdSystemProperties;
import org.firebirdsql.gds.ng.wire.WireDatabaseConnection;
import org.firebirdsql.gds.ng.wire.version10.V10ParameterConverter;
import org.firebirdsql.jaybird.fb.constants.DpbItems;

import java.sql.SQLException;

/**
 * Implementation of {@link org.firebirdsql.gds.ng.ParameterConverter} for the version 11 protocol.
 * <p>
 * Adds support for including the process name and process id from the system properties
 * {@code org.firebirdsql.jdbc.processName} and {@code org.firebirdsql.jdbc.pid}
 * </p>
 *
 * @author Mark Rotteveel
 * @since 3.0
 */
public class V11ParameterConverter extends V10ParameterConverter {

    @Override
    protected void populateDefaultProperties(final WireDatabaseConnection connection,
            final DatabaseParameterBuffer dpb) throws SQLException {
        super.populateDefaultProperties(connection, dpb);

        addProcessName(dpb);
        addProcessId(dpb);
    }

    /**
     * Adds the processName to the dpb, if available.
     *
     * @param dpb
     *         database parameter buffer
     */
    protected final void addProcessName(DatabaseParameterBuffer dpb) {
        if (dpb.hasArgument(DpbItems.isc_dpb_process_name)) return;
        String processName = JaybirdSystemProperties.getProcessName();
        if (processName != null) {
            dpb.addArgument(DpbItems.isc_dpb_process_name, processName);
        }
    }

    /**
     * Adds the processId (pid) to the dpb, if available.
     *
     * @param dpb
     *         database parameter buffer
     */
    protected final void addProcessId(DatabaseParameterBuffer dpb) {
        if (dpb.hasArgument(DpbItems.isc_dpb_process_id)) return;
        Integer pid = JaybirdSystemProperties.getProcessId();
        if (pid != null) {
            dpb.addArgument(DpbItems.isc_dpb_process_id, pid);
        } else {
            try {
                long actualPid = ProcessHandle.current().pid();
                if ((actualPid & 0x7F_FF_FF_FFL) == actualPid) {
                    // Firebird only supports 32-bit process ids, we limit to positive only (31-bit)
                    dpb.addArgument(DpbItems.isc_dpb_process_id, (int) actualPid);
                }
            } catch (SecurityException ignored) {
                // Disallowed by security manager
            }
        }
    }

}
