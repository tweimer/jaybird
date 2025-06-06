// SPDX-FileCopyrightText: Copyright 2020-2023 Mark Rotteveel
// SPDX-License-Identifier: LGPL-2.1-or-later
package org.firebirdsql.jna.embedded.spi;

/**
 * Service provider interface to identify a Firebird Embedded library.
 * <p>
 * Implementations that provide a Firebird Embedded library need to implement this interface to provide the necessary
 * information to identify if it is a suitable implementation. The implementations of this interface need to be listed
 * in {@code META-INF/services/org.firebirdsql.jna.embedded.spi.FirebirdEmbeddedProvider} inside the jar that provides
 * the implementation.
 * </p>
 * <p>
 * For detailed requirements, see <a href="https://github.com/FirebirdSQL/jaybird/blob/master/devdoc/jdp/jdp-2020-05-firebird-embedded-locator-service-provider.md">jdp-2020-05:
 * Firebird Embedded locator service provider</a>
 * </p>
 * <p>
 * This class will be loaded using {@link java.util.ServiceLoader}. Implementations must provide a no-arg constructor.
 * </p>
 *
 * @author Mark Rotteveel
 * @since 5
 */
public interface FirebirdEmbeddedProvider {

    /**
     * Platform of this Firebird Embedded library.
     * <p>
     * Applies the platform naming conventions of JNA.
     * </p>
     *
     * @return Name of the platform (e.g. {@code "win32-x86-64"} for Windows 64-bit (x86))
     */
    String getPlatform();

    /**
     * Get the Firebird server version of this provider.
     * <p>
     * Implementations should report a version similar as reported by {@code isc_info_firebird_version} and as expected
     * by {@link org.firebirdsql.gds.impl.GDSServerVersion}, that is a format of
     * {@code <platform>-<type><majorVersion>.<minorVersion>.<variant>.<buildNum>[-<revision>] <serverName>},
     * where {@code platform} is a two-character platform identification string, Windows for example is "WI",
     * {@code type} is one of the three characters: "V" - production version, "T" - beta version, "X" - development
     * version.
     * </p>
     * <p>
     * This is not a hard requirement, but failure to comply may exclude the implementation from being used in
     * features like selecting a suitable Firebird Embedded version based on version requirements (such a feature does
     * not exist yet).
     * </p>
     *
     * @return Firebird version information (eg {@code "WI-V3.0.5.33220 Firebird 3.0"})
     */
    String getVersion();

    /**
     * Get an instance of the provided Firebird Embedded library.
     * <p>
     * For example, implementations could unpack a Firebird Embedded library to the filesystem, or try and find a
     * Firebird instance installed on the system.
     * </p>
     * <p>
     * If the provider has to perform initialization before the embedded library is usable (eg copy resources from the
     * classpath to a temporary location), this must be done in this method.
     * </p>
     * <p>
     * Implementations must be able to handle multiple calls to this method. It is allowed to return the same library
     * instance on subsequent invocations.
     * </p>
     *
     * @return Firebird Embedded Library information
     * @throws FirebirdEmbeddedLoadingException
     *         For exceptions loading or finding Firebird Embedded
     */
    FirebirdEmbeddedLibrary getFirebirdEmbeddedLibrary() throws FirebirdEmbeddedLoadingException;

}
