// SPDX-FileCopyrightText: Copyright 2019-2023 Mark Rotteveel
// SPDX-License-Identifier: LGPL-2.1-or-later
package org.firebirdsql.gds.ng;

import org.firebirdsql.common.extension.UsesDatabaseExtension;
import org.firebirdsql.gds.ISCConstants;
import org.firebirdsql.gds.ng.TestOperationAware.OperationReport;
import org.firebirdsql.gds.ng.monitor.Operation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.firebirdsql.common.FBTestProperties.getConnectionViaDriverManager;
import static org.firebirdsql.common.matchers.SQLExceptionMatchers.errorCodeEquals;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.*;

class OperationMonitorTest {

    @RegisterExtension
    final UsesDatabaseExtension.UsesDatabaseForEach usesDatabase = UsesDatabaseExtension.noDatabase();

    private final TestOperationAware testOperationAware = new TestOperationAware();

    @BeforeEach
    void initOperationMonitor() {
        OperationMonitor.initOperationAware(testOperationAware);
    }

    @AfterEach
    void clearOperationMonitor() {
        OperationMonitor.initOperationAware(null);
    }

    @AfterAll
    static void clearOperationMonitorAgain() {
        // paranoia: extra clear of OperationMonitor
        OperationMonitor.initOperationAware(null);
    }

    @Test
    void startOperationNotifiesOnRegisteredInstance() {
        Operation operation = new DummyOperation();
        OperationMonitor.startOperation(operation);

        List<OperationReport> reportedOperations = testOperationAware.getReportedOperations();
        assertEquals(1, reportedOperations.size(), "Unexpected number of operations");
        OperationReport operationReport = reportedOperations.get(0);
        assertEquals(OperationReport.Type.START, operationReport.getType(), "Unexpected report type");
        assertEquals(operation, operationReport.getOperation(), "Unexpected operation");
    }

    @Test
    void endOperationNotifiesOnRegisteredInstance() {
        Operation operation = new DummyOperation();
        OperationMonitor.endOperation(operation);

        List<OperationReport> reportedOperations = testOperationAware.getReportedOperations();
        assertEquals(1, reportedOperations.size(), "Unexpected number of operations");
        OperationReport operationReport = reportedOperations.get(0);
        assertEquals(OperationReport.Type.END, operationReport.getType(), "Unexpected report type");
        assertEquals(operation, operationReport.getOperation(), "Unexpected operation");
    }

    @Test
    void notificationDuringSimpleSelect() throws Exception {
        usesDatabase.createDefaultDatabase();
        try (Connection connection = getConnectionViaDriverManager();
             Statement stmt = connection.createStatement()) {
            connection.setAutoCommit(false);
            List<OperationReport> reportedOperations = testOperationAware.getReportedOperations();
            assertEquals(0, reportedOperations.size());
            try (ResultSet rs = stmt.executeQuery("select 1 from RDB$DATABASE")) {
                assertEquals(2, reportedOperations.size());
                assertOperationReport(reportedOperations.get(0), OperationReport.Type.START,
                        Operation.Type.STATEMENT_EXECUTE);
                assertOperationReport(reportedOperations.get(1), OperationReport.Type.END,
                        Operation.Type.STATEMENT_EXECUTE);

                assertTrue(rs.next(), "Expected a row");
                // Native implementation does additional fetch during next()
                assertThat(reportedOperations, hasSize(greaterThanOrEqualTo(4)));
                assertOperationReport(reportedOperations.get(2), OperationReport.Type.START,
                        Operation.Type.STATEMENT_FETCH);
                assertOperationReport(reportedOperations.get(3), OperationReport.Type.END,
                        Operation.Type.STATEMENT_FETCH);
            }
        }
    }

    // Technically this test should probably belong in a FbStatementTest instead
    @Test
    void synchronousCancellationDuringExecute() throws Exception {
        TestOperationAware syncCancelOperationAware = new TestOperationAware() {
            @Override
            public void startOperation(Operation operation) {
                super.startOperation(operation);
                if (operation.getType() == Operation.Type.STATEMENT_EXECUTE) {
                    try {
                        operation.cancel();
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                }
            }
        };
        OperationMonitor.initOperationAware(syncCancelOperationAware);
        usesDatabase.createDefaultDatabase();
        try (Connection connection = getConnectionViaDriverManager();
             Statement stmt = connection.createStatement()) {
            connection.setAutoCommit(false);
            List<OperationReport> reportedOperations = syncCancelOperationAware.getReportedOperations();
            assertEquals(0, reportedOperations.size());
            SQLException exception = assertThrows(SQLException.class,
                    () -> stmt.executeQuery("select 1 from RDB$DATABASE"));
            assertThat(exception, errorCodeEquals(ISCConstants.isc_cancelled));
            assertEquals(2, reportedOperations.size());
            assertOperationReport(reportedOperations.get(0), OperationReport.Type.START,
                    Operation.Type.STATEMENT_EXECUTE);
            assertOperationReport(reportedOperations.get(1), OperationReport.Type.END,
                    Operation.Type.STATEMENT_EXECUTE);
        }
    }

    // Technically this test should probably belong in a FbStatementTest instead
    @Test
    void synchronousCancellationDuringFetch() throws Exception {
        TestOperationAware syncCancelOperationAware = new TestOperationAware() {
            @Override
            public void startOperation(Operation operation) {
                super.startOperation(operation);
                if (operation.getType() == Operation.Type.STATEMENT_FETCH) {
                    try {
                        operation.cancel();
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                }
            }
        };
        OperationMonitor.initOperationAware(syncCancelOperationAware);
        usesDatabase.createDefaultDatabase();
        try (Connection connection = getConnectionViaDriverManager();
             Statement stmt = connection.createStatement()) {
            connection.setAutoCommit(false);
            List<OperationReport> reportedOperations = syncCancelOperationAware.getReportedOperations();
            assertEquals(0, reportedOperations.size());
            try (ResultSet rs = stmt.executeQuery("select 1 from RDB$DATABASE")) {
                assertEquals(2, reportedOperations.size());
                assertOperationReport(reportedOperations.get(0), OperationReport.Type.START,
                        Operation.Type.STATEMENT_EXECUTE);
                assertOperationReport(reportedOperations.get(1), OperationReport.Type.END,
                        Operation.Type.STATEMENT_EXECUTE);

                SQLException exception = assertThrows(SQLException.class, rs::next);
                assertThat(exception, errorCodeEquals(ISCConstants.isc_cancelled));
            }
            assertEquals(4, reportedOperations.size());
            assertOperationReport(reportedOperations.get(2), OperationReport.Type.START,
                    Operation.Type.STATEMENT_FETCH);
            assertOperationReport(reportedOperations.get(3), OperationReport.Type.END,
                    Operation.Type.STATEMENT_FETCH);
        }
    }

    static void assertOperationReport(OperationReport operationReport, OperationReport.Type reportType,
            Operation.Type operationType) {
        assertEquals(reportType, operationReport.getType(), "operationReport.type");
        assertEquals(operationType, operationReport.getOperation().getType(), "operationReport.operation.type");
    }

    private static class DummyOperation implements Operation {

        @Override
        public Type getType() {
            return null;
        }

        @Override
        public void cancel() {
            // does nothing
        }
    }
}
