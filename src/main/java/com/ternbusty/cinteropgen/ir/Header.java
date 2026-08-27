package com.ternbusty.cinteropgen.ir;

import java.util.List;

/**
 * Intermediate representation for all declarations parsed from a C header.
 *
 * Each nested record carries the qualType string from clang's AST, which
 * the TypeMapper resolves to GraalVM CInterop Java types at codegen time.
 */
public record Header(
        String name,
        List<StructDecl> structs,
        List<FunctionDecl> functions,
        List<EnumDecl> enums,
        List<MacroConstant> constants,
        List<TypedefDecl> typedefs,
        List<FunctionPointerDecl> functionPointers
) {
    public record StructDecl(String name, List<StructField> fields, boolean isUnion) {}

    public record StructField(String name, String qualType, boolean isBitfield, int bitWidth) {}

    public record FunctionDecl(
            String name,
            String returnQualType,
            List<FunctionParam> params,
            boolean isVariadic
    ) {}

    public record FunctionParam(String name, String qualType) {}

    public record EnumDecl(String name, List<EnumConstant> constants) {}

    public record EnumConstant(String name, long value) {}

    public record MacroConstant(String name, String value) {}

    public record TypedefDecl(String name, String underlyingQualType) {}

    /**
     * A function pointer typedef, e.g. {@code typedef void (*callback_fn)(int, const char *)}.
     */
    public record FunctionPointerDecl(
            String name,
            String returnQualType,
            List<FunctionParam> params
    ) {}
}
