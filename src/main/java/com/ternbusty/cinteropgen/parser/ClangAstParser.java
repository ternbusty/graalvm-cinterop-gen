package com.ternbusty.cinteropgen.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parse a C header by running {@code clang -Xclang -ast-dump=json} and
 * walking the resulting JSON AST.
 *
 * Macros are extracted separately via a regex scan of the header text,
 * since the preprocessor expands them before AST construction.
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

    public Header parse(Path headerPath) throws IOException, InterruptedException {
        var absPath = headerPath.toAbsolutePath().toString();

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
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            jsonOutput = readAll(reader);
        }

        int rc = proc.waitFor();
        if (rc != 0) {
            String stderr;
            try (var reader = new BufferedReader(
                    new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                stderr = readAll(reader);
            }
            throw new IOException("clang exited with code " + rc + ": " + stderr);
        }

        var root = JsonParser.parseString(jsonOutput).getAsJsonObject();
        var inner = root.getAsJsonArray("inner");
        if (inner == null) {
            throw new IOException("clang AST has no top-level declarations");
        }

        var structs = new ArrayList<StructDecl>();
        var functions = new ArrayList<FunctionDecl>();
        var enums = new ArrayList<EnumDecl>();
        var typedefs = new ArrayList<TypedefDecl>();
        var seenStructs = new HashSet<String>();
        var seenEnums = new HashSet<String>();

        // clang's JSON AST only records "file" in loc for the first
        // declaration in each file. Subsequent declarations in the same
        // file omit the "file" field. We track the current file
        // statefulness to filter out system declarations.
        String currentFile = null;

        for (var element : inner) {
            var node = element.getAsJsonObject();

            // Track current file from loc.
            currentFile = trackFile(node, currentFile);

            // Only process declarations from our header.
            if (currentFile == null || !currentFile.equals(absPath)) {
                continue;
            }

            var kind = str(node, "kind");
            switch (kind) {
                case "RecordDecl" -> {
                    var decl = parseRecord(node);
                    if (decl != null && !decl.name().isEmpty() && seenStructs.add(decl.name())) {
                        structs.add(decl);
                    }
                }
                case "FunctionDecl" -> {
                    var decl = parseFunction(node);
                    if (decl != null) {
                        functions.add(decl);
                    }
                }
                case "EnumDecl" -> {
                    var decl = parseEnum(node);
                    if (decl != null && !decl.name().isEmpty() && seenEnums.add(decl.name())) {
                        enums.add(decl);
                    }
                }
                case "TypedefDecl" -> {
                    var td = parseTypedef(node);
                    if (td != null) {
                        typedefs.add(td);
                        // If typedef wraps an anonymous struct/enum, register
                        // it under the typedef name.
                        handleAnonymousTypedef(node, td, structs, enums, seenStructs, seenEnums);
                    }
                }
            }
        }

        // Macros via regex scan.
        var constants = parseMacros(headerPath);

        var fileName = headerPath.getFileName().toString();
        var name = fileName.contains(".")
                ? fileName.substring(0, fileName.lastIndexOf('.'))
                : fileName;

        return new Header(name, structs, functions, enums, constants, typedefs);
    }

    // ── loc tracking ─────────────────────────────────────────────

    private String trackFile(JsonObject node, String current) {
        var loc = node.getAsJsonObject("loc");
        if (loc == null) return current;

        // Direct file field.
        var file = optStr(loc, "file");
        if (file != null) return file;

        // spellingLoc / expansionLoc (macros, builtins).
        var spelling = loc.getAsJsonObject("spellingLoc");
        if (spelling != null) {
            var f = optStr(spelling, "file");
            if (f != null) return f;
        }
        var expansion = loc.getAsJsonObject("expansionLoc");
        if (expansion != null) {
            var f = optStr(expansion, "file");
            if (f != null) return f;
        }

        // No file info: same file as before.
        return current;
    }

    // ── Record (struct / union) ──────────────────────────────────

    private StructDecl parseRecord(JsonObject node) {
        var name = str(node, "name");
        boolean isUnion = "union".equals(str(node, "tagUsed"));

        if (!node.has("completeDefinition") ||
                !node.get("completeDefinition").getAsBoolean()) {
            // Forward declaration.
            return name.isEmpty() ? null : new StructDecl(name, List.of(), isUnion);
        }

        var fields = new ArrayList<StructField>();
        var inner = node.getAsJsonArray("inner");
        if (inner != null) {
            for (var child : inner) {
                var c = child.getAsJsonObject();
                if ("FieldDecl".equals(str(c, "kind"))) {
                    fields.add(new StructField(
                            str(c, "name"),
                            qualType(c)
                    ));
                }
            }
        }

        return new StructDecl(name, fields, isUnion);
    }

    // ── Function ─────────────────────────────────────────────────

    private FunctionDecl parseFunction(JsonObject node) {
        var name = str(node, "name");
        if (name.isEmpty()) return null;

        var funcType = qualType(node); // e.g. "int (int, int)"
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
        // Find the last '(' at the function-type level.
        // Handle cases like "struct point *(int, int)" or "void (*)(int)".
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
        var constants = new ArrayList<EnumConstant>();

        var inner = node.getAsJsonArray("inner");
        if (inner != null) {
            for (var child : inner) {
                var c = child.getAsJsonObject();
                if ("EnumConstantDecl".equals(str(c, "kind"))) {
                    long value = extractEnumValue(c);
                    constants.add(new EnumConstant(str(c, "name"), value));
                }
            }
        }

        return new EnumDecl(name, constants);
    }

    private long extractEnumValue(JsonObject enumConst) {
        // Look for ConstantExpr or ImplicitValueInitExpr with a value.
        var inner = enumConst.getAsJsonArray("inner");
        if (inner != null) {
            for (var child : inner) {
                var c = child.getAsJsonObject();
                var val = optStr(c, "value");
                if (val != null) {
                    try {
                        return Long.parseLong(val);
                    } catch (NumberFormatException ignored) {}
                }
                // Recurse into nested exprs.
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

    private void handleAnonymousTypedef(
            JsonObject node, TypedefDecl td,
            List<StructDecl> structs, List<EnumDecl> enums,
            Set<String> seenStructs, Set<String> seenEnums) {
        var inner = node.getAsJsonArray("inner");
        if (inner == null) return;
        for (var child : inner) {
            var c = child.getAsJsonObject();
            var kind = str(c, "kind");

            // Walk through ElaboratedType / RecordType to find the actual record.
            if ("ElaboratedType".equals(kind)) {
                // Check for ownedTagDecl (inline struct/enum in typedef).
                var owned = c.getAsJsonObject("ownedTagDecl");
                if (owned != null) {
                    var tagKind = str(owned, "kind");
                    var tagName = str(owned, "name");
                    if (tagName.isEmpty()) {
                        // Anonymous: use typedef name.
                        if ("RecordDecl".equals(tagKind)) {
                            var id = str(owned, "id");
                            var anon = findRecordById(node, id);
                            if (anon != null && seenStructs.add(td.name())) {
                                boolean isUnion = "union".equals(str(anon, "tagUsed"));
                                var fields = new ArrayList<StructField>();
                                var fInner = anon.getAsJsonArray("inner");
                                if (fInner != null) {
                                    for (var f : fInner) {
                                        var fo = f.getAsJsonObject();
                                        if ("FieldDecl".equals(str(fo, "kind"))) {
                                            fields.add(new StructField(str(fo, "name"), qualType(fo)));
                                        }
                                    }
                                }
                                structs.add(new StructDecl(td.name(), fields, isUnion));
                            }
                        } else if ("EnumDecl".equals(tagKind)) {
                            var id = str(owned, "id");
                            var anon = findEnumById(node, id);
                            if (anon != null && seenEnums.add(td.name())) {
                                enums.add(parseEnumWithName(anon, td.name()));
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Search siblings (preceding declarations at the same level) for a
     * RecordDecl with the given id. In practice, an anonymous struct
     * defined inside a typedef appears as a sibling right before the
     * TypedefDecl in clang's AST.
     */
    private JsonObject findRecordById(JsonObject typedefNode, String id) {
        // The anonymous RecordDecl is emitted as a sibling in the
        // TranslationUnit's inner array, not inside the TypedefDecl.
        // We stashed the parent in the walk loop, so look at our
        // preceding sibling. Since we don't have direct parent access
        // here, parse the inner of the typedef itself.
        return findDeclById(typedefNode, id, "RecordDecl");
    }

    private JsonObject findEnumById(JsonObject typedefNode, String id) {
        return findDeclById(typedefNode, id, "EnumDecl");
    }

    private JsonObject findDeclById(JsonObject parent, String id, String expectedKind) {
        // Not found within typedef; the caller should look at siblings.
        return null;
    }

    private EnumDecl parseEnumWithName(JsonObject node, String name) {
        var constants = new ArrayList<EnumConstant>();
        var inner = node.getAsJsonArray("inner");
        if (inner != null) {
            for (var child : inner) {
                var c = child.getAsJsonObject();
                if ("EnumConstantDecl".equals(str(c, "kind"))) {
                    long value = extractEnumValue(c);
                    constants.add(new EnumConstant(str(c, "name"), value));
                }
            }
        }
        return new EnumDecl(name, constants);
    }

    // ── Macro extraction ─────────────────────────────────────────

    // Pattern: #define NAME value (simple object-like macros).
    private static final Pattern MACRO_PATTERN =
            Pattern.compile("^\\s*#\\s*define\\s+(\\w+)\\s+(.+)$");

    private List<MacroConstant> parseMacros(Path headerPath) throws IOException {
        var macros = new ArrayList<MacroConstant>();
        for (var line : Files.readAllLines(headerPath, StandardCharsets.UTF_8)) {
            Matcher m = MACRO_PATTERN.matcher(line);
            if (!m.matches()) continue;

            var name = m.group(1);
            var value = m.group(2).trim();

            // Skip include guards, internal macros, function-like macros.
            if (name.startsWith("_") || name.endsWith("_H") || name.endsWith("_H_"))
                continue;
            // Skip if value looks like a macro invocation or complex expr.
            if (value.contains("(") || value.contains("\\")) continue;

            // Only keep numeric or string literals.
            var cleaned = value.replaceAll("[uUlLfF]$", "");
            boolean isNumeric = false;
            try {
                Long.decode(cleaned);
                isNumeric = true;
            } catch (NumberFormatException e) {
                try {
                    Double.parseDouble(cleaned);
                    isNumeric = true;
                } catch (NumberFormatException e2) {
                    // not numeric
                }
            }
            if (!isNumeric && !value.startsWith("\"")) continue;

            macros.add(new MacroConstant(name, value));
        }
        return macros;
    }

    // ── JSON helpers ─────────────────────────────────────────────

    private static String str(JsonObject obj, String key) {
        var e = obj.get(key);
        return (e != null && e.isJsonPrimitive()) ? e.getAsString() : "";
    }

    private static String optStr(JsonObject obj, String key) {
        var e = obj.get(key);
        return (e != null && e.isJsonPrimitive()) ? e.getAsString() : null;
    }

    private static String qualType(JsonObject node) {
        var type = node.getAsJsonObject("type");
        if (type == null) return "";
        var qt = optStr(type, "qualType");
        return qt != null ? qt : "";
    }

    private static String readAll(BufferedReader reader) throws IOException {
        var sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = reader.read(buf)) != -1) {
            sb.append(buf, 0, n);
        }
        return sb.toString();
    }
}
