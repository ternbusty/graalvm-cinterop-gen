package com.ternbusty.cinteropgen.parser;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ternbusty.cinteropgen.ir.Header;
import com.ternbusty.cinteropgen.ir.Header.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parse a C header by running {@code clang -Xclang -ast-dump=json} and
 * walking the resulting JSON AST.
 *
 * By default every declaration reachable from the header (including
 * transitive {@code #include}s) is collected, matching jextract's
 * default behaviour. When an {@link IncludeFilter} with at least one
 * non-empty set is supplied, the parser enters whitelist mode and only
 * declarations whose names match are collected.
 *
 * Macro constants are always discovered via {@code clang -dM -E}.
 * Compiler builtins are subtracted so that only macros originating
 * from header files appear in the output.
 */
public class ClangAstParser {

    private final List<String> extraArgs;
    private final String clangBinary;

    public ClangAstParser() {
        this(List.of(), "clang");
    }

    public ClangAstParser(List<String> extraArgs, String clangBinary) {
        this.extraArgs = extraArgs;
        this.clangBinary = clangBinary;
    }

    /** Parse including everything reachable (jextract default). */
    public Header parse(Path headerPath)
            throws IOException, InterruptedException {
        return parse(headerPath, IncludeFilter.NONE);
    }

    /**
     * Parse with an optional include filter.
     *
     * When {@code filter.isSelective()} is false (default), every
     * reachable declaration is collected. When true, only declarations
     * whose names match the filter are collected.
     */
    public Header parse(Path headerPath, IncludeFilter filter)
            throws IOException, InterruptedException {
        var absPath = headerPath.toAbsolutePath().toString();
        boolean selective = filter.isSelective();

        var cmd = new ArrayList<String>();
        cmd.add(clangBinary);
        cmd.add("-Xclang");
        cmd.add("-ast-dump=json");
        cmd.add("-fsyntax-only");
        cmd.add("-x");
        cmd.add("c");
        cmd.add("-std=c11");
        cmd.addAll(extraArgs);
        cmd.add(absPath);

        var pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        var proc = pb.start();

        String jsonOutput;
        try (var reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(),
                        StandardCharsets.UTF_8))) {
            jsonOutput = readAll(reader);
        }

        int rc = proc.waitFor();
        if (rc != 0) {
            String stderr;
            try (var reader = new BufferedReader(
                    new InputStreamReader(proc.getErrorStream(),
                            StandardCharsets.UTF_8))) {
                stderr = readAll(reader);
            }
            throw new IOException("clang exited with code " + rc
                    + ": " + stderr);
        }

        var root = JsonParser.parseString(jsonOutput).getAsJsonObject();
        var inner = root.getAsJsonArray("inner");
        if (inner == null) {
            throw new IOException(
                    "clang AST has no top-level declarations");
        }

        var structs = new ArrayList<StructDecl>();
        var functions = new ArrayList<FunctionDecl>();
        var enums = new ArrayList<EnumDecl>();
        var typedefs = new ArrayList<TypedefDecl>();
        var functionPointers = new ArrayList<FunctionPointerDecl>();
        var seenStructs = new HashSet<String>();
        var seenEnums = new HashSet<String>();
        var seenFunctions = new HashSet<String>();

        // Pre-build id→node map for anonymous typedef resolution.
        var nodeById = new HashMap<String, JsonObject>();
        for (var element : inner) {
            var node = element.getAsJsonObject();
            var id = optStr(node, "id");
            if (id != null) {
                nodeById.put(id, node);
            }
        }

        for (var element : inner) {
            var node = element.getAsJsonObject();
            var kind = str(node, "kind");

            switch (kind) {
                case "RecordDecl" -> {
                    var decl = parseRecord(node);
                    if (decl != null && !decl.name().isEmpty()
                            && seenStructs.add(decl.name())) {
                        if (!selective || matchesRecord(filter, decl)) {
                            structs.add(decl);
                        }
                    }
                }
                case "FunctionDecl" -> {
                    var decl = parseFunction(node);
                    if (decl != null
                            && seenFunctions.add(decl.name())) {
                        if (!selective
                                || filter.matchesFunction(decl.name())) {
                            functions.add(decl);
                        }
                    }
                }
                case "EnumDecl" -> {
                    var decl = parseEnum(node);
                    if (decl != null && !decl.name().isEmpty()
                            && seenEnums.add(decl.name())) {
                        if (!selective
                                || filter.matchesEnum(decl.name())) {
                            enums.add(decl);
                        }
                    }
                }
                case "TypedefDecl" -> {
                    var td = parseTypedef(node);
                    if (td != null) {
                        boolean tdIncluded = !selective
                                || filter.matchesTypedef(td.name());

                        // Detect function pointer typedef.
                        var fp = detectFunctionPointerTypedef(td);
                        if (fp != null && tdIncluded) {
                            functionPointers.add(fp);
                        }

                        if (tdIncluded) {
                            typedefs.add(td);
                        }

                        // Anonymous typedef struct/enum: check against
                        // struct/enum filters (the typedef name becomes
                        // the struct/enum name).
                        handleAnonymousTypedef(
                                node, td, nodeById, filter, selective,
                                structs, enums, seenStructs, seenEnums);
                    }
                }
            }
        }

        // Macros.
        var constants = extractMacros(headerPath, filter);

        var fileName = headerPath.getFileName().toString();
        var name = fileName.contains(".")
                ? fileName.substring(0, fileName.lastIndexOf('.'))
                : fileName;

        return new Header(name, structs, functions, enums, constants,
                typedefs, functionPointers);
    }

    /** Check whether a struct/union declaration passes the filter. */
    private static boolean matchesRecord(
            IncludeFilter filter, StructDecl decl) {
        return decl.isUnion()
                ? filter.matchesUnion(decl.name())
                : filter.matchesStruct(decl.name());
    }

    // ── Record (struct / union) ──────────────────────────────────

    private StructDecl parseRecord(JsonObject node) {
        var name = str(node, "name");
        boolean isUnion = "union".equals(str(node, "tagUsed"));

        if (!node.has("completeDefinition")
                || !node.get("completeDefinition").getAsBoolean()) {
            return name.isEmpty()
                    ? null
                    : new StructDecl(name, List.of(), isUnion);
        }

        var fields = parseFields(node);
        return new StructDecl(name, fields, isUnion);
    }

    private List<StructField> parseFields(JsonObject recordNode) {
        var fields = new ArrayList<StructField>();
        var inner = recordNode.getAsJsonArray("inner");
        if (inner != null) {
            for (var child : inner) {
                var c = child.getAsJsonObject();
                if ("FieldDecl".equals(str(c, "kind"))) {
                    boolean isBitfield = c.has("isBitfield")
                            && c.get("isBitfield").getAsBoolean();
                    int bitWidth = 0;
                    if (isBitfield) {
                        bitWidth = extractBitWidth(c);
                    }
                    fields.add(new StructField(
                            str(c, "name"),
                            qualType(c),
                            isBitfield,
                            bitWidth
                    ));
                }
            }
        }
        return fields;
    }

    private int extractBitWidth(JsonObject fieldDecl) {
        var inner = fieldDecl.getAsJsonArray("inner");
        if (inner == null) return 0;
        for (var child : inner) {
            var c = child.getAsJsonObject();
            var val = optStr(c, "value");
            if (val != null) {
                try {
                    return Integer.parseInt(val);
                } catch (NumberFormatException ignored) {
                }
            }
            int nested = extractBitWidth(c);
            if (nested > 0) return nested;
        }
        return 0;
    }

    // ── Function ─────────────────────────────────────────────────

    private FunctionDecl parseFunction(JsonObject node) {
        var name = str(node, "name");
        if (name.isEmpty()) return null;

        var funcType = qualType(node);
        var retType = extractReturnType(funcType);
        boolean variadic = funcType.endsWith("...)");

        var params = new ArrayList<FunctionParam>();
        var inner = node.getAsJsonArray("inner");
        if (inner != null) {
            int idx = 0;
            for (var child : inner) {
                var c = child.getAsJsonObject();
                if ("ParmVarDecl".equals(str(c, "kind"))) {
                    var pName = str(c, "name");
                    if (pName.isEmpty()) pName = "arg" + idx;
                    params.add(new FunctionParam(pName, qualType(c)));
                    idx++;
                }
            }
        }

        return new FunctionDecl(name, retType, params, variadic);
    }

    /**
     * Extract the return type from a function's qualType string.
     * Format: "return_type (param_types)".
     */
    public static String extractReturnType(String funcQualType) {
        int depth = 0;
        for (int i = funcQualType.length() - 1; i >= 0; i--) {
            char ch = funcQualType.charAt(i);
            if (ch == ')') depth++;
            else if (ch == '(') {
                depth--;
                if (depth == 0) {
                    return funcQualType.substring(0, i).trim();
                }
            }
        }
        return funcQualType;
    }

    // ── Enum ─────────────────────────────────────────────────────

    private EnumDecl parseEnum(JsonObject node) {
        var name = str(node, "name");
        var constants = parseEnumConstants(node);
        return new EnumDecl(name, constants);
    }

    private List<EnumConstant> parseEnumConstants(JsonObject enumNode) {
        var constants = new ArrayList<EnumConstant>();
        var inner = enumNode.getAsJsonArray("inner");
        if (inner != null) {
            for (var child : inner) {
                var c = child.getAsJsonObject();
                if ("EnumConstantDecl".equals(str(c, "kind"))) {
                    long value = extractEnumValue(c);
                    constants.add(
                            new EnumConstant(str(c, "name"), value));
                }
            }
        }
        return constants;
    }

    private long extractEnumValue(JsonObject enumConst) {
        var inner = enumConst.getAsJsonArray("inner");
        if (inner != null) {
            for (var child : inner) {
                var c = child.getAsJsonObject();
                var val = optStr(c, "value");
                if (val != null) {
                    try {
                        return Long.parseLong(val);
                    } catch (NumberFormatException ignored) {
                    }
                }
                long nested = extractEnumValue(c);
                if (nested != Long.MIN_VALUE) return nested;
            }
        }
        return Long.MIN_VALUE;
    }

    // ── Typedef ──────────────────────────────────────────────────

    private TypedefDecl parseTypedef(JsonObject node) {
        var name = str(node, "name");
        if (name.isEmpty()) return null;
        return new TypedefDecl(name, qualType(node));
    }

    /**
     * If a typedef has an ownedTagDecl pointing to an anonymous
     * struct/enum, register the anonymous type under the typedef name.
     */
    private void handleAnonymousTypedef(
            JsonObject typedefNode, TypedefDecl td,
            Map<String, JsonObject> nodeById,
            IncludeFilter filter, boolean selective,
            List<StructDecl> structs, List<EnumDecl> enums,
            Set<String> seenStructs, Set<String> seenEnums) {
        var inner = typedefNode.getAsJsonArray("inner");
        if (inner == null) return;

        for (var child : inner) {
            var c = child.getAsJsonObject();
            var kind = str(c, "kind");

            if ("ElaboratedType".equals(kind)) {
                var owned = c.getAsJsonObject("ownedTagDecl");
                if (owned == null) continue;

                var tagKind = str(owned, "kind");
                var tagName = str(owned, "name");
                if (!tagName.isEmpty()) continue;

                var tagId = str(owned, "id");
                if (tagId.isEmpty()) continue;

                var fullDecl = nodeById.get(tagId);
                if (fullDecl == null) continue;

                if ("RecordDecl".equals(tagKind)
                        && seenStructs.add(td.name())) {
                    boolean isUnion = "union".equals(
                            str(fullDecl, "tagUsed"));
                    boolean matches;
                    if (!selective) {
                        matches = true;
                    } else {
                        matches = isUnion
                                ? filter.matchesUnion(td.name())
                                : filter.matchesStruct(td.name());
                    }
                    if (matches) {
                        var fields = parseFields(fullDecl);
                        structs.add(new StructDecl(
                                td.name(), fields, isUnion));
                    }
                } else if ("EnumDecl".equals(tagKind)
                        && seenEnums.add(td.name())) {
                    if (!selective
                            || filter.matchesEnum(td.name())) {
                        var constants = parseEnumConstants(fullDecl);
                        enums.add(new EnumDecl(td.name(), constants));
                    }
                }
            }
        }
    }

    // ── Function pointer typedef detection ───────────────────────

    private FunctionPointerDecl detectFunctionPointerTypedef(
            TypedefDecl td) {
        var qt = td.underlyingQualType();
        int parenStar = qt.indexOf("(*)");
        if (parenStar < 0) return null;

        var retType = qt.substring(0, parenStar).strip();
        var rest = qt.substring(parenStar + 3).strip();

        if (!rest.startsWith("(") || !rest.endsWith(")")) return null;
        var paramStr = rest.substring(1, rest.length() - 1).strip();

        var params = new ArrayList<FunctionParam>();
        if (!paramStr.isEmpty() && !paramStr.equals("void")) {
            var paramTypes = splitByTopLevelComma(paramStr);
            for (int i = 0; i < paramTypes.size(); i++) {
                params.add(new FunctionParam(
                        "arg" + i, paramTypes.get(i).strip()));
            }
        }

        return new FunctionPointerDecl(td.name(), retType, params);
    }

    static List<String> splitByTopLevelComma(String s) {
        var result = new ArrayList<String>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                result.add(s.substring(start, i).strip());
                start = i + 1;
            }
        }
        result.add(s.substring(start).strip());
        return result;
    }

    // ── Macro extraction (unified, via clang -dM -E) ────────────

    /**
     * Object-like macro line from {@code clang -dM -E}.
     * Function-like macros ({@code #define NAME(args) ...}) have no
     * whitespace between the name and {@code (}, so they do not match.
     */
    private static final Pattern DM_MACRO =
            Pattern.compile("#define\\s+(\\w+)\\s+(.+)");

    /**
     * Extract macro constants using the preprocessor.
     *
     * In default mode (no filter), all macros from the header chain
     * are collected after subtracting compiler builtins. In selective
     * mode, only macros matching the filter are collected.
     *
     * The resolution flow has two phases.
     *
     * Phase 1 runs {@code clang -dM -E} to discover all defined
     * object-like macros and attempts to resolve values in-memory
     * (direct numeric/float literals, string literals, and
     * single-identifier indirection through the macro map).
     *
     * Phase 2 generates a temporary C file that assigns remaining
     * unresolved macros to {@code static const} variables, then parses
     * the resulting clang AST to read the compiler-evaluated values.
     */
    private List<MacroConstant> extractMacros(
            Path headerPath, IncludeFilter filter)
            throws IOException, InterruptedException {
        boolean selective = filter.isSelective();

        // In selective mode with no constant filter, skip entirely.
        if (selective && filter.constants().isEmpty()) {
            return List.of();
        }

        // Discover all macros via clang -dM -E.
        var macroMap = discoverMacros(headerPath);

        // In default mode, subtract compiler builtins so that only
        // macros originating from header files remain.
        if (!selective) {
            var builtins = discoverBuiltinMacros();
            for (var key : builtins.keySet()) {
                macroMap.remove(key);
            }
        }

        // Collect matching names.
        var matching = new ArrayList<String>();
        for (var name : macroMap.keySet()) {
            // Always skip internal names and include guards.
            if (name.startsWith("_")
                    || name.endsWith("_H")
                    || name.endsWith("_H_"))
                continue;

            if (selective) {
                if (filter.matchesConstant(name)) {
                    matching.add(name);
                }
            } else {
                matching.add(name);
            }
        }

        if (matching.isEmpty()) return List.of();

        // Phase 1: try in-memory resolution.
        var resolved = new ArrayList<MacroConstant>();
        var unresolved = new ArrayList<String>();

        for (var name : matching) {
            var value = resolveInMemory(macroMap, name, new HashSet<>());
            if (value != null) {
                resolved.add(new MacroConstant(name, value));
            } else {
                unresolved.add(name);
            }
        }

        // Phase 2: resolve remaining via temp file + clang AST.
        if (!unresolved.isEmpty()) {
            var phase2 = resolveViaClangAst(
                    headerPath, unresolved, macroMap);
            resolved.addAll(phase2);
        }

        return resolved;
    }

    /**
     * Run {@code clang -dM -E} and return a map of all defined
     * object-like macros (name → raw value string).
     */
    private Map<String, String> discoverMacros(Path headerPath)
            throws IOException, InterruptedException {
        var cmd = new ArrayList<String>();
        cmd.add(clangBinary);
        cmd.add("-dM");
        cmd.add("-E");
        cmd.add("-x");
        cmd.add("c");
        cmd.add("-std=c11");
        cmd.addAll(extraArgs);
        cmd.add(headerPath.toAbsolutePath().toString());

        var pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        var proc = pb.start();

        String output;
        try (var reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(),
                        StandardCharsets.UTF_8))) {
            output = readAll(reader);
        }
        proc.waitFor();

        var map = new LinkedHashMap<String, String>();
        for (var line : output.split("\\R")) {
            var m = DM_MACRO.matcher(line);
            if (m.find()) {
                map.put(m.group(1), m.group(2).strip());
            }
        }
        return map;
    }

    /**
     * Discover compiler builtin macros by preprocessing an empty
     * source. The result is used to subtract builtins from the full
     * set discovered via the actual header file.
     */
    private Map<String, String> discoverBuiltinMacros()
            throws IOException, InterruptedException {
        var emptyFile = Files.createTempFile("cig-empty-", ".c");
        try {
            Files.writeString(emptyFile, "");
            return discoverMacros(emptyFile);
        } finally {
            Files.deleteIfExists(emptyFile);
        }
    }

    /**
     * Try to resolve a macro value in-memory.
     *
     * Handles direct integer/float literals (with optional C suffixes),
     * string literals, and single-identifier indirection through the
     * macro map.
     *
     * Returns the resolved value string, or null if unresolvable.
     */
    private String resolveInMemory(
            Map<String, String> macroMap, String name,
            Set<String> visited) {
        if (!visited.add(name)) return null;
        var raw = macroMap.get(name);
        if (raw == null) return null;

        raw = raw.strip();

        // String literals.
        if (raw.startsWith("\"")) return raw;

        // Strip outer parentheses.
        var unwrapped = raw;
        while (unwrapped.startsWith("(") && unwrapped.endsWith(")")) {
            unwrapped = unwrapped.substring(1,
                    unwrapped.length() - 1).strip();
        }

        // Try as direct integer literal.
        var numericValue = tryParseNumeric(unwrapped);
        if (numericValue != null) return numericValue;

        // Try as floating-point literal.
        var floatCleaned = unwrapped.replaceAll("[fFlL]+$", "");
        try {
            Double.parseDouble(floatCleaned);
            return unwrapped;
        } catch (NumberFormatException ignored) {
        }

        // Try as single identifier (macro indirection).
        if (unwrapped.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return resolveInMemory(macroMap, unwrapped, visited);
        }

        return null;
    }

    private static final Pattern NUMERIC_LITERAL =
            Pattern.compile(
                    "^[+-]?(?:0[xX][0-9a-fA-F]+|0[0-7]*|[1-9]\\d*|0)"
                            + "[uUlL]*$");

    /**
     * Try to parse a string as a C numeric literal.
     * Returns the cleaned numeric string (suffix stripped), or null.
     */
    static String tryParseNumeric(String s) {
        if (!NUMERIC_LITERAL.matcher(s).matches()) return null;
        var cleaned = s.replaceAll("[uUlLfF]+$", "");
        try {
            Long.decode(cleaned);
            return cleaned;
        } catch (NumberFormatException e) {
            try {
                // Handle unsigned values > Long.MAX_VALUE.
                Long.parseUnsignedLong(
                        cleaned.startsWith("0x") || cleaned.startsWith("0X")
                                ? cleaned.substring(2) : cleaned,
                        cleaned.startsWith("0x") || cleaned.startsWith("0X")
                                ? 16 : 10);
                return cleaned;
            } catch (NumberFormatException e2) {
                return null;
            }
        }
    }

    /**
     * Resolve macro values by generating a temporary C file and parsing
     * it with clang.
     *
     * Each unresolved macro becomes a
     * {@code static const unsigned long long} variable. Macros that
     * expand to non-integer expressions are silently skipped (the temp
     * file compilation is best-effort).
     */
    private List<MacroConstant> resolveViaClangAst(
            Path headerPath, List<String> names,
            Map<String, String> macroMap)
            throws IOException, InterruptedException {
        // Pre-filter: skip macros whose raw values look non-numeric
        // (strings, function calls, type expressions).
        var candidates = new ArrayList<String>();
        for (var name : names) {
            var raw = macroMap.getOrDefault(name, "");
            if (raw.startsWith("\"") || raw.contains("__attribute__")) {
                continue;
            }
            candidates.add(name);
        }
        if (candidates.isEmpty()) return List.of();

        // Generate temp file.
        var tempDir = Files.createTempDirectory("cinterop-gen");
        var tempFile = tempDir.resolve("__resolve.c");
        try {
            var sb = new StringBuilder();
            sb.append("#include \"")
                    .append(headerPath.toAbsolutePath())
                    .append("\"\n");
            for (int i = 0; i < candidates.size(); i++) {
                sb.append("static const unsigned long long __cig_")
                        .append(i).append(" = (unsigned long long)(")
                        .append(candidates.get(i)).append(");\n");
            }
            Files.writeString(tempFile, sb.toString());

            // Run clang AST dump on temp file.
            var cmd = new ArrayList<String>();
            cmd.add(clangBinary);
            cmd.add("-Xclang");
            cmd.add("-ast-dump=json");
            cmd.add("-fsyntax-only");
            cmd.add("-x");
            cmd.add("c");
            cmd.add("-std=c11");
            cmd.addAll(extraArgs);
            cmd.add(tempFile.toAbsolutePath().toString());

            var pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            var proc = pb.start();

            String jsonOutput;
            try (var reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(),
                            StandardCharsets.UTF_8))) {
                jsonOutput = readAll(reader);
            }

            int rc = proc.waitFor();
            if (rc != 0) {
                // Compilation failed; some macros may be non-integer.
                // Skip phase 2 silently.
                return List.of();
            }

            // Extract values from VarDecl nodes.
            var root = JsonParser.parseString(jsonOutput)
                    .getAsJsonObject();
            var inner = root.getAsJsonArray("inner");
            if (inner == null) return List.of();

            var resolved = new ArrayList<MacroConstant>();
            for (var element : inner) {
                var node = element.getAsJsonObject();
                if (!"VarDecl".equals(str(node, "kind"))) continue;
                var varName = str(node, "name");
                if (!varName.startsWith("__cig_")) continue;

                int idx;
                try {
                    idx = Integer.parseInt(varName.substring(6));
                } catch (NumberFormatException e) {
                    continue;
                }
                if (idx < 0 || idx >= candidates.size()) continue;

                long value = extractEnumValue(node);
                if (value != Long.MIN_VALUE) {
                    resolved.add(new MacroConstant(
                            candidates.get(idx),
                            String.valueOf(value)));
                }
            }
            return resolved;
        } finally {
            Files.deleteIfExists(tempFile);
            Files.deleteIfExists(tempDir);
        }
    }

    // ── JSON helpers ─────────────────────────────────────────────

    private static String str(JsonObject obj, String key) {
        var e = obj.get(key);
        return (e != null && e.isJsonPrimitive())
                ? e.getAsString() : "";
    }

    private static String optStr(JsonObject obj, String key) {
        var e = obj.get(key);
        return (e != null && e.isJsonPrimitive())
                ? e.getAsString() : null;
    }

    private static String qualType(JsonObject node) {
        var type = node.getAsJsonObject("type");
        if (type == null) return "";
        var qt = optStr(type, "qualType");
        return qt != null ? qt : "";
    }

    private static String readAll(BufferedReader reader)
            throws IOException {
        var sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = reader.read(buf)) != -1) {
            sb.append(buf, 0, n);
        }
        return sb.toString();
    }
}
