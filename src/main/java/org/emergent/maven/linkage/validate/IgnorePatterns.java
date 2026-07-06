package org.emergent.maven.linkage.validate;

import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Glob patterns over dotted class names ({@code javax.annotation.*}): {@code *} matches any
 * run of characters (dots included), {@code ?} matches one character.
 */
public final class IgnorePatterns {

    public static final IgnorePatterns NONE = new IgnorePatterns(List.of());

    private final List<Pattern> patterns;

    private IgnorePatterns(List<Pattern> patterns) {
        this.patterns = patterns;
    }

    public static IgnorePatterns compile(Collection<String> globs) {
        return new IgnorePatterns(globs.stream().map(IgnorePatterns::toRegex).toList());
    }

    public boolean isIgnored(String dottedClassName) {
        return patterns.stream().anyMatch(pattern -> pattern.matcher(dottedClassName).matches());
    }

    private static Pattern toRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    if (regex.length() < 2 || !regex.substring(regex.length() - 2).equals(".*")) {
                        regex.append(".*");
                    }
                }
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }
}
