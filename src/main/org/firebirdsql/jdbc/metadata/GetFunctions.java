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
package org.firebirdsql.jdbc.metadata;

import org.firebirdsql.gds.ng.fields.RowDescriptor;
import org.firebirdsql.gds.ng.fields.RowDescriptorBuilder;
import org.firebirdsql.gds.ng.fields.RowValue;
import org.firebirdsql.jdbc.metadata.DbMetadataMediator.MetadataQuery;
import org.firebirdsql.util.FirebirdSupportInfo;
import org.firebirdsql.util.InternalApi;

import java.sql.ResultSet;
import java.sql.SQLException;

import static java.sql.DatabaseMetaData.functionNoTable;
import static org.firebirdsql.gds.ISCConstants.SQL_SHORT;
import static org.firebirdsql.gds.ISCConstants.SQL_VARYING;
import static org.firebirdsql.jdbc.metadata.FbMetadataConstants.OBJECT_NAME_LENGTH;

/**
 * Provides the implementation for {@link java.sql.DatabaseMetaData#getFunctions(String, String, String)}.
 *
 * @author Mark Rotteveel
 * @since 4.0
 */
@InternalApi
public abstract class GetFunctions extends AbstractMetadataMethod {

    private static final RowDescriptor ROW_DESCRIPTOR =
            new RowDescriptorBuilder(11, DbMetadataMediator.datatypeCoder)
                    .at(0).simple(SQL_VARYING | 1, OBJECT_NAME_LENGTH, "FUNCTION_CAT", "FUNCTIONS").addField()
                    .at(1).simple(SQL_VARYING | 1, OBJECT_NAME_LENGTH, "FUNCTION_SCHEM", "FUNCTIONS").addField()
                    .at(2).simple(SQL_VARYING, OBJECT_NAME_LENGTH, "FUNCTION_NAME", "FUNCTIONS").addField()
                    // Field in Firebird is actually a blob, using Integer.MAX_VALUE for length
                    .at(3).simple(SQL_VARYING | 1, Integer.MAX_VALUE, "REMARKS", "FUNCTIONS").addField()
                    .at(4).simple(SQL_SHORT, 0, "FUNCTION_TYPE", "FUNCTIONS").addField()
                    .at(5).simple(SQL_VARYING, OBJECT_NAME_LENGTH, "SPECIFIC_NAME", "FUNCTIONS").addField()
                    // non-standard extensions
                    .at(6).simple(SQL_VARYING | 1, Integer.MAX_VALUE, "JB_FUNCTION_SOURCE", "FUNCTIONS").addField()
                    .at(7).simple(SQL_VARYING, 4, "JB_FUNCTION_KIND", "FUNCTIONS").addField()
                    .at(8).simple(SQL_VARYING | 1, 255, "JB_MODULE_NAME", "FUNCTIONS").addField()
                    .at(9).simple(SQL_VARYING | 1, 255, "JB_ENTRYPOINT", "FUNCTIONS").addField()
                    .at(10).simple(SQL_VARYING | 1, 255, "JB_ENGINE_NAME", "FUNCTIONS").addField()
                    .toRowDescriptor();

    private GetFunctions(DbMetadataMediator mediator) {
        super(ROW_DESCRIPTOR, mediator);
    }

    /**
     * @see java.sql.DatabaseMetaData#getFunctions(String, String, String)
     */
    public final ResultSet getFunctions(String functionNamePattern) throws SQLException {
        if ("".equals(functionNamePattern)) {
            // Matching function name not possible
            return createEmpty();
        }

        MetadataQuery metadataQuery = createGetFunctionsQuery(functionNamePattern);
        return createMetaDataResultSet(metadataQuery);
    }

    @Override
    final RowValue createMetadataRow(ResultSet rs, RowValueBuilder valueBuilder) throws SQLException {
        return valueBuilder
                .at(0).set(null)
                .at(1).set(null)
                .at(2).setString(rs.getString("FUNCTION_NAME"))
                .at(3).setString(rs.getString("REMARKS"))
                .at(4).setShort(functionNoTable)
                .at(5).setString(rs.getString("FUNCTION_NAME"))
                .at(6).setString(rs.getString("JB_FUNCTION_SOURCE"))
                .at(7).setString(rs.getString("JB_FUNCTION_KIND"))
                .at(8).setString(rs.getString("JB_MODULE_NAME"))
                .at(9).setString(rs.getString("JB_ENTRYPOINT"))
                .at(10).setString(rs.getString("JB_ENGINE_NAME"))
                .toRowValue(false);
    }

    abstract MetadataQuery createGetFunctionsQuery(String functionNamePattern);

    /**
     * Creates an instance of {@code GetFunctions}.
     *
     * @param mediator
     *         Database metadata mediator
     * @return Instance
     */
    public static GetFunctions create(DbMetadataMediator mediator) {
        FirebirdSupportInfo firebirdSupportInfo = mediator.getFirebirdSupportInfo();
        // NOTE: Indirection through static method prevents unnecessary classloading
        if (firebirdSupportInfo.isVersionEqualOrAbove(3, 0)) {
            return FB3.createInstance(mediator);
        } else {
            return FB2_5.createInstance(mediator);
        }
    }

    /**
     * Implementation suitable for Firebird 2.5 and earlier.
     */
    private static final class FB2_5 extends GetFunctions {

        //@formatter:off
        private static final String GET_FUNCTIONS_FRAGMENT_2_5 =
                "select\n"
                + "  RDB$FUNCTION_NAME as FUNCTION_NAME,\n"
                + "  RDB$DESCRIPTION as REMARKS,\n"
                + "  cast(null as blob sub_type text) as JB_FUNCTION_SOURCE,\n"
                + "  'UDF' as JB_FUNCTION_KIND,\n"
                + "  trim(trailing from RDB$MODULE_NAME) as JB_MODULE_NAME,\n"
                + "  trim(trailing from RDB$ENTRYPOINT) as JB_ENTRYPOINT,\n"
                + "  cast(null as varchar(255)) as JB_ENGINE_NAME\n"
                + "from RDB$FUNCTIONS\n";
        //@formatter:on

        private static final String GET_FUNCTIONS_ORDER_BY_2_5 =
                "order by RDB$FUNCTION_NAME";

        private FB2_5(DbMetadataMediator mediator) {
            super(mediator);
        }

        private static GetFunctions createInstance(DbMetadataMediator mediator) {
            return new FB2_5(mediator);
        }

        @Override
        MetadataQuery createGetFunctionsQuery(String functionNamePattern) {
            Clause functionNameClause = new Clause("RDB$FUNCTION_NAME", functionNamePattern);
            String queryText = GET_FUNCTIONS_FRAGMENT_2_5
                    + functionNameClause.getCondition("where ", "\n")
                    + GET_FUNCTIONS_ORDER_BY_2_5;
            return new MetadataQuery(queryText, Clause.parameters(functionNameClause));
        }
    }

    /**
     * Implementation suitable for Firebird 3 and higher; filters out functions in packages.
     */
    private static final class FB3 extends GetFunctions {

        //@formatter:off
        private static final String GET_FUNCTIONS_FRAGMENT_3 =
                "select\n"
                + "  trim(trailing from RDB$FUNCTION_NAME) as FUNCTION_NAME,\n"
                + "  RDB$DESCRIPTION as REMARKS,\n"
                + "  RDB$FUNCTION_SOURCE as JB_FUNCTION_SOURCE,\n"
                + "  case\n"
                + "    when RDB$LEGACY_FLAG = 1 then 'UDF'\n"
                + "    when RDB$ENGINE_NAME is not null then 'UDR'\n"
                + "    else 'PSQL'\n"
                + "  end as JB_FUNCTION_KIND,\n"
                + "  trim(trailing from RDB$MODULE_NAME) as JB_MODULE_NAME,\n"
                + "  trim(trailing from RDB$ENTRYPOINT) as JB_ENTRYPOINT,\n"
                + "  trim(trailing from RDB$ENGINE_NAME) as JB_ENGINE_NAME\n"
                + "from RDB$FUNCTIONS\n"
                + "where RDB$PACKAGE_NAME is null\n";
        //@formatter:on
        
        // NOTE: Including RDB$PACKAGE_NAME so index can be used to sort
        private static final String GET_FUNCTIONS_ORDER_BY_3 =
                "order by RDB$PACKAGE_NAME, RDB$FUNCTION_NAME";

        private FB3(DbMetadataMediator mediator) {
            super(mediator);
        }

        private static GetFunctions createInstance(DbMetadataMediator mediator) {
            return new FB3(mediator);
        }

        @Override
        MetadataQuery createGetFunctionsQuery(String functionNamePattern) {
            Clause functionNameClause = new Clause("RDB$FUNCTION_NAME", functionNamePattern);
            String queryText = GET_FUNCTIONS_FRAGMENT_3
                    + functionNameClause.getCondition("and ", "\n")
                    + GET_FUNCTIONS_ORDER_BY_3;
            return new MetadataQuery(queryText, Clause.parameters(functionNameClause));
        }
    }
}
