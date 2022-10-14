/*
 * Firebird Open Source JDBC Driver
 *
 * Distributable under LGPL license.
 * You may obtain a copy of the License at http://www.gnu.org/copyleft/lgpl.html
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * LGPL License for more details.
 *
 * This file was created by members of the firebird development team.
 * All individual contributions remain the Copyright (C) of those
 * individuals.  Contributors to this file are either listed here or
 * can be obtained from a source control history command.
 *
 * All rights reserved.
 */
package org.firebirdsql.util;

import java.util.function.Supplier;

/**
 * Output formats for messages.
 *
 * @author <a href="mailto:mrotteveel@users.sourceforge.net">Mark Rotteveel</a>
 */
enum OutputFormat {

    /**
     * One file for messages, one file for SQLSTATE.
     */
    SINGLE(SingleFileStore::new),
    /**
     * Per facility a file for messages and a file for SQLSTATE.
     */
    PER_FACILITY(PerFacilityStore::new),
    ;

    private final Supplier<MessageStore> messageStoreSupplier;

    OutputFormat(Supplier<MessageStore> messageStoreSupplier) {
        this.messageStoreSupplier = messageStoreSupplier;
    }

    MessageStore createMessageStore() {
        return messageStoreSupplier.get();
    }
}
