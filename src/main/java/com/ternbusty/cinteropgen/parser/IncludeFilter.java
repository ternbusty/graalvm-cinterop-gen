package com.ternbusty.cinteropgen.parser;

import java.util.Set;

/**
 * Selective symbol filter for cinterop-gen.
 *
 * When any include set is non-empty the parser enters whitelist mode,
 * accepting only declarations whose names match. When all sets are
 * empty, every declaration is included (matching jextract's default).
 *
 * Each set accepts exact names or prefix globs ending with {@code *}
 * (e.g. {@code "SYS_*"}).
 */
public record IncludeFilter(
        Set<String> functions,
        Set<String> structs,
        Set<String> unions,
        Set<String> enums,
        Set<String> typedefs,
        Set<String> constants
) {
    public static final IncludeFilter NONE = new IncludeFilter(
            Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());

    /** True when any include list is non-empty (whitelist mode). */
    public boolean isSelective() {
        return !functions.isEmpty() || !structs.isEmpty()
                || !unions.isEmpty() || !enums.isEmpty()
                || !typedefs.isEmpty() || !constants.isEmpty();
    }

    public boolean matchesFunction(String name) {
        return matchesAny(functions, name);
    }

    public boolean matchesStruct(String name) {
        return matchesAny(structs, name);
    }

    public boolean matchesUnion(String name) {
        return matchesAny(unions, name);
    }

    public boolean matchesEnum(String name) {
        return matchesAny(enums, name);
    }

    public boolean matchesTypedef(String name) {
        return matchesAny(typedefs, name);
    }

    public boolean matchesConstant(String name) {
        return matchesAny(constants, name);
    }

    static boolean matchesAny(Set<String> patterns, String name) {
        if (patterns.isEmpty()) return false;
        for (var pattern : patterns) {
            if (pattern.endsWith("*")) {
                var prefix = pattern.substring(0, pattern.length() - 1);
                if (name.startsWith(prefix)) return true;
            } else {
                if (name.equals(pattern)) return true;
            }
        }
        return false;
    }
}
