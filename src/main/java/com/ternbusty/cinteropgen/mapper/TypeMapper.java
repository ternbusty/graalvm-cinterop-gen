package com.ternbusty.cinteropgen.mapper;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Map C type strings (qualType from clang's AST) to GraalVM CInterop
 * Java type names.
 *
 * The mapping follows SubstrateVM's CInterop documentation.
 */
public class TypeMapper {

    // Known word-sized typedefs.
    private static final Map<String, String> WORD_TYPES = Map.of(
            "size_t", "UnsignedWord",
            "ssize_t", "SignedWord",
            "uintptr_t", "UnsignedWord",
            "intptr_t", "SignedWord",
            "ptrdiff_t", "SignedWord"
    );

    // Fixed-width integer typedefs.
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

    // Primitive C type names → Java.
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
            Map.entry("long", "long"),
            Map.entry("unsigned long", "long"),
            Map.entry("long long", "long"),
            Map.entry("unsigned long long", "long"),
            Map.entry("float", "float"),
            Map.entry("double", "double"),
            Map.entry("long double", "double")
    );

    /**
     * Map a C qualType string to its GraalVM CInterop Java type name.
     */
    public String map(String qualType) {
        var qt = qualType.strip();

        // Strip leading "const " / "volatile " / "restrict ".
        qt = stripQualifiers(qt);

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

        // Array types: "type [N]" → treat as pointer.
        if (qt.contains("[")) {
            var elemType = qt.substring(0, qt.indexOf('[')).strip();
            return mapPointerToElem(elemType);
        }

        // Struct/union reference.
        if (qt.startsWith("struct ")) {
            return toPascalCase(qt.substring(7).strip());
        }
        if (qt.startsWith("union ")) {
            return toPascalCase(qt.substring(6).strip());
        }

        // Enum → int.
        if (qt.startsWith("enum ")) {
            return "int";
        }

        // Primitive.
        var prim = PRIMITIVES.get(qt);
        if (prim != null) return prim;

        // Function pointer: "type (*)(params)" or similar.
        if (qt.contains("(*)")) {
            return "PointerBase";
        }

        // Unknown typedef / type.  Try FIXED_WIDTH one more time
        // after stripping any trailing space.
        fw = FIXED_WIDTH.get(qt.strip());
        if (fw != null) return fw;

        // Default to PointerBase for unknown.
        return "PointerBase";
    }

    private String mapPointer(String qt) {
        // Remove trailing * and strip.
        var pointee = qt.substring(0, qt.lastIndexOf('*')).strip();
        pointee = stripQualifiers(pointee);

        return mapPointerToElem(pointee);
    }

    private String mapPointerToElem(String pointee) {
        pointee = stripQualifiers(pointee);

        // void * → VoidPointer
        if (pointee.equals("void")) return "VoidPointer";

        // char * → CCharPointer
        if (pointee.equals("char") || pointee.equals("signed char") ||
                pointee.equals("unsigned char")) {
            return "CCharPointer";
        }

        // uint8_t * → CCharPointer (byte-sized fixed-width types)
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

        // char ** → CCharPointerPointer
        if (pointee.endsWith("*")) {
            var inner = pointee.substring(0, pointee.lastIndexOf('*')).strip();
            inner = stripQualifiers(inner);
            if (inner.equals("char") || inner.equals("signed char") ||
                    inner.equals("unsigned char")) {
                return "CCharPointerPointer";
            }
        }

        // struct * → struct type (in CInterop, pointer to struct IS the interface type)
        if (pointee.startsWith("struct ")) {
            return toPascalCase(pointee.substring(7).strip());
        }
        if (pointee.startsWith("union ")) {
            return toPascalCase(pointee.substring(6).strip());
        }

        // Typed pointers for common primitives.
        return switch (pointee) {
            case "int", "unsigned int" -> "CIntPointer";
            case "short", "unsigned short" -> "CShortPointer";
            case "long", "unsigned long", "long long", "unsigned long long" -> "CLongPointer";
            case "float" -> "CFloatPointer";
            case "double" -> "CDoublePointer";
            default -> "PointerBase";
        };
    }

    private static String stripQualifiers(String type) {
        var t = type;
        while (true) {
            if (t.startsWith("const ")) { t = t.substring(6); continue; }
            if (t.startsWith("volatile ")) { t = t.substring(9); continue; }
            if (t.startsWith("restrict ")) { t = t.substring(9); continue; }
            // Remove trailing const after pointer: "char *const"
            if (t.endsWith(" const")) { t = t.substring(0, t.length() - 6); continue; }
            break;
        }
        return t.strip();
    }

    /**
     * Convert a C identifier (snake_case or CamelCase) to PascalCase.
     */
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

    /**
     * Convert a C identifier to camelCase.
     */
    public static String toCamelCase(String name) {
        var pascal = toPascalCase(name);
        if (pascal.isEmpty()) return pascal;
        return Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    }
}
