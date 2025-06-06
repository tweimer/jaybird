// SPDX-FileCopyrightText: Copyright 2023-2024 Mark Rotteveel
// SPDX-License-Identifier: LGPL-2.1-or-later
module org.firebirdsql.jaybird {
    requires transitive java.sql;
    requires transitive java.transaction.xa;
    requires transitive java.naming;

    // Declare as optional for deployment simplicity
    requires static org.jspecify;

    exports org.firebirdsql.ds;
    exports org.firebirdsql.encodings;
    exports org.firebirdsql.event;
    exports org.firebirdsql.extern.decimal;
    exports org.firebirdsql.gds;
    exports org.firebirdsql.gds.impl;
    exports org.firebirdsql.gds.impl.argument;
    exports org.firebirdsql.gds.impl.wire;
    exports org.firebirdsql.gds.ng;
    exports org.firebirdsql.gds.ng.dbcrypt;
    exports org.firebirdsql.gds.ng.fields;
    exports org.firebirdsql.gds.ng.listeners;
    exports org.firebirdsql.gds.ng.monitor;
    exports org.firebirdsql.gds.ng.wire;
    exports org.firebirdsql.gds.ng.wire.auth;
    exports org.firebirdsql.gds.ng.wire.crypt;
    exports org.firebirdsql.jaybird.fb.constants;
    exports org.firebirdsql.jaybird.props;
    exports org.firebirdsql.jaybird.props.def;
    exports org.firebirdsql.jaybird.props.spi;
    exports org.firebirdsql.jaybird.util to org.firebirdsql.jna;
    exports org.firebirdsql.jaybird.xca;
    exports org.firebirdsql.jdbc;
    exports org.firebirdsql.jdbc.field;
    exports org.firebirdsql.management;
    exports org.firebirdsql.util;

    provides java.sql.Driver
            with org.firebirdsql.jdbc.FBDriver;

    uses org.firebirdsql.encodings.EncodingSet;
    provides org.firebirdsql.encodings.EncodingSet
            with org.firebirdsql.encodings.DefaultEncodingSet;

    uses org.firebirdsql.gds.impl.GDSFactoryPlugin;
    provides org.firebirdsql.gds.impl.GDSFactoryPlugin
            with org.firebirdsql.gds.impl.wire.WireGDSFactoryPlugin;

    uses org.firebirdsql.gds.ng.wire.auth.AuthenticationPluginSpi;
    provides org.firebirdsql.gds.ng.wire.auth.AuthenticationPluginSpi
            with org.firebirdsql.gds.ng.wire.auth.legacy.LegacyAuthenticationPluginSpi,
                    org.firebirdsql.gds.ng.wire.auth.srp.SrpAuthenticationPluginSpi,
                    org.firebirdsql.gds.ng.wire.auth.srp.Srp224AuthenticationPluginSpi,
                    org.firebirdsql.gds.ng.wire.auth.srp.Srp256AuthenticationPluginSpi,
                    org.firebirdsql.gds.ng.wire.auth.srp.Srp384AuthenticationPluginSpi,
                    org.firebirdsql.gds.ng.wire.auth.srp.Srp512AuthenticationPluginSpi;

    uses org.firebirdsql.gds.ng.wire.crypt.EncryptionPluginSpi;
    provides org.firebirdsql.gds.ng.wire.crypt.EncryptionPluginSpi
            with org.firebirdsql.gds.ng.wire.crypt.arc4.Arc4EncryptionPluginSpi,
                    org.firebirdsql.gds.ng.wire.crypt.chacha.ChaChaEncryptionPluginSpi;

    uses org.firebirdsql.gds.ng.wire.ProtocolDescriptor;
    provides org.firebirdsql.gds.ng.wire.ProtocolDescriptor
            with org.firebirdsql.gds.ng.wire.version10.Version10Descriptor,
                    org.firebirdsql.gds.ng.wire.version11.Version11Descriptor,
                    org.firebirdsql.gds.ng.wire.version12.Version12Descriptor,
                    org.firebirdsql.gds.ng.wire.version13.Version13Descriptor,
                    org.firebirdsql.gds.ng.wire.version15.Version15Descriptor,
                    org.firebirdsql.gds.ng.wire.version16.Version16Descriptor,
                    org.firebirdsql.gds.ng.wire.version18.Version18Descriptor;

    uses org.firebirdsql.jaybird.props.spi.ConnectionPropertyDefinerSpi;
}