// SPDX-FileCopyrightText: Copyright 2019-2022 Mark Rotteveel
// SPDX-License-Identifier: LGPL-2.1-or-later
package org.firebirdsql.gds.ng.wire.version16;

import org.firebirdsql.common.extension.RequireProtocolExtension;
import org.firebirdsql.gds.ng.wire.version15.V15ServiceTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.firebirdsql.common.extension.RequireProtocolExtension.requireProtocolVersion;

/**
 * Tests for {@link org.firebirdsql.gds.ng.wire.version10.V10Service} in the V16 protocol.
 *
 * @author Mark Rotteveel
 * @since 4.0
 */
public class V16ServiceTest extends V15ServiceTest {

    @RegisterExtension
    @Order(1)
    public static final RequireProtocolExtension requireProtocol = requireProtocolVersion(16);

    protected V16CommonConnectionInfo commonConnectionInfo() {
        return new V16CommonConnectionInfo();
    }

}
