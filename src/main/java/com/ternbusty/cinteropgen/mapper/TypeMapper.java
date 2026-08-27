package com.ternbusty.cinteropgen.mapper;

import java.util.Map;
import java.util.Set;

/**
 * Map C type strings (qualType from clang's AST) to GraalVM CInterop
 * Java type names.
 */
public class TypeMapper {

    private static final Map<String, String> WORD_TYPES = Map.of(
            "size_t", "UnsignedWord",
            "ssize_t", "SignedWord",
            "uintptr_t", "UnsignedWord",
            "intptr_t", "SignedWord",
            "ptrdiff_t", "SignedWord"
    );

    private static final Map<String, String> FIXED_WIDTH = Map.ofEntries(
            Map.entry("int8_t", "byte"),
            Map.entry("uint8_t", "byte"),
            Map.entry("int16_t", "short"),
            Map.entry("uint16_t", "short"),
            Map.entry("int32_t", "int"),
            Map.entry("uint32_t", "int"),
            Map.entry("int64_t", "long"),
            Map.entry("uint64_t", "long"),
            Map.entry("pid_t", "int"),
            Map.entry("uid_t", "int"),
            Map.entry("gid_t", "int"),
            Map.entry("mode_t", "int"),
            Map.entry("off_t", "long"),
            Map.entry("__pid_t", "int"),
            Map.entry("__uid_t", "int"),
            Map.entry("__gid_t", "int"),
            Map.entry("__mode_t", "int"),
            Map.entry("__off_t", "long")
    );

    private static final Map<String, String> PRIMITIVES = Map.ofEntries(
            Map.entry("void", "void"),
            Map.entry("_Bool", "boolean"),
            Map.entry("char", "byte"),
            Map.entry("signed char", "byte"),
            Map.entry("unsigned char", "byte"),
            Map.entry("short", "short"),
            Map.entry("unsigned short", "short"),
            Map.entry("int", "int"),
            Map.entry("unsigned int", "int"),
            Map.entry("unsigned", "int"),
            Map.entry("long", "long"),
            Map.entry("unsigned long", "long"),
            Map.entry("long long", "long"),
            Map.entry("unsigned long long", "long"),
            Map.entry("float", "float"),
            Map.entry("double", "double"),
            Map.entry("long double", "double")
    );

    /** Known function pointer typedef names (mapped to PascalCase interfaces). */
    private final Set<String> functionPointerNames;

    public TypeMapper() {
        this(Set.of());
    }

    public TypeMapper(Set<String> functionPointerNames) {
        this.functionPointerNames = functionPointerNames;
    }

    public String map(String qualType) {
        var qt = qualType.strip();
        qt = stripQualifiers(qt);

        // Function pointer typedef → generated interface name.
        if (functionPointerNames.contains(qt)) {
            return toPascalCase(qt);
        }

        // Word-sized types.
        var word = WORD_TYPES.get(qt);
        if (word != null) return word;

        // Fixed-width types.
        var fw = FIXED_WIDTH.get(qt);
        if (fw != null) return fw;

        // Pointer types.
        if (qt.endsWith("*")) {
            return mapPointer(qt);
        }

        // Array types: "type [N]" → pointer.
        if (qt.contains("[")) {
            var elemType = qt.substring(0, qt.indexOf('[')).strip();
            return mapPointerToElem(elemType);
        }

        // Struct/union reference.
        if (qt.startsWith("struct ")) return toPascalCase(qt.substring(7).strip());
        if (qt.startsWith("union ")) return toPascalCase(qt.substring(6).strip());

        // Enum → int.
        if (qt.startsWith("enum ")) return "int";

        // Primitive.
        var prim = PRIMITIVES.get(qt);
        if (prim != null) return prim;

        // Function pointer: "type (*)(params)".
        if (qt.contains("(*)")) return "PointerBase";

        // Final fallback for FIXED_WIDTH.
        fw = FIXED_WIDTH.get(qt.strip());
        if (fw != null) return fw;

        return "PointerBase";
    }

    /**
     * Check whether a qualType denotes a struct value (not a pointer).
     * Used to warn about by-value struct returns.
     */
    public boolean isStructByValue(String qualType) {
        var qt = stripQualifiers(qualType.strip());
        return qt.startsWith("struct ") && !qt.endsWith("*");
    }

    private String mapPointer(String qt) {
        var pointee = qt.substring(0, qt.lastIndexOf('*')).strip();
        pointee = stripQualifiers(pointee);
        return mapPointerToElem(pointee);
    }

    private String mapPointerToElem(String pointee) {
        pointee = stripQualifiers(pointee);

        if (pointee.equals("void")) return "VoidPointer";

        if (pointee.equals("char") || pointee.equals("signed char") ||
                pointee.equals("unsigned char")) {
            return "CCharPointer";
        }

        // Fixed-width pointer: uint8_t* → CCharPointer, int32_t* → CIntPointer
        var fw = FIXED_WIDTH.get(pointee);
        if (fw != null) {
            return switch (fw) {
                case "byte" -> "CCharPointer";
                case "short" -> "CShortPointer";
                case "int" -> "CIntPointer";
                case "long" -> "CLongPointer";
                default -> "PointerBase";
            };
        }

        // char **
        if (pointee.endsWith("*")) {
            var inner = stripQualifiers(
                    pointee.substring(0, pointee.lastIndexOf('*')).strip());
            if (inner.equals("char") || inner.equals("signed char") ||
                    inner.equals("unsigned char")) {
                return "CCharPointerPointer";
            }
            // struct foo ** → StructPointer (handled via @CPointerTo)
            if (inner.startsWith("struct ")) {
                return toPascalCase(inner.substring(7).strip()) + "Pointer";
            }
        }

        // struct * → interface type
        if (pointee.startsWith("struct "))
            return toPascalCase(pointee.substring(7).strip());
        if (pointee.startsWith("union "))
            return toPascalCase(pointee.substring(6).strip());

        return switch (pointee) {
            case "int", "unsigned int", "unsigned" -> "CIntPointer";
            case "short", "unsigned short" -> "CShortPointer";
            case "long", "unsigned long", "long long", "unsigned long long" -> "CLongPointer";
            case "float" -> "CFloatPointer";
            case "double" -> "CDoublePointer";
            default -> "PointerBase";
        };
    }

    static String stripQualifiers(String type) {
        var t = type;
        while (true) {
            if (t.startsWith("const ")) { t = t.substring(6); continue; }
            if (t.startsWith("volatile ")) { t = t.substring(9); continue; }
            if (t.startsWith("restrict ")) { t = t.substring(9); continue; }
            if (t.endsWith(" const")) { t = t.substring(0, t.length() - 6); continue; }
            break;
        }
        return t.strip();
    }

    public static String toPascalCase(String name) {
        if (name.isEmpty()) return name;
        if (!name.contains("_") && Character.isUpperCase(name.charAt(0))) return name;
        var sb = new StringBuilder();
        for (var part : name.split("_")) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) sb.append(part.substring(1));
            }
        }
        return sb.toString();
    }

    public static String toCamelCase(String name) {
        var pascal = toPascalCase(name);
        if (pascal.isEmpty()) return pascal;
        return Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    }

    /**
     * Extract the struct name from a double-pointer qualType, e.g.
     * "struct point **" → "point", or null if not a double-pointer-to-struct.
     */
    public static String doublePointerStructName(String qualType) {
        var qt = stripQualifiers(qualType.strip());
        if (!qt.endsWith("**")) return null;
        var base = stripQualifiers(qt.substring(0, qt.length() - 2).strip());
        if (base.startsWith("struct ")) return base.substring(7).strip();
        if (base.startsWith("union ")) return base.substring(6).strip();
        return null;
    }
}
