// SPDX-FileCopyrightText: Copyright 2015-2024 Mark Rotteveel
// SPDX-License-Identifier: LGPL-2.1-or-later
package org.firebirdsql.gds.ng.wire.crypt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import static java.util.Collections.emptyMap;
import static java.util.Collections.unmodifiableMap;
import static java.util.Objects.requireNonNull;

/**
 * Class to hold server keys known to the client.
 *
 * @author Mark Rotteveel
 * @since 3.0
 */
public final class KnownServerKey {

    private static final Pattern CRYPT_PLUGIN_LIST_SPLIT = Pattern.compile("[ \t,;]+");

    private final String keyType;
    private final List<String> plugins;
    private final Map<String, byte[]> specificData;

    private KnownServerKey(String keyType, List<String> plugins, Map<String, byte[]> specificData) {
        this.keyType = requireNonNull(keyType, "keyType");
        this.plugins = List.copyOf(requireNonNull(plugins, "plugins"));
        this.specificData = specificData == null || specificData.isEmpty()
                ? emptyMap()
                : unmodifiableMap(specificData);
    }

    public KnownServerKey(String keyType, String plugins, Map<String, byte[]> specificData) {
        this(keyType, Arrays.asList(CRYPT_PLUGIN_LIST_SPLIT.split(plugins)), specificData);
    }

    public List<PluginSpecificData> getPluginSpecificData() {
        List<PluginSpecificData> pluginSpecificData = new ArrayList<>(plugins.size());
        for (String plugin : plugins) {
            EncryptionIdentifier identifier = new EncryptionIdentifier(keyType, plugin);
            pluginSpecificData.add(new PluginSpecificData(identifier, specificData.get(plugin)));
        }
        return pluginSpecificData;
    }

    public void clear() {
        specificData.values().stream()
                .filter(Objects::nonNull)
                .forEach(b -> Arrays.fill(b, (byte) 0));
    }

    /**
     * Class to hold plugin specific data.
     *
     * @param encryptionIdentifier
     *         encryption identifier
     * @param specificData
     *         plugin specific data (can be {@code null})
     * @since 5
     */
    @SuppressWarnings("java:S6218")
    public record PluginSpecificData(EncryptionIdentifier encryptionIdentifier, byte[] specificData) {

        public PluginSpecificData {
            requireNonNull(encryptionIdentifier, "encryptionIdentifier");
        }

    }
}
