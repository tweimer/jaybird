// SPDX-FileCopyrightText: Copyright 2020-2023 Mark Rotteveel
// SPDX-License-Identifier: LGPL-2.1-or-later
package org.firebirdsql.gds.ng.tz;

import java.time.OffsetDateTime;
import java.time.OffsetTime;

import static org.firebirdsql.gds.ISCConstants.SQL_TIMESTAMP_TZ;
import static org.firebirdsql.jaybird.util.ByteArrayHelper.fromHexString;

class TimeZoneCodecStandardTimestampTzTest extends TimeZoneCodecAbstractTimestampTzTest {

    @Override
    int getTzType() {
        return SQL_TIMESTAMP_TZ;
    }

    @Override
    byte[] getOffsetNetworkAt2019_03_09Input() {
        return fromHexString(TIMESTAMPTZ_AT_2019_03_09_OFFSET_1_NETWORK_HEX);
    }

    @Override
    byte[] getOffsetNetworkAt2019_03_09Expected() {
        return getOffsetNetworkAt2019_03_09Input();
    }

    @Override
    byte[] getOffsetNetworkAt2019_07_01Input() {
        return fromHexString(TIMESTAMPTZ_AT_2019_07_01_OFFSET_1_NETWORK_HEX);
    }

    @Override
    byte[] getOffsetNetworkAt2019_07_01Expected() {
        return getOffsetNetworkAt2019_07_01Input();
    }

    @Override
    OffsetDateTime getOffsetExpectedOffsetDateTimeAt2019_07_01() {
        return OFFSET_1_OFFSET_DATE_TIME_AT_2019_07_01;
    }

    @Override
    OffsetTime getOffsetExpectedOffsetTimeAt2019_07_01() {
        return OFFSET_1_OFFSET_TIME;
    }

    @Override
    byte[] getZoneNetworkAt2019_03_09Input() {
        return fromHexString(TIMESTAMPTZ_AT_2019_03_09_ZONE_NETWORK_HEX);
    }

    @Override
    byte[] getZoneNetworkAt2019_03_09Expected() {
        return getZoneNetworkAt2019_03_09Input();
    }

    @Override
    byte[] getZoneNetworkAt2019_07_01Input() {
        return fromHexString(TIMESTAMPTZ_AT_2019_07_01_ZONE_NETWORK_HEX);
    }

    @Override
    byte[] getZoneNetworkAt2019_07_01Expected() {
        return getZoneNetworkAt2019_07_01Input();
    }

    @Override
    OffsetDateTime getZoneExpectedOffsetDateTimeAt2019_07_01() {
        return OFFSET_2_OFFSET_DATE_TIME_AT_2019_07_01;
    }

    @Override
    OffsetTime getZoneExpectedOffsetTimeAt2019_07_01() {
        return OFFSET_2_OFFSET_TIME;
    }

    @Override
    byte[] getOffsetLeAt2019_03_09Input() {
        return fromHexString(TIMESTAMPTZ_AT_2019_03_09_OFFSET_1_LE_HEX);
    }

    @Override
    byte[] getOffsetLeAt2019_03_09Expected() {
        return getOffsetLeAt2019_03_09Input();
    }

    @Override
    byte[] getZoneLeAt2019_03_09Input() {
        return fromHexString(TIMESTAMPTZ_AT_2019_03_09_ZONE_LE_HEX);
    }

    @Override
    byte[] getZoneLeAt2019_03_09Expected() {
        return getZoneLeAt2019_03_09Input();
    }

    @Override
    byte[] getOffsetBeAt2019_03_09Input() {
        return fromHexString(TIMESTAMPTZ_AT_2019_03_09_OFFSET_1_BE_HEX);
    }

    @Override
    byte[] getOffsetBeAt2019_03_09Expected() {
        return getOffsetBeAt2019_03_09Input();
    }

    @Override
    byte[] getZoneBeAt2019_03_09Input() {
        return fromHexString(TIMESTAMPTZ_AT_2019_03_09_ZONE_BE_HEX);
    }

    @Override
    byte[] getZoneBeAt2019_03_09Expected() {
        return getZoneBeAt2019_03_09Input();
    }

}