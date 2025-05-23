// SPDX-FileCopyrightText: Copyright 2001-2024 Firebird development team and individual contributors
// SPDX-FileCopyrightText: Copyright 2022-2024 Mark Rotteveel
// SPDX-License-Identifier: LGPL-2.1-or-later
package org.firebirdsql.jdbc.metadata;

import org.firebirdsql.gds.ng.fields.RowDescriptor;
import org.firebirdsql.gds.ng.fields.RowValue;
import org.firebirdsql.jdbc.DbMetadataMediator;
import org.firebirdsql.jdbc.DbMetadataMediator.MetadataQuery;

import java.sql.ResultSet;
import java.sql.SQLException;

import static org.firebirdsql.gds.ISCConstants.SQL_SHORT;
import static org.firebirdsql.gds.ISCConstants.SQL_VARYING;
import static org.firebirdsql.jaybird.util.StringUtils.isNullOrEmpty;
import static org.firebirdsql.jdbc.metadata.FbMetadataConstants.OBJECT_NAME_LENGTH;

/**
 * Provides the implementation for {@link java.sql.DatabaseMetaData#getPrimaryKeys(String, String, String)}.
 *
 * @author Mark Rotteveel
 * @since 5
 */
public final class GetPrimaryKeys extends AbstractMetadataMethod {

    private static final String COLUMNINFO = "COLUMNINFO";
    
    private static final RowDescriptor ROW_DESCRIPTOR = DbMetadataMediator.newRowDescriptorBuilder(7)
            .at(0).simple(SQL_VARYING | 1, OBJECT_NAME_LENGTH, "TABLE_CAT", COLUMNINFO).addField()
            .at(1).simple(SQL_VARYING | 1, OBJECT_NAME_LENGTH, "TABLE_SCHEM", COLUMNINFO).addField()
            .at(2).simple(SQL_VARYING, OBJECT_NAME_LENGTH, "TABLE_NAME", COLUMNINFO).addField()
            .at(3).simple(SQL_VARYING, OBJECT_NAME_LENGTH, "COLUMN_NAME", COLUMNINFO).addField()
            .at(4).simple(SQL_SHORT, 0, "KEY_SEQ", COLUMNINFO).addField()
            .at(5).simple(SQL_VARYING, OBJECT_NAME_LENGTH, "PK_NAME", COLUMNINFO).addField()
            .at(6).simple(SQL_VARYING, OBJECT_NAME_LENGTH, "JB_PK_INDEX_NAME", COLUMNINFO).addField()
            .toRowDescriptor();

    private static final String GET_PRIMARY_KEYS_START = """
            select
              RC.RDB$RELATION_NAME as TABLE_NAME,
              ISGMT.RDB$FIELD_NAME as COLUMN_NAME,
              ISGMT.RDB$FIELD_POSITION + 1 as KEY_SEQ,
              RC.RDB$CONSTRAINT_NAME as PK_NAME,
              RC.RDB$INDEX_NAME as JB_PK_INDEX_NAME
            from RDB$RELATION_CONSTRAINTS RC
            inner join RDB$INDEX_SEGMENTS ISGMT
              on RC.RDB$INDEX_NAME = ISGMT.RDB$INDEX_NAME
            where RC.RDB$CONSTRAINT_TYPE = 'PRIMARY KEY'
            and\s""";

    private static final String GET_PRIMARY_KEYS_END = "\norder by ISGMT.RDB$FIELD_NAME ";

    private GetPrimaryKeys(DbMetadataMediator mediator) {
        super(ROW_DESCRIPTOR, mediator);
    }

    public ResultSet getPrimaryKeys(String table) throws SQLException {
        if (isNullOrEmpty(table)) {
            return createEmpty();
        }
        Clause tableClause = Clause.equalsClause("RC.RDB$RELATION_NAME", table);
        String sql = GET_PRIMARY_KEYS_START
                + tableClause.getCondition(false)
                + GET_PRIMARY_KEYS_END;
        MetadataQuery metadataQuery = new MetadataQuery(sql, Clause.parameters(tableClause));
        return createMetaDataResultSet(metadataQuery);
    }

    @Override
    RowValue createMetadataRow(ResultSet rs, RowValueBuilder valueBuilder) throws SQLException {
        return valueBuilder
                .at(0).set(null)
                .at(1).set(null)
                .at(2).setString(rs.getString("TABLE_NAME"))
                .at(3).setString(rs.getString("COLUMN_NAME"))
                .at(4).setShort(rs.getShort("KEY_SEQ"))
                .at(5).setString(rs.getString("PK_NAME"))
                .at(6).setString(rs.getString("JB_PK_INDEX_NAME"))
                .toRowValue(false);
    }

    public static GetPrimaryKeys create(DbMetadataMediator mediator) {
        return new GetPrimaryKeys(mediator);
    }
}
