// SPDX-FileCopyrightText: Copyright 2018-2024 Mark Rotteveel
// SPDX-License-Identifier: LGPL-2.1-or-later
package org.firebirdsql.jdbc.metadata;

import org.firebirdsql.util.InternalApi;

import java.util.regex.Pattern;

import static org.firebirdsql.jdbc.metadata.FbMetadataConstants.OBJECT_NAME_LENGTH;

/**
 * Holder of a database metadata pattern.
 * <p>
 * Provides information whether the pattern is the all-pattern, or if the condition needs a normal equality comparison,
 * a SQL {@code LIKE}, or a SQL {@code STARTING WITH}.
 * </p>
 *
 * @author Mark Rotteveel
 * @since 4.0
 */
@InternalApi
public final class MetadataPattern {

    // Extra space to allow for longer patterns (avoids string right truncation errors)
    private static final int OBJECT_NAME_PARAMETER_LENGTH = OBJECT_NAME_LENGTH + 10;
    private static final MetadataPattern ALL_PATTERN = new MetadataPattern(ConditionType.NONE, null);
    private static final MetadataPattern EMPTY_PATTERN = new MetadataPattern(ConditionType.SQL_EQUALS, "");
    private static final MetadataPattern IS_NULL = new MetadataPattern(ConditionType.SQL_IS_NULL, null);
    private static final Pattern METADATA_SPECIALS = Pattern.compile("([\\\\_%])");

    private final ConditionType conditionType;
    private final String conditionValue;

    /**
     * Create a metadata pattern.
     *
     * @param conditionType
     *         Type of condition to be used.
     * @param conditionValue
     *         Value to be used with the specified condition type
     */
    private MetadataPattern(ConditionType conditionType, String conditionValue) {
        this.conditionType = conditionType;
        this.conditionValue = conditionValue;
    }

    /**
     * @return Type of condition to use for this metadata pattern
     */
    public ConditionType getConditionType() {
        return conditionType;
    }

    /**
     * @return Value for the condition; {@code null} signals no value
     */
    public String getConditionValue() {
        return conditionValue;
    }

    /**
     * Renders the condition for this pattern.
     *
     * @param columnName
     *         column name
     * @return Rendered condition (can be empty string if there is no condition).
     */
    public String renderCondition(String columnName) {
        return conditionType.renderCondition(columnName);
    }

    /**
     * @return Metadata pattern matcher for this metadata pattern
     */
    public MetadataPatternMatcher toMetadataPatternMatcher() {
        return MetadataPatternMatcher.fromPattern(this);
    }

    /**
     * Compiles the metadata pattern.
     *
     * @param metadataPattern
     *         Metadata pattern string
     * @return MetadataPattern instance
     */
    public static MetadataPattern compile(String metadataPattern) {
        if (isAllCondition(metadataPattern)) {
            return ALL_PATTERN;
        }
        if (metadataPattern.isEmpty()) {
            return EMPTY_PATTERN;
        }
        if (!containsPatternSpecialChars(metadataPattern)) {
            return new MetadataPattern(ConditionType.SQL_EQUALS, metadataPattern);
        }

        return parsePattern(metadataPattern);
    }

    /**
     * Creates a {@code MetadataPattern} explicit for an <em>equals</em> ({@code =}) condition.
     *
     * @param value value for equals condition
     * @return MetadataPattern of type {@code SQL_EQUALS}
     */
    static MetadataPattern equalsCondition(String value) {
        return new MetadataPattern(ConditionType.SQL_EQUALS, value);
    }

    /**
     * Creates a {@code MetadataPattern} for an {@code IS NULL} condition
     *
     * @return MetadataPattern of type {@code SQL_IS_NULL}
     */
    static MetadataPattern isNullCondition() {
        return IS_NULL;
    }

    /**
     * Scans string to determine if string contains any of {@code \_%} that indicates additional processing is needed.
     *
     * @param pattern
     *         metadata pattern
     * @return {@code true} if the string contains any like special characters
     */
    public static boolean containsPatternSpecialChars(String pattern) {
        for (int idx = 0; idx < pattern.length(); idx++) {
            if (isPatternSpecialChar(pattern.charAt(idx))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if character is a database metadata pattern special.
     *
     * @param charVal
     *         Character to check
     * @return {@code true} if {@code charVal} is a pattern special ({@code \_%})
     */
    public static boolean isPatternSpecialChar(char charVal) {
        return charVal == '%' || charVal == '_' || charVal == '\\';
    }

    /**
     * Escapes the like wildcards and escape ({@code \_%} in the provided search string with a {@code \}.
     *
     * @param objectName
     *         Object name to escape.
     * @return Object name with wildcards escaped.
     */
    public static String escapeWildcards(String objectName) {
        if (objectName == null) {
            return null;
        }
        return METADATA_SPECIALS.matcher(objectName).replaceAll("\\\\$1");
    }

    @SuppressWarnings("java:S127")
    private static MetadataPattern parsePattern(String metadataPattern) {
        ConditionType conditionType = ConditionType.SQL_EQUALS;
        boolean hasEscape = false;
        final int patternLength = metadataPattern.length();
        final StringBuilder likePattern = new StringBuilder(patternLength);
        for (int idx = 0; idx < patternLength; idx++) {
            char ch = metadataPattern.charAt(idx);
            switch (ch) {
            case '%' -> {
                if (idx == patternLength - 1 && conditionType == ConditionType.SQL_EQUALS) {
                    // No other unescaped LIKE pattern characters: we can use STARTING_WITH
                    conditionType = ConditionType.SQL_STARTING_WITH;
                    // Pattern complete (without the final %)
                } else {
                    conditionType = ConditionType.SQL_LIKE;
                    likePattern.append(ch);
                }
            }
            case '_' -> {
                conditionType = ConditionType.SQL_LIKE;
                likePattern.append(ch);
            }
            case '\\' -> {
                hasEscape = true;
                if (idx < patternLength - 1 && isPatternSpecialChar(metadataPattern.charAt(idx + 1))) {
                    // We add the character here, so we skip it in the next iteration to distinguish
                    // unescaped wildcards (which trigger SQL_LIKE) from escaped wildcards
                    likePattern.append('\\').append(metadataPattern.charAt(++idx));
                } else {
                    // Escape followed by non-special or end of string: introduce escape
                    likePattern.append('\\').append('\\');
                }
            }
            default -> likePattern.append(ch);
            }
        }

        if (!hasEscape) {
            return new MetadataPattern(conditionType,
                    conditionType == ConditionType.SQL_EQUALS ? metadataPattern : likePattern.toString());
        }

        if (conditionType != ConditionType.SQL_LIKE) {
            // We have no unescaped wildcards; strip escapes so we can use plain equals
            stripEscapes(likePattern);
        }

        return new MetadataPattern(conditionType, likePattern.toString());
    }

    private static void stripEscapes(StringBuilder likePattern) {
        for (int idx = 0; idx < likePattern.length(); idx++) {
            if (likePattern.charAt(idx) == '\\') {
                // This removes the escape and then skips the next (previously escaped) character
                likePattern.deleteCharAt(idx);
            }
        }
    }

    public static boolean isAllCondition(String metadataPattern) {
        return metadataPattern == null || "%".equals(metadataPattern);
    }

    public enum ConditionType {
        NONE {
            @Override
            String renderCondition(String columnName) {
                return "";
            }
        },
        SQL_LIKE {
            @Override
            String renderCondition(String columnName) {
                // We are trimming trailing spaces to avoid issues with LIKE and CHAR padding
                return "trim(trailing from " + columnName + ") like "
                        + "cast(? as varchar(" + OBJECT_NAME_PARAMETER_LENGTH + ")) escape '\\' ";
            }
        },
        SQL_EQUALS {
            @Override
            String renderCondition(String columnName) {
                return columnName + " = cast(? as varchar(" + OBJECT_NAME_PARAMETER_LENGTH + ")) ";
            }
        },
        SQL_STARTING_WITH {
            @Override
            String renderCondition(String columnName) {
                return columnName + " starting with cast(? as varchar(" + OBJECT_NAME_PARAMETER_LENGTH + ")) ";
            }
        },
        SQL_IS_NULL {
            @Override
            String renderCondition(String columnName) {
                return columnName + " is null ";
            }
        };

        /**
         * Renders a parameterized condition of this type.
         *
         * @param columnName
         *         Name of columns
         * @return Rendered condition (or empty string for no condition)
         */
        abstract String renderCondition(String columnName);
    }

}
