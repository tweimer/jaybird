// SPDX-FileCopyrightText: Copyright 2018-2024 Mark Rotteveel
// SPDX-License-Identifier: LGPL-2.1-or-later
package org.firebirdsql.jdbc.metadata;

import org.firebirdsql.util.InternalApi;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Emulates behavior of a database metadata pattern.
 * <p>
 * This behaves similar to (but not 100% identical to) a SQL {@code LIKE} pattern with {@code ESCAPE '\'} clause.
 * </p>
 * <p>
 * This implementation is not thread-safe.
 * </p>
 *
 * @author Mark Rotteveel
 * @since 4.0
 */
@InternalApi
public abstract sealed class MetadataPatternMatcher {

    private MetadataPatternMatcher() {
        // Only allow derivation in nested classes
    }

    /**
     * Derives a metadata pattern matcher from a metadata pattern instance.
     *
     * @param metadataPattern
     *         Metadata pattern instance
     * @return Matcher for {@code metadataPattern}
     */
    public static MetadataPatternMatcher fromPattern(MetadataPattern metadataPattern) {
        return switch (metadataPattern.getConditionType()) {
            case NONE -> AllMatcher.INSTANCE;
            case SQL_EQUALS -> new EqualsMatcher(metadataPattern.getConditionValue());
            case SQL_STARTING_WITH -> new StartingWithMatcher(metadataPattern.getConditionValue());
            case SQL_LIKE -> new LikeMatcher(metadataPattern.getConditionValue());
            case SQL_IS_NULL -> NullMatcher.INSTANCE;
            default -> throw new AssertionError("Unexpected condition type " + metadataPattern.getConditionType());
        };
    }

    /**
     * Checks if {@code value} matches the pattern of this matcher.
     * <p>
     * This method is not thread-safe.
     * </p>
     *
     * @param value
     *         Value to check
     * @return {@code true} if {@code value} matches this pattern, {@code false} otherwise
     */
    public abstract boolean matches(String value);

    private static final class AllMatcher extends MetadataPatternMatcher {

        private static final AllMatcher INSTANCE = new AllMatcher();

        @Override
        public boolean matches(String value) {
            return true;
        }
    }

    private static final class EqualsMatcher extends MetadataPatternMatcher {

        private final String pattern;

        private EqualsMatcher(String pattern) {
            this.pattern = pattern;
        }

        @Override
        public boolean matches(String value) {
            return pattern.equals(value);
        }

    }

    private static final class StartingWithMatcher extends MetadataPatternMatcher {

        private final String pattern;

        private StartingWithMatcher(String pattern) {
            this.pattern = pattern;
        }

        @Override
        public boolean matches(String value) {
            return value != null && value.startsWith(pattern);
        }

    }

    private static final class LikeMatcher extends MetadataPatternMatcher {

        private final Matcher regexMatcher;

        private LikeMatcher(String pattern) {
            String regexPattern = MetadataPatternMatcher.patternToRegex(pattern);
            Pattern compiledPattern = Pattern.compile(regexPattern);
            regexMatcher = compiledPattern.matcher("");
        }

        @Override
        public boolean matches(String value) {
            if (value == null) {
                return false;
            }
            regexMatcher.reset(value);
            return regexMatcher.matches();
        }

    }

    private static final class NullMatcher extends MetadataPatternMatcher {

        private static final NullMatcher INSTANCE = new NullMatcher();

        @Override
        public boolean matches(String value) {
            return value == null;
        }

    }

    /**
     * Creates a regular expression pattern equivalent to the provided database metadata pattern.
     *
     * @param metadataPattern
     *         database metadata pattern
     * @return Pattern for the provided like string.
     */
    @SuppressWarnings("java:S127")
    public static String patternToRegex(final String metadataPattern) {
        final int patternLength = metadataPattern.length();
        // Derivation of additional 10: 8 chars for 2x quote pair (\Q..\E) + 2 chars for .*
        final StringBuilder patternString = new StringBuilder(patternLength + 10);
        final StringBuilder subPattern = new StringBuilder();
        for (int idx = 0; idx < patternLength; idx++) {
            char charVal = metadataPattern.charAt(idx);
            switch (charVal) {
            case '_', '%' -> {
                if (!subPattern.isEmpty()) {
                    patternString.append(Pattern.quote(subPattern.toString()));
                    subPattern.setLength(0);
                }
                patternString.append(charVal == '_' ? "." : ".*");
            }
            case '\\' -> {
                idx += 1;
                if (idx < patternLength) {
                    char nextChar = metadataPattern.charAt(idx);
                    if (!MetadataPattern.isPatternSpecialChar(nextChar)) {
                        // backslash before non-escapable character, handle as normal, see JDBC-562 and ODBC spec
                        // NOTE: given the use of MetadataPattern, this situation will not occur
                        subPattern.append('\\');
                    }
                    subPattern.append(nextChar);
                } else {
                    // backslash at end of string, handled as normal, see JDBC-562 and ODBC spec
                    // NOTE: given the use of MetadataPattern, this situation will not occur
                    subPattern.append('\\');
                }
            }
            default -> subPattern.append(charVal);
            }
        }

        if (!subPattern.isEmpty()) {
            patternString.append(Pattern.quote(subPattern.toString()));
        }

        return patternString.toString();
    }

}
