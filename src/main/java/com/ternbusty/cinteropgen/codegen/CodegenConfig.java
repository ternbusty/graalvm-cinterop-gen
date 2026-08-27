package com.ternbusty.cinteropgen.codegen;

/**
 * Configuration for Java code generation.
 *
 * @param pkg           Java package name
 * @param className     Name of the functions/constants class (empty = derived from header)
 * @param headerInclude C header for CContext, e.g. "sys/stat.h"
 * @param emitComments  Whether to emit C type comments in generated code
 */
public record CodegenConfig(
        String pkg,
        String className,
        String headerInclude,
        boolean emitComments
) {
    public CodegenConfig {
        if (pkg == null || pkg.isEmpty()) pkg = "generated";
        if (className == null) className = "";
        if (headerInclude == null) headerInclude = "";
    }
}
