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
package org.firebirdsql.gds.ng;

/**
 * Cursor flags.
 *
 * @author Mark Rotteveel
 * @since 5
 */
public enum CursorFlag {

    CURSOR_TYPE_SCROLLABLE(0x1),
    ;

    private final int flagValue;

    CursorFlag(int flagValue) {
        this.flagValue = flagValue;
    }

    public int flagValue() {
        return flagValue;
    }
}
