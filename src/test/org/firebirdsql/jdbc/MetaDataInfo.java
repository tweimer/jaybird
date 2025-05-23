// SPDX-FileCopyrightText: Copyright 2012-2024 Mark Rotteveel
// SPDX-License-Identifier: LGPL-2.1-or-later
package org.firebirdsql.jdbc;

import org.hamcrest.Matcher;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;

import static java.lang.String.format;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Interface for the information metadata column objects should be able to provide for validation.
 */
public interface MetaDataInfo {

    /**
     * Marker value to ignore the column during validation
     */
    Object IGNORE_DURING_VALIDATION = new Object();
    /**
     * Marker value to only check if the column is non-null during validation
     */
    Object ANY_NON_NULL_VALUE = new Object();

    /**
     * Position of this metadata column in the resul set
     *
     * @return 1-based position of the column
     */
    int getPosition();

    /**
     * Java class of the expected column type (String => Varchar, Integer => Integer, Short => Smallint)
     */
    Class<?> getColumnClass();

    /**
     * @return name of this metadata column in the result set
     */
    // NOTE: Uses name() to match method defined in java.lang.Enum, as (all) implementations are enum
    String name();

    /**
     * Asserts the expected position of this column in the resultset.
     *
     * @param rs
     *         ResultSet to use for asserting the column position
     */
    default void assertColumnPosition(ResultSet rs) throws SQLException {
        assertEquals(getPosition(), rs.findColumn(name()),
                () -> format("Unexpected column position for %s", name()));
    }

    /**
     * Asserts the type of this column as reported by the ResultSetMetaData of this ResultSet.
     *
     * @param rs
     *         ResultSet
     */
    default void assertColumnType(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int sqlType = md.getColumnType(getPosition());
        assertTrue(isAllowedSqlType(sqlType), () -> format("Unexpected SQL Type %d for column %s", sqlType, name()));
    }

    /**
     * Asserts the value of this column on the current row of the resultset.
     *
     * @param rs
     *         ResultSet
     * @param expectedValue
     *         Value expected
     */
    default void assertColumnValue(ResultSet rs, Object expectedValue) throws SQLException {
        if (getColumnClass().isInstance(expectedValue) || expectedValue == null) {
            if (getColumnClass().equals(String.class)) {
                assertStringColumnValue(rs, (String) expectedValue);
            } else if (getColumnClass().equals(Integer.class)) {
                assertIntegerColumnValue(rs, (Integer) expectedValue);
            } else if (getColumnClass().equals(Short.class)) {
                assertShortColumnValue(rs, (Short) expectedValue);
            } else {
                assertObjectColumnValue(rs, expectedValue);
            }
        } else {
            assertObjectColumnValue(rs, expectedValue);
        }
    }

    @SuppressWarnings("unchecked")
    private void assertObjectColumnValue(ResultSet rs, Object expectedValue) throws SQLException {
        if (expectedValue == IGNORE_DURING_VALIDATION) return;
        Object value = rs.getObject(name());
        if (expectedValue == ANY_NON_NULL_VALUE) {
            assertNotNull(value, "Expected non-null value for " + name() + ", but was null");
        } else if (expectedValue instanceof Matcher<?> matcher) {
            assertThat("Unexpected value for " + name(), value, (Matcher<Object>) matcher);
        } else {
            assertEquals(expectedValue, value, () -> "Unexpected value for " + name());
        }
    }

    private void assertShortColumnValue(ResultSet rs, Short expectedValue) throws SQLException {
        short value = rs.getShort(name());
        if (expectedValue != null) {
            assertEquals(expectedValue.shortValue(), value, () -> "Unexpected value for " + name());
            assertFalse(rs.wasNull(), () -> name() + " should not be actual NULL");
        } else {
            assertEquals(0, value, () -> "Unexpected value for " + name() + " (expected NULL/0)");
            assertTrue(rs.wasNull(), () -> name() + " should be actual NULL");
        }
    }

    private void assertIntegerColumnValue(ResultSet rs, Integer expectedValue) throws SQLException {
        int value = rs.getInt(name());
        if (expectedValue != null) {
            assertEquals(expectedValue.intValue(), value, () -> format("Unexpected value for %s", name()));
            assertFalse(rs.wasNull(), () -> format("%s should not be actual NULL", name()));
        } else {
            assertEquals(0, value, () -> format("Unexpected value for %s (expected NULL/0)", name()));
            assertTrue(rs.wasNull(), () -> format("%s should be actual NULL", name()));
        }
    }

    private void assertStringColumnValue(ResultSet rs, String expectedValue)
            throws SQLException {
        String value = rs.getString(name());
        assertEquals(expectedValue, value, () -> "Unexpected value for " + name());
    }

    private boolean isAllowedSqlType(int sqlType) {
        if (getColumnClass() == String.class) {
            return (sqlType == Types.CHAR || sqlType == Types.VARCHAR || sqlType == Types.LONGVARCHAR);
        } else if (getColumnClass() == Integer.class) {
            return (sqlType == Types.INTEGER);
        } else if (getColumnClass() == Short.class) {
            return (sqlType == Types.SMALLINT);
        }
        return false;
    }

}
