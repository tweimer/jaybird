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
package org.firebirdsql.gds.ng.wire;

import org.firebirdsql.gds.impl.wire.WireProtocolConstants;
import org.firebirdsql.gds.ng.EmptyProtocolDescriptor;
import org.firebirdsql.gds.ng.wire.version10.Version10Descriptor;
import org.firebirdsql.gds.ng.wire.version11.Version11Descriptor;
import org.firebirdsql.gds.ng.wire.version12.Version12Descriptor;
import org.firebirdsql.gds.ng.wire.version13.Version13Descriptor;
import org.firebirdsql.gds.ng.wire.version15.Version15Descriptor;
import org.firebirdsql.gds.ng.wire.version16.Version16Descriptor;
import org.firebirdsql.gds.ng.wire.version18.Version18Descriptor;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author <a href="mailto:mrotteveel@users.sourceforge.net">Mark Rotteveel</a>
 * @since 3.0
 */
class ProtocolCollectionTest {

    /**
     * Tests if the default collection contains the expected classes.
     * <p>
     * As the default collection depends on the content of the classpath, this
     * test is not a statement that these classes are the ones loaded in actual
     * usage.
     * </p>
     */
    @Test
    void testGetDefaultCollection() {
        assertProtocolCollection(ProtocolCollection.getDefaultCollection(),
                Arrays.asList(
                        Version10Descriptor.class,
                        Version11Descriptor.class,
                        Version12Descriptor.class,
                        Version13Descriptor.class,
                        Version15Descriptor.class,
                        Version16Descriptor.class,
                        Version18Descriptor.class));
    }

    /**
     * Tests if {@link ProtocolCollection#create(ProtocolDescriptor...)} returns
     * a collection with the supplied descriptors.
     */
    @Test
    void testCreate() {
        // Version 10 with weight 3, type 1
        ProtocolDescriptor alternativeDescriptorV10Weight3_1 = new EmptyProtocolDescriptor(
                WireProtocolConstants.PROTOCOL_VERSION10, WireProtocolConstants.arch_generic,
                WireProtocolConstants.ptype_rpc, WireProtocolConstants.ptype_batch_send, 3);
        // Version 10 with weight 2
        ProtocolDescriptor alternativeDescriptorV10Weight2 = new EmptyProtocolDescriptor(
                WireProtocolConstants.PROTOCOL_VERSION10, WireProtocolConstants.arch_generic,
                WireProtocolConstants.ptype_rpc, WireProtocolConstants.ptype_batch_send, 2);
        // Version 10 with weight 3, type 2
        ProtocolDescriptor alternativeDescriptorV10Weight3_2 = new EmptyProtocolDescriptor(
                WireProtocolConstants.PROTOCOL_VERSION10, WireProtocolConstants.arch_generic,
                WireProtocolConstants.ptype_rpc, WireProtocolConstants.ptype_batch_send, 3);

        ProtocolCollection collection = ProtocolCollection.create(new Version10Descriptor(),
                alternativeDescriptorV10Weight3_1, alternativeDescriptorV10Weight2, alternativeDescriptorV10Weight3_2);

        // We expect the descriptor 'Version 10 with weight 3, type 1' to be returned
        assertProtocolCollection(collection, singletonList(alternativeDescriptorV10Weight3_1.getClass()));
    }

    /**
     * Tests {@link ProtocolCollection#create(ProtocolDescriptor...)} to see if
     * it selects the descriptor with the highest weight when given two
     * ProtocolDescriptors with the same version.
     */
    @Test
    void testCreateSameVersion() {
        ProtocolDescriptor alternativeDescriptor = new EmptyProtocolDescriptor(
                WireProtocolConstants.PROTOCOL_VERSION10, WireProtocolConstants.arch_generic,
                WireProtocolConstants.ptype_rpc, WireProtocolConstants.ptype_batch_send, 2);
        ProtocolCollection collection = ProtocolCollection.create(new Version10Descriptor(), alternativeDescriptor);

        assertProtocolCollection(collection, singletonList(alternativeDescriptor.getClass()));
    }

    /**
     * Tests if {@link ProtocolCollection#getProtocolDescriptor(int)} correctly
     * retrieves the descriptor based on its version.
     */
    @Test
    void testGetProtocolDescriptor_existingVersion() {
        ProtocolDescriptor descriptor = new Version10Descriptor();
        ProtocolCollection collection = ProtocolCollection.create(descriptor);

        assertSame(descriptor, collection.getProtocolDescriptor(descriptor.getVersion()),
                "Unexpected ProtocolDescriptor returned");
    }

    /**
     * Tests if {@link ProtocolCollection#getProtocolDescriptor(int)} correctly
     * returns null when a version is requested that is not contained in the
     * collection.
     */
    @Test
    void testGetProtocolDescriptor_nonExistentVersion() {
        ProtocolDescriptor descriptor = new Version10Descriptor();
        ProtocolCollection collection = ProtocolCollection.create(descriptor);

        assertNull(collection.getProtocolDescriptor(-1), "Unexpected ProtocolDescriptor returned");
    }

    private void assertProtocolCollection(ProtocolCollection collection,
                                          List<Class<? extends ProtocolDescriptor>> expected) {
        Set<Class<? extends ProtocolDescriptor>> expectedProtocols = new HashSet<>(expected);
        Set<Class<? extends ProtocolDescriptor>> unexpectedProtocols = new HashSet<>();

        for (ProtocolDescriptor descriptor : collection) {
            Class<? extends ProtocolDescriptor> descriptorClass = descriptor.getClass();
            if (!expectedProtocols.remove(descriptorClass)) {
                unexpectedProtocols.add(descriptorClass);
            }
        }

        assertTrue(expectedProtocols.isEmpty(),
                () -> "One or more expected descriptors not found: " + expectedProtocols);
        assertTrue(unexpectedProtocols.isEmpty(),
                () -> "One or more unexpected descriptors found: " + unexpectedProtocols);
    }
}