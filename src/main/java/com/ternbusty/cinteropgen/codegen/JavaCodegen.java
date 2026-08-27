package com.ternbusty.cinteropgen.codegen;

import com.ternbusty.cinteropgen.ir.Header;
import com.ternbusty.cinteropgen.ir.Header.*;
import com.ternbusty.cinteropgen.mapper.TypeMapper;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static com.ternbusty.cinteropgen.mapper.TypeMapper.toCamelCase;
import static com.ternbusty.cinteropgen.mapper.TypeMapper.toPascalCase;

/**
 * Generate Java source files with GraalVM CInterop annotations
 * from the parsed IR.
 */
public class JavaCodegen {

    private final CodegenConfig config;
    private TypeMapper typeMapper;

    public JavaCodegen(CodegenConfig config) {
        this.config = config;
        this.typeMapper = new TypeMapper();
    }

    public Map<String, String> generate(Header header) {
        // Initialize TypeMapper with known function pointer typedef names.
        var fpNames = header.functionPointers().stream()
                .map(FunctionPointerDecl::name)
                .collect(Collectors.toSet());
        this.typeMapper = new TypeMapper(fpNames);

        var className = config.className().isEmpty()
                ? toPascalCase(header.name())
                : config.className();

        var files = new LinkedHashMap<String, String>();
        var pkgDir = config.pkg().replace('.', '/');

        // One interface per struct.
        for (var struct : header.structs()) {
            if (struct.name().isEmpty() || struct.fields().isEmpty()) continue;
            var name = toPascalCase(struct.name());
            files.put(pkgDir + "/" + name + ".java", genStruct(struct));
        }

        // One class per enum.
        for (var en : header.enums()) {
            if (en.name().isEmpty() || en.constants().isEmpty()) continue;
            var name = toPascalCase(en.name());
            files.put(pkgDir + "/" + name + ".java", genEnum(en));
        }

        // CFunctionPointer interfaces.
        for (var fp : header.functionPointers()) {
            var name = toPascalCase(fp.name());
            files.put(pkgDir + "/" + name + ".java", genFunctionPointer(fp));
        }

        // @CPointerTo interfaces for double-pointer-to-struct types.
        var pointerToStructs = collectDoublePointerStructs(header);
        for (var structName : pointerToStructs) {
            var javaName = toPascalCase(structName);
            var ptrName = javaName + "Pointer";
            files.put(pkgDir + "/" + ptrName + ".java",
                    genPointerTo(structName, javaName, ptrName));
        }

        // Functions + constants in one class.
        if (!header.functions().isEmpty() || !header.constants().isEmpty()) {
            files.put(pkgDir + "/" + className + ".java",
                    genFunctionsClass(header, className));
        }

        return files;
    }

    // ── Struct ───────────────────────────────────────────────────

    private String genStruct(StructDecl struct) {
        var javaName = toPascalCase(struct.name());
        var sb = new StringBuilder();

        sb.append("package ").append(config.pkg()).append(";\n\n");
        appendStructImports(sb, struct);

        var keyword = struct.isUnion() ? "union" : "struct";
        sb.append("/** C ").append(keyword).append(' ')
                .append(struct.name()).append(" */\n");
        sb.append("@CStruct(\"").append(struct.name()).append("\")\n");
        sb.append("public interface ").append(javaName)
                .append(" extends PointerBase {\n\n");

        for (var field : struct.fields()) {
            appendField(sb, field);
        }

        sb.append("}\n");
        return sb.toString();
    }

    private void appendField(StringBuilder sb, StructField field) {
        // Bitfield: skip with comment.
        if (field.isBitfield()) {
            sb.append("    // Bitfield '").append(field.name())
                    .append("' (").append(field.qualType())
                    .append(", ").append(field.bitWidth())
                    .append(" bit) skipped: @CField does not support bitfields.\n\n");
            return;
        }

        var javaType = typeMapper.map(field.qualType());
        var getter = toCamelCase(field.name());
        boolean isAddressable = isStructOrArrayType(field.qualType());

        if (config.emitComments()) {
            sb.append("    /** ").append(field.qualType()).append(" */\n");
        }

        if (isAddressable) {
            sb.append("    @CFieldAddress(\"").append(field.name())
                    .append("\")\n");
            sb.append("    ").append(javaType).append(' ')
                    .append(getter).append("();\n\n");
        } else {
            sb.append("    @CField(\"").append(field.name()).append("\")\n");
            sb.append("    ").append(javaType).append(' ')
                    .append(getter).append("();\n\n");
            sb.append("    @CField(\"").append(field.name()).append("\")\n");
            sb.append("    void ").append(getter).append('(')
                    .append(javaType).append(" value);\n\n");
        }
    }

    private boolean isStructOrArrayType(String qualType) {
        var qt = qualType.strip();
        return qt.startsWith("struct ") || qt.startsWith("union ") || qt.contains("[");
    }

    private void appendStructImports(StringBuilder sb, StructDecl struct) {
        var imports = new TreeSet<String>();
        imports.add("org.graalvm.nativeimage.c.struct.CStruct");
        imports.add("org.graalvm.word.PointerBase");

        boolean needsCField = false;
        boolean needsCFieldAddress = false;
        for (var f : struct.fields()) {
            if (f.isBitfield()) continue;
            if (isStructOrArrayType(f.qualType())) {
                needsCFieldAddress = true;
            } else {
                needsCField = true;
            }
            collectTypeImport(imports, typeMapper.map(f.qualType()));
        }
        if (needsCField)
            imports.add("org.graalvm.nativeimage.c.struct.CField");
        if (needsCFieldAddress)
            imports.add("org.graalvm.nativeimage.c.struct.CFieldAddress");

        for (var imp : imports) {
            sb.append("import ").append(imp).append(";\n");
        }
        sb.append('\n');
    }

    // ── Enum ─────────────────────────────────────────────────────

    private String genEnum(EnumDecl en) {
        var javaName = toPascalCase(en.name());
        var sb = new StringBuilder();

        sb.append("package ").append(config.pkg()).append(";\n\n");
        if (config.emitComments()) {
            sb.append("/** C enum ").append(en.name()).append(" */\n");
        }
        sb.append("public final class ").append(javaName).append(" {\n\n");
        sb.append("    private ").append(javaName).append("() {}\n\n");

        for (var c : en.constants()) {
            sb.append("    public static final int ").append(c.name())
                    .append(" = ").append(c.value()).append(";\n");
        }
        sb.append("\n}\n");
        return sb.toString();
    }

    // ── CFunctionPointer ─────────────────────────────────────────

    private String genFunctionPointer(FunctionPointerDecl fp) {
        var javaName = toPascalCase(fp.name());
        var sb = new StringBuilder();

        sb.append("package ").append(config.pkg()).append(";\n\n");
        appendFunctionPointerImports(sb, fp);

        if (config.emitComments()) {
            sb.append("/** C function pointer typedef ").append(fp.name())
                    .append(" */\n");
        }
        sb.append("@CFunctionPointer\n");
        sb.append("public interface ").append(javaName)
                .append(" extends CFunctionPointer {\n\n");

        var retType = typeMapper.map(fp.returnQualType());
        sb.append("    @InvokeCFunctionPointer\n");
        sb.append("    ").append(retType).append(" invoke(");

        var params = fp.params();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            var p = params.get(i);
            sb.append(typeMapper.map(p.qualType())).append(' ')
                    .append(safeJavaName(p.name()));
        }
        sb.append(");\n");

        sb.append("}\n");
        return sb.toString();
    }

    private void appendFunctionPointerImports(StringBuilder sb,
                                               FunctionPointerDecl fp) {
        var imports = new TreeSet<String>();
        imports.add("org.graalvm.nativeimage.c.function.CFunctionPointer");
        imports.add("org.graalvm.nativeimage.c.function.InvokeCFunctionPointer");

        collectTypeImport(imports, typeMapper.map(fp.returnQualType()));
        for (var p : fp.params()) {
            collectTypeImport(imports, typeMapper.map(p.qualType()));
        }

        for (var imp : imports) {
            sb.append("import ").append(imp).append(";\n");
        }
        sb.append('\n');
    }

    // ── @CPointerTo ──────────────────────────────────────────────

    private Set<String> collectDoublePointerStructs(Header header) {
        var result = new LinkedHashSet<String>();
        for (var func : header.functions()) {
            for (var param : func.params()) {
                var name = TypeMapper.doublePointerStructName(param.qualType());
                if (name != null) result.add(name);
            }
            var retName = TypeMapper.doublePointerStructName(
                    func.returnQualType());
            if (retName != null) result.add(retName);
        }
        for (var struct : header.structs()) {
            for (var field : struct.fields()) {
                var name = TypeMapper.doublePointerStructName(field.qualType());
                if (name != null) result.add(name);
            }
        }
        return result;
    }

    private String genPointerTo(String cStructName, String javaStructName,
                                String ptrName) {
        var sb = new StringBuilder();
        sb.append("package ").append(config.pkg()).append(";\n\n");
        sb.append("import org.graalvm.nativeimage.c.struct.CPointerTo;\n");
        sb.append("import org.graalvm.word.PointerBase;\n\n");

        if (config.emitComments()) {
            sb.append("/** Typed pointer to ").append(cStructName)
                    .append(" (for struct ").append(cStructName)
                    .append(" **) */\n");
        }
        sb.append("@CPointerTo(").append(javaStructName).append(".class)\n");
        sb.append("public interface ").append(ptrName)
                .append(" extends PointerBase {\n\n");

        sb.append("    ").append(javaStructName).append(" read();\n\n");
        sb.append("    ").append(javaStructName)
                .append(" read(int index);\n\n");
        sb.append("    void write(").append(javaStructName)
                .append(" value);\n\n");
        sb.append("    void write(int index, ")
                .append(javaStructName).append(" value);\n");

        sb.append("}\n");
        return sb.toString();
    }

    // ── Functions + Constants ────────────────────────────────────

    private String genFunctionsClass(Header header, String className) {
        var sb = new StringBuilder();

        sb.append("package ").append(config.pkg()).append(";\n\n");
        appendFunctionImports(sb, header, className);

        if (!config.headerInclude().isEmpty()) {
            sb.append("@CContext(").append(className)
                    .append(".Directives.class)\n");
        }
        sb.append("public final class ").append(className).append(" {\n\n");
        sb.append("    private ").append(className).append("() {}\n\n");

        if (!config.headerInclude().isEmpty()) {
            sb.append("    public static final class Directives")
                    .append(" implements CContext.Directives {\n");
            sb.append("        @Override\n");
            sb.append("        public List<String> getHeaderFiles() {\n");
            sb.append("            return List.of(\"<")
                    .append(config.headerInclude()).append(">\");\n");
            sb.append("        }\n");
            sb.append("    }\n\n");
        }

        for (var func : header.functions()) {
            appendFunction(sb, func);
        }

        for (var mc : header.constants()) {
            appendMacroConstant(sb, mc);
        }

        sb.append("}\n");
        return sb.toString();
    }

    private void appendFunction(StringBuilder sb, FunctionDecl func) {
        var retType = typeMapper.map(func.returnQualType());

        if (config.emitComments()) {
            sb.append("    /** ").append(cSignature(func)).append(" */\n");
        }

        // Variadic warning.
        if (func.isVariadic()) {
            sb.append("    // NOTE: Variadic function. CInterop binds")
                    .append(" only the fixed parameters.\n");
        }

        // Struct by-value return warning.
        if (typeMapper.isStructByValue(func.returnQualType())) {
            sb.append("    // NOTE: Returns struct by value.")
                    .append(" Consider CFunction.Transition.NO_TRANSITION.\n");
        }

        sb.append("    @CFunction(\"").append(func.name()).append("\")\n");
        sb.append("    public static native ").append(retType).append(' ')
                .append(func.name()).append('(');

        var params = func.params();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            var p = params.get(i);
            sb.append(typeMapper.map(p.qualType())).append(' ')
                    .append(safeJavaName(p.name()));
        }
        sb.append(");\n\n");
    }

    private void appendMacroConstant(StringBuilder sb, MacroConstant mc) {
        var value = mc.value().strip();
        var cleaned = value.replaceAll("[uUlLfF]+$", "");
        try {
            long v = Long.decode(cleaned);
            if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
                sb.append("    public static final int ").append(mc.name())
                        .append(" = ").append(cleaned).append(";\n\n");
            } else {
                sb.append("    public static final long ").append(mc.name())
                        .append(" = ").append(cleaned).append("L;\n\n");
            }
            return;
        } catch (NumberFormatException ignored) {}

        try {
            Double.parseDouble(cleaned);
            sb.append("    public static final double ").append(mc.name())
                    .append(" = ").append(value).append(";\n\n");
            return;
        } catch (NumberFormatException ignored) {}

        if (value.startsWith("\"")) {
            sb.append("    // #define ").append(mc.name()).append(' ')
                    .append(value).append('\n');
        }
    }

    private void appendFunctionImports(StringBuilder sb, Header header,
                                        String className) {
        var imports = new TreeSet<String>();
        imports.add("org.graalvm.nativeimage.c.function.CFunction");

        if (!config.headerInclude().isEmpty()) {
            imports.add("org.graalvm.nativeimage.c.CContext");
            imports.add("java.util.List");
        }

        for (var func : header.functions()) {
            collectTypeImport(imports, typeMapper.map(func.returnQualType()));
            for (var p : func.params()) {
                collectTypeImport(imports, typeMapper.map(p.qualType()));
            }
        }

        for (var imp : imports) {
            sb.append("import ").append(imp).append(";\n");
        }
        sb.append('\n');
    }

    private void collectTypeImport(Set<String> imports, String javaType) {
        switch (javaType) {
            case "CCharPointer" ->
                    imports.add("org.graalvm.nativeimage.c.type.CCharPointer");
            case "CCharPointerPointer" ->
                    imports.add("org.graalvm.nativeimage.c.type.CCharPointerPointer");
            case "CIntPointer" ->
                    imports.add("org.graalvm.nativeimage.c.type.CIntPointer");
            case "CShortPointer" ->
                    imports.add("org.graalvm.nativeimage.c.type.CShortPointer");
            case "CLongPointer" ->
                    imports.add("org.graalvm.nativeimage.c.type.CLongPointer");
            case "CFloatPointer" ->
                    imports.add("org.graalvm.nativeimage.c.type.CFloatPointer");
            case "CDoublePointer" ->
                    imports.add("org.graalvm.nativeimage.c.type.CDoublePointer");
            case "VoidPointer" ->
                    imports.add("org.graalvm.nativeimage.c.type.VoidPointer");
            case "PointerBase" ->
                    imports.add("org.graalvm.word.PointerBase");
            case "UnsignedWord" ->
                    imports.add("org.graalvm.word.UnsignedWord");
            case "SignedWord" ->
                    imports.add("org.graalvm.word.SignedWord");
        }
    }

    private String cSignature(FunctionDecl func) {
        var sb = new StringBuilder();
        sb.append(func.returnQualType()).append(' ').append(func.name())
                .append('(');
        var params = func.params();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            var p = params.get(i);
            sb.append(p.qualType()).append(' ').append(p.name());
        }
        if (func.isVariadic()) {
            if (!params.isEmpty()) sb.append(", ");
            sb.append("...");
        }
        sb.append(')');
        return sb.toString();
    }

    private static final Set<String> JAVA_RESERVED = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case",
            "catch", "char", "class", "const", "continue", "default",
            "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long",
            "native", "new", "package", "private", "protected",
            "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw",
            "throws", "transient", "try", "void", "volatile", "while"
    );

    private static String safeJavaName(String name) {
        return JAVA_RESERVED.contains(name) ? name + "_" : name;
    }
}
