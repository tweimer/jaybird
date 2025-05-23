// SPDX-FileCopyrightText: Copyright 2011-2023 Mark Rotteveel
// SPDX-License-Identifier: LGPL-2.1-or-later
package org.firebirdsql.ds;

import org.firebirdsql.jaybird.props.internal.TransactionNameMapping;
import org.junit.jupiter.api.Test;

import javax.naming.Reference;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DataSourceFactory} and - indirectly - the correctness of the getReference() method of
 * {@link FBConnectionPoolDataSource}, {@link FBXADataSource}, and {@link FBSimpleDataSource}.
 * 
 * @author Mark Rotteveel
 * @since 2.2
 */
class DataSourceFactoryTest {
    
    private static final String ROLE_NAME = "someRoleName";
    private static final int LOGIN_TIMEOUT = 513;
    private static final String ENCODING = "WIN1252";
    private static final String PASSWORD = "somePassword";
    private static final String USER = "someUser";
    private static final String DATABASE_NAME = "/some/path/to/database.fdb";
    private static final int PORT_NUMBER = 33050;
    private static final String SERVER_NAME = "someServer.local";
    private static final String TYPE = "PURE_JAVA";
    private static final String DESCRIPTION = "Description of originalDS";

    /**
     * Fills the properties (exposed with JavaBeans setters) of FBAbstractCommonDataSource for testing. Does not set
     * nonStandardProperty and encoding (as it is set through charSet).
     * 
     * @param instance Instance to configure
     */
    private void fillFBAbstractCommonDataSourceProperties(FBAbstractCommonDataSource instance) throws SQLException {
        instance.setDescription(DESCRIPTION);
        instance.setType(TYPE);
        instance.setServerName(SERVER_NAME);
        instance.setPortNumber(PORT_NUMBER);
        instance.setDatabaseName(DATABASE_NAME);
        instance.setUser(USER);
        instance.setPassword(PASSWORD);
        instance.setEncoding(ENCODING);
        instance.setLoginTimeout(LOGIN_TIMEOUT);
        instance.setRoleName(ROLE_NAME);
    }
    
    /**
     * Validates if the instance of FBAbstractCommonDataSource has the values as set by
     * {@link DataSourceFactoryTest#fillFBAbstractCommonDataSourceProperties(FBAbstractCommonDataSource)}.
     * 
     * @param instance Instance to validate
     */
    private void assertFBAbstractCommonDataSourceProperties(FBAbstractCommonDataSource instance) throws SQLException{
        assertEquals(DESCRIPTION, instance.getDescription());
        assertEquals(TYPE, instance.getType());
        assertEquals(SERVER_NAME, instance.getServerName());
        assertEquals(PORT_NUMBER, instance.getPortNumber());
        assertEquals(DATABASE_NAME, instance.getDatabaseName());
        assertEquals(USER, instance.getUser());
        assertEquals(PASSWORD, instance.getPassword());
        assertEquals(ENCODING, instance.getEncoding());
        assertEquals(LOGIN_TIMEOUT, instance.getLoginTimeout());
        assertEquals(ROLE_NAME, instance.getRoleName());
    }
    
    /**
     * Tests reconstruction of a {@link FBConnectionPoolDataSource} using a reference.
     * <p>
     * This test is done with the basic properties exposed through setters. It tests
     * <ol>
     * <li>If the reference returned has the right factory name</li>
     * <li>If the reference returned has the right classname</li>
     * <li>If the object returned by the factory is a distinct new instance</li>
     * <li>If all the properties set on the original are also set on the new instance</li>
     * </ol>
     * </p>
     */
    @Test
    void testBuildFBConnectionPoolDataSource_basicProperties() throws Exception {
        final FBConnectionPoolDataSource originalDS = new FBConnectionPoolDataSource();
        
        fillFBAbstractCommonDataSourceProperties(originalDS);
        Reference ref = originalDS.getReference();
        
        assertEquals(DataSourceFactory.class.getName(), ref.getFactoryClassName(), "Unexpected factory name");
        assertEquals(FBConnectionPoolDataSource.class.getName(), ref.getClassName(), "Unexpected class name");
        
        FBConnectionPoolDataSource newDS = (FBConnectionPoolDataSource) new DataSourceFactory().getObjectInstance(ref, null, null, null);
        assertNotSame(originalDS, newDS, "Expected distinct new object");
        assertFBAbstractCommonDataSourceProperties(newDS);
    }
    
    /**
     * Tests reconstruction of a {@link FBXADataSource} using a reference.
     * <p>
     * This test is done with the basic properties exposed through setters. It tests
     * <ol>
     * <li>If the reference returned has the right factory name</li>
     * <li>If the reference returned has the right classname</li>
     * <li>If the object returned by the factory is a distinct new instance</li>
     * <li>If all the properties set on the original are also set on the new instance</li>
     * </ol>
     * </p>
     */
    @Test
    void testBuildFBXADataSource_basicProperties() throws Exception {
        final FBXADataSource originalDS = new FBXADataSource();
        
        fillFBAbstractCommonDataSourceProperties(originalDS);
        Reference ref = originalDS.getReference();
        
        assertEquals(DataSourceFactory.class.getName(), ref.getFactoryClassName(), "Unexpected factory name");
        assertEquals(FBXADataSource.class.getName(), ref.getClassName(), "Unexpected class name");
        
        FBXADataSource newDS = (FBXADataSource) new DataSourceFactory().getObjectInstance(ref, null, null, null);
        assertFBAbstractCommonDataSourceProperties(newDS);
    }
    
    /**
     * Tests reconstruction of a {@link FBConnectionPoolDataSource} using a reference.
     * <p>
     * This test is done with a selection of properties set through the {@link FBConnectionPoolDataSource#setNonStandardProperty(String)} methods. It tests
     * <ol>
     * <li>If the reference returned has the right factory name</li>
     * <li>If the reference returned has the right classname</li>
     * <li>If the object returned by the factory is a distinct new instance</li>
     * <li>If all the properties set on the original are also set on the new instance</li>
     * <li>If an unset property is handled correctly</li>
     * </ol>
     * </p>
     */
    @Test
    void testBuildFBConnectionPoolDataSource_nonStandardProperties() throws Exception {
        final FBConnectionPoolDataSource originalDS = new FBConnectionPoolDataSource();
        
        originalDS.setNonStandardProperty("buffersNumber=127");
        originalDS.setProperty("defaultTransactionIsolation",
                Integer.toString(Connection.TRANSACTION_SERIALIZABLE));
        originalDS.setProperty("madeUpProperty", "madeUpValue");
        Reference ref = originalDS.getReference();
        
        FBConnectionPoolDataSource newDS = (FBConnectionPoolDataSource)new DataSourceFactory().getObjectInstance(ref, null, null, null);
        assertEquals("127", newDS.getProperty("buffersNumber"));
        assertEquals(TransactionNameMapping.TRANSACTION_SERIALIZABLE, newDS.getProperty("defaultTransactionIsolation"));
        assertEquals(Connection.TRANSACTION_SERIALIZABLE, newDS.getIntProperty("defaultTransactionIsolation", -1));
        assertEquals("madeUpValue", newDS.getProperty("madeUpProperty"));
        assertNull(newDS.getDescription());
    }
    
    /**
     * Tests reconstruction of a {@link FBXADataSource} using a reference.
     * <p>
     * This test is done with a selection of properties set through the {@link FBXADataSource#setNonStandardProperty(String)}
     * and {@link FBXADataSource#setProperty(String, String)} methods. It tests
     * <ol>
     * <li>If the reference returned has the right factory name</li>
     * <li>If the reference returned has the right classname</li>
     * <li>If the object returned by the factory is a distinct new instance</li>
     * <li>If all the properties set on the original are also set on the new instance</li>
     * <li>If an unset property is handled correctly</li> 
     * </ol>
     * </p>
     */
    @Test
    void testBuildFBXADataSource_nonStandardProperties() throws Exception {
        final FBXADataSource originalDS = new FBXADataSource();
        
        originalDS.setNonStandardProperty("buffersNumber=127");
        originalDS.setProperty("defaultTransactionIsolation",
                Integer.toString(Connection.TRANSACTION_SERIALIZABLE));
        originalDS.setProperty("madeUpProperty", "madeUpValue");
        Reference ref = originalDS.getReference();
        
        FBXADataSource newDS = (FBXADataSource)new DataSourceFactory().getObjectInstance(ref, null, null, null);
        assertEquals("127", newDS.getProperty("buffersNumber"));
        assertEquals(TransactionNameMapping.TRANSACTION_SERIALIZABLE, newDS.getProperty("defaultTransactionIsolation"));
        assertEquals(Connection.TRANSACTION_SERIALIZABLE, newDS.getIntProperty("defaultTransactionIsolation", -1));
        assertEquals("madeUpValue", newDS.getProperty("madeUpProperty"));
        assertNull(newDS.getDescription());
    }

    @Test
    void testBuildFBSimpleDataSource() throws Exception {
        final FBSimpleDataSource originalDS = new FBSimpleDataSource();
        originalDS.setDescription(DESCRIPTION);
        originalDS.setType(TYPE);
        final String database = String.format("//%s:%d/%s", SERVER_NAME, PORT_NUMBER, DATABASE_NAME);
        originalDS.setDatabaseName(database);
        originalDS.setUser(USER);
        originalDS.setPassword(PASSWORD);
        originalDS.setEncoding(ENCODING);
        originalDS.setLoginTimeout(LOGIN_TIMEOUT);
        originalDS.setRoleName(ROLE_NAME);
        originalDS.setNonStandardProperty("buffersNumber=127");
        originalDS.setIntProperty("defaultTransactionIsolation", Connection.TRANSACTION_SERIALIZABLE);
        originalDS.setProperty("madeUpProperty", "madeUpValue");
        Reference ref = originalDS.getReference();

        assertEquals(DataSourceFactory.class.getName(), ref.getFactoryClassName(), "Unexpected factory name");
        assertEquals(FBSimpleDataSource.class.getName(), ref.getClassName(), "Unexpected class name");

        FBSimpleDataSource newDS =
                (FBSimpleDataSource) new DataSourceFactory().getObjectInstance(ref, null, null, null);

        assertEquals(DESCRIPTION, newDS.getDescription());
        assertEquals(TYPE, newDS.getType());
        assertEquals(database, newDS.getDatabaseName());
        assertEquals(USER, newDS.getUser());
        assertEquals(PASSWORD, newDS.getPassword());
        assertEquals(ENCODING, newDS.getEncoding());
        assertEquals(LOGIN_TIMEOUT, newDS.getLoginTimeout());
        assertEquals(ROLE_NAME, newDS.getRoleName());
        assertEquals(Integer.valueOf(127), newDS.getIntProperty("buffersNumber"));
        assertEquals(Integer.valueOf(Connection.TRANSACTION_SERIALIZABLE),
                newDS.getIntProperty("defaultTransactionIsolation"));
        assertEquals("madeUpValue", newDS.getProperty("madeUpProperty"));
    }
}
