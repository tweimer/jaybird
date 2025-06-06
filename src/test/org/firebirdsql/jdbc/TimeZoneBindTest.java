// SPDX-FileCopyrightText: Copyright 2019-2022 Mark Rotteveel
// SPDX-License-Identifier: LGPL-2.1-or-later
package org.firebirdsql.jdbc;

import org.firebirdsql.common.extension.RequireFeatureExtension;
import org.firebirdsql.common.extension.UsesDatabaseExtension;
import org.firebirdsql.gds.ISCConstants;
import org.firebirdsql.util.FirebirdSupportInfo;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.sql.*;
import java.time.OffsetDateTime;
import java.util.Properties;

import static org.firebirdsql.common.FBTestProperties.*;
import static org.firebirdsql.common.matchers.SQLExceptionMatchers.errorCodeEquals;
import static org.firebirdsql.common.matchers.SQLExceptionMatchers.fbMessageStartsWith;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.AllOf.allOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * See also companion test {@link TimeZoneBindLegacyTest}.
 */
class TimeZoneBindTest {

    @RegisterExtension
    @Order(1)
    static final RequireFeatureExtension requireFeature = RequireFeatureExtension
            .withFeatureCheck(FirebirdSupportInfo::supportsTimeZones, "Test requires time zone support (Firebird 4+)")
            .build();

    @RegisterExtension
    static final UsesDatabaseExtension.UsesDatabaseForAll usesDatabase = UsesDatabaseExtension.usesDatabaseForAll();

    @Test
    void testCurrentTimestamp_noBind() throws Exception {
        Properties props = getDefaultPropertiesForConnection();
        try (Connection connection = DriverManager.getConnection(getUrl(), props);
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("select CURRENT_TIMESTAMP from RDB$DATABASE")) {
            ResultSetMetaData rsmd = rs.getMetaData();
            assertEquals(Types.TIMESTAMP_WITH_TIMEZONE, rsmd.getColumnType(1), "Expected TIMESTAMP (WITHOUT TIME ZONE)");
            assertTrue(rs.next(), "Expected a row");
            assertThat(rs.getObject(1), instanceOf(OffsetDateTime.class));
        }
    }

    @Test
    void testCurrentTimestamp_emptyBind() throws Exception {
        checkForBindValue("");
    }

    @Test
    void testCurrentTimestamp_nativeBind() throws Exception {
        checkForBindValue("timestamp with time zone to native");
    }

    @Test
    void testCurrentTimestamp_NaTIVEBind() throws Exception {
        // check case insensitivity
        checkForBindValue("timestamp with time zone to NaTIVE");
    }

    @Test
    void testCurrentTimestamp_invalidBind() {
        Properties props = getDefaultPropertiesForConnection();
        props.setProperty("dataTypeBind", "timestamp with time zone to doesnotexist");
        SQLException exception = assertThrows(SQLException.class, () -> {
            //noinspection EmptyTryBlock
            try (Connection ignore = DriverManager.getConnection(getUrl(), props)) {
                // ensure connection is closed if this doesn't fail
            }
        });
        assertThat(exception, allOf(
                errorCodeEquals(ISCConstants.isc_bind_err),
                fbMessageStartsWith(ISCConstants.isc_bind_err, "timestamp with time zone to doesnotexist")));
    }

    @Test
    void verifySessionReset_retainsSetting() throws Exception {
        Properties props = getDefaultPropertiesForConnection();
        props.setProperty("dataTypeBind", "timestamp with time zone to legacy");
        try (Connection connection = DriverManager.getConnection(getUrl(), props);
             Statement stmt = connection.createStatement()) {
            stmt.execute("alter session reset");

            verifyLegacyTimestamp(stmt);
        }
    }

    @Test
    void verifySessionReset_throughJDBCUrl_withMultipleProperties() throws Exception {
        String jdbcUrl = getUrl() + "?dataTypeBind=time with time zone to legacy%3Btimestamp with time zone to legacy";
        try (Connection connection = DriverManager.getConnection(jdbcUrl, DB_USER, DB_PASSWORD);
             Statement stmt = connection.createStatement()) {
            verifyLegacyTimestamp(stmt);
        }
    }

    @Test
    void verifySessionReset_afterExplicitChange() throws Exception {
        Properties props = getDefaultPropertiesForConnection();
        props.setProperty("dataTypeBind", "timestamp with time zone to legacy");
        try (Connection connection = DriverManager.getConnection(getUrl(), props);
             Statement stmt = connection.createStatement()) {

            verifyLegacyTimestamp(stmt);

            stmt.execute("set bind of timestamp with time zone to native");

            verifyTimestampWithTimezone(stmt);

            stmt.execute("alter session reset");

            verifyLegacyTimestamp(stmt);
        }
    }

    private void checkForBindValue(String bindValue) throws Exception {
        Properties props = getDefaultPropertiesForConnection();
        props.setProperty("dataTypeBind", bindValue);
        try (Connection connection = DriverManager.getConnection(getUrl(), props)) {
            try (Statement stmt = connection.createStatement()) {
                verifyTimestampWithTimezone(stmt);
            }
        }
    }

    private void verifyLegacyTimestamp(Statement stmt) throws SQLException {
        verifyTimestampType(stmt, JDBCType.TIMESTAMP, Timestamp.class);
    }

    private void verifyTimestampWithTimezone(Statement stmt) throws SQLException {
        verifyTimestampType(stmt, JDBCType.TIMESTAMP_WITH_TIMEZONE, OffsetDateTime.class);
    }

    private void verifyTimestampType(Statement stmt, JDBCType expectedJdbcType, Class<?> expectedType) throws SQLException {
        try (ResultSet rs = stmt.executeQuery("select CURRENT_TIMESTAMP from RDB$DATABASE")) {
            ResultSetMetaData rsmd = rs.getMetaData();
            assertEquals(expectedJdbcType.getVendorTypeNumber().intValue(), rsmd.getColumnType(1),
                    "Expected " + expectedJdbcType);
            assertTrue(rs.next(), "Expected a row");
            assertThat(rs.getObject(1), instanceOf(expectedType));
        }
    }

}
