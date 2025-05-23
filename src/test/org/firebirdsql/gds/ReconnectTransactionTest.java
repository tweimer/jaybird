// SPDX-FileCopyrightText: Copyright 2005-2006 Roman Rokytskyy
// SPDX-FileCopyrightText: Copyright 2005 Gabriel Reid
// SPDX-FileCopyrightText: Copyright 2011-2024 Mark Rotteveel
// SPDX-License-Identifier: LGPL-2.1-or-later
package org.firebirdsql.gds;

import org.firebirdsql.common.FBTestProperties;
import org.firebirdsql.common.extension.UsesDatabaseExtension;
import org.firebirdsql.gds.impl.GDSHelper;
import org.firebirdsql.gds.ng.*;
import org.firebirdsql.gds.ng.fields.RowValue;
import org.firebirdsql.gds.ng.listeners.StatementListener;
import org.firebirdsql.jdbc.FBTpbMapper;
import org.firebirdsql.jdbc.field.FBField;
import org.firebirdsql.jdbc.field.FieldDataProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconnectTransactionTest {

    private static final byte[] message = new byte[] {
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08
    };

    private static final String RECOVERY_QUERY = """
            SELECT RDB$TRANSACTION_ID, RDB$TRANSACTION_DESCRIPTION
            FROM RDB$TRANSACTIONS WHERE RDB$TRANSACTION_STATE = 1""";

    @RegisterExtension
    final UsesDatabaseExtension.UsesDatabaseForEach usesDatabase = UsesDatabaseExtension.usesDatabase();

    private final TransactionParameterBuffer tpb = FBTpbMapper.getDefaultMapper().getDefaultMapping();

    private static class DataProvider implements FieldDataProvider {

        private final List<RowValue> rows;
        private final int fieldPos;
        private int row;

        private DataProvider(List<RowValue> rows, int fieldPos) {
            this.rows = rows;
            this.fieldPos = fieldPos;
        }

        public void setRow(int row) {
            this.row = row;
        }

        public byte[] getFieldData() {
            return rows.get(row).getFieldData(fieldPos);
        }

        public void setFieldData(byte[] data) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void testReconnectTransaction() throws Exception {
        FbConnectionProperties connectionInfo = FBTestProperties.getDefaultFbConnectionProperties();

        FbDatabaseFactory databaseFactory = FBTestProperties.getFbDatabaseFactory();
        try (FbDatabase dbHandle1 = databaseFactory.connect(connectionInfo)) {
            dbHandle1.attach();
            FbTransaction trHandle1 = dbHandle1.startTransaction(tpb);
            trHandle1.prepare(message);

            // No commit! We leave trHandle1 in Limbo.
        }

        try (FbDatabase dbHandle2 = databaseFactory.connect(connectionInfo)) {
            dbHandle2.attach();
            GDSHelper gdsHelper2 = new GDSHelper(dbHandle2);
            FbTransaction trHandle2 = dbHandle2.startTransaction(tpb);
            gdsHelper2.setCurrentTransaction(trHandle2);

            FbStatement stmtHandle2 = dbHandle2.createStatement(trHandle2);
            stmtHandle2.prepare(RECOVERY_QUERY);

            final List<RowValue> rows = new ArrayList<>();
            StatementListener stmtListener = new StatementListener() {
                @Override
                public void receivedRow(FbStatement sender, RowValue rowValues) {
                    rows.add(rowValues);
                }
            };
            stmtHandle2.addStatementListener(stmtListener);
            stmtHandle2.execute(RowValue.EMPTY_ROW_VALUE);
            stmtHandle2.fetchRows(10);

            DataProvider dataProvider0 = new DataProvider(rows, 0);
            DataProvider dataProvider1 = new DataProvider(rows, 1);

            FBField field0 = FBField.createField(stmtHandle2.getRowDescriptor().getFieldDescriptor(0), dataProvider0, gdsHelper2, false);
            FBField field1 = FBField.createField(stmtHandle2.getRowDescriptor().getFieldDescriptor(1), dataProvider1, gdsHelper2, false);

            boolean foundInLimboTx = false;
            int row = 0;
            while (row < rows.size()) {
                dataProvider0.setRow(row);
                dataProvider1.setRow(row);

                long inLimboTxId = field0.getLong();
                byte[] inLimboMessage = field1.getBytes();

                if (Arrays.equals(message, inLimboMessage)) {
                    foundInLimboTx = true;

                    FbTransaction inLimboTrHandle = dbHandle2.reconnectTransaction(inLimboTxId);
                    assertEquals(inLimboTxId, inLimboTrHandle.getTransactionId());
                    inLimboTrHandle.rollback();
                    break;
                }
                row++;
            }

            stmtHandle2.close();
            trHandle2.commit();

            assertTrue(foundInLimboTx, "Should find in-limbo tx");
        }

    }
}
