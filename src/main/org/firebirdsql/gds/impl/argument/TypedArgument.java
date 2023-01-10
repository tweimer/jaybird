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
package org.firebirdsql.gds.impl.argument;

/**
 * Argument with an argument type.
 *
 * @author Mark Rotteveel
 * @since 5
 */
public abstract class TypedArgument extends Argument {

    private static final long serialVersionUID = -6422646924006860740L;
    
    final ArgumentType argumentType;

    TypedArgument(int type, ArgumentType argumentType) {
        super(type);
        this.argumentType = argumentType;
    }
}
