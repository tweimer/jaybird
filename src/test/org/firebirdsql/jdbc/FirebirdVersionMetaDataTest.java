// SPDX-FileCopyrightText: Copyright 2017-2022 Mark Rotteveel
// SPDX-License-Identifier: LGPL-2.1-or-later
package org.firebirdsql.jdbc;

import org.firebirdsql.gds.impl.GDSServerVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Mark Rotteveel
 */
class FirebirdVersionMetaDataTest {

    @Test
    void shouldReturn4_0ForFirebird4_0() throws Exception {
        GDSServerVersion version = GDSServerVersion.parseRawVersion("WI-V4.0.0.459 Firebird 4.0");

        assertEquals(FirebirdVersionMetaData.FIREBIRD_4_0, FirebirdVersionMetaData.getVersionMetaDataFor(version));
    }

    @Test
    void shouldReturn3_0ForFirebird3_0() throws Exception {
        GDSServerVersion version = GDSServerVersion.parseRawVersion("WI-V3.0.1.32609 Firebird 3.0");

        assertEquals(FirebirdVersionMetaData.FIREBIRD_3_0, FirebirdVersionMetaData.getVersionMetaDataFor(version));
    }

    @Test
    void shouldReturn2_5ForFirebird2_5() throws Exception {
        GDSServerVersion version = GDSServerVersion.parseRawVersion("WI-V2.5.6.27020 Firebird 2.5");

        assertEquals(FirebirdVersionMetaData.FIREBIRD_2_5, FirebirdVersionMetaData.getVersionMetaDataFor(version));
    }

    @Test
    void shouldReturn2_1ForFirebird2_1() throws Exception {
        GDSServerVersion version = GDSServerVersion.parseRawVersion("WI-V2.1.7.18553 Firebird 2.1");

        assertEquals(FirebirdVersionMetaData.FIREBIRD_2_1, FirebirdVersionMetaData.getVersionMetaDataFor(version));
    }

    @Test
    void shouldReturn2_0ForFirebird2_0() throws Exception {
        GDSServerVersion version = GDSServerVersion.parseRawVersion("WI-V2.0.7.13318 Firebird 2.0");

        assertEquals(FirebirdVersionMetaData.FIREBIRD_2_0, FirebirdVersionMetaData.getVersionMetaDataFor(version));
    }

    @Test
    void shouldReturn2_0ForFirebird1_5() throws Exception {
        GDSServerVersion version = GDSServerVersion.parseRawVersion("WI-V1.5.6.18482 Firebird 1.5");

        assertEquals(FirebirdVersionMetaData.FIREBIRD_2_0, FirebirdVersionMetaData.getVersionMetaDataFor(version));
    }

}
