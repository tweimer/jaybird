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

package org.firebirdsql.jaybird.parser;

import org.firebirdsql.util.InternalApi;

/**
 * Interface for reserved words checks.
 *
 * @author Mark Rotteveel
 * @since 5
 */
@InternalApi
public interface ReservedWords {

    /**
     * Checks case-insensitively if the supplied token text is a reserved word.
     *
     * @param tokenText
     *         Token text to check
     * @return {@code true} if the token text is a reserved word, {@code false} otherwise
     */
    boolean isReservedWord(CharSequence tokenText);

}
