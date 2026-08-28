package com.ternbusty.cinteropgen;

import com.ternbusty.cinteropgen.codegen.CodegenConfig;
import com.ternbusty.cinteropgen.codegen.JavaCodegen;
import com.ternbusty.cinteropgen.ir.Header;
import com.ternbusty.cinteropgen.parser.ClangAstParser;
import com.ternbusty.cinteropgen.parser.IncludeFilter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

public class Main {

    public static void main(String[] args) {
        // Expand @argfile arguments.
        try {
            args = expandArgFiles(args);
        } catch (IOException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(1);
            return;
        }

        if (args.length == 0
                || "--help".equals(args[0])
                || "-h".equals(args[0])) {
            printUsage();
            System.exit(args.length == 0 ? 1 : 0);
            return;
        }

        String header = null;
        String pkg = "generated";
        String output = ".";
        String className = "";
        String headerInclude = "";
        boolean emitComments = true;
        String dumpIncludesPath = null;
        var clangIncludes = new ArrayList<String>();
        var defines = new ArrayList<String>();

        var incFunctions = new LinkedHashSet<String>();
        var incStructs = new LinkedHashSet<String>();
        var incUnions = new LinkedHashSet<String>();
        var incEnums = new LinkedHashSet<String>();
        var incTypedefs = new LinkedHashSet<String>();
        var incConstants = new LinkedHashSet<String>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-p", "--package" -> pkg = args[++i];
                case "-o", "--output" -> output = args[++i];
                case "-c", "--class-name" -> className = args[++i];
                case "--header-include" -> headerInclude = args[++i];
                case "--no-comments" -> emitComments = false;
                case "-I" -> clangIncludes.add(args[++i]);
                case "-D" -> defines.add(args[++i]);
                case "--include-function" ->
                        addCommaSeparated(incFunctions, args[++i]);
                case "--include-struct" ->
                        addCommaSeparated(incStructs, args[++i]);
                case "--include-union" ->
                        addCommaSeparated(incUnions, args[++i]);
                case "--include-enum" ->
                        addCommaSeparated(incEnums, args[++i]);
                case "--include-typedef" ->
                        addCommaSeparated(incTypedefs, args[++i]);
                case "--include-constant" ->
                        addCommaSeparated(incConstants, args[++i]);
                case "--dump-includes" ->
                        dumpIncludesPath = args[++i];
                default -> {
                    if (args[i].startsWith("-I")) {
                        clangIncludes.add(args[i].substring(2));
                    } else if (args[i].startsWith("-D")) {
                        defines.add(args[i].substring(2));
                    } else if (!args[i].startsWith("-")) {
                        header = args[i];
                    } else {
                        System.err.println(
                                "Unknown option: " + args[i]);
                        System.exit(1);
                    }
                }
            }
        }

        if (header == null) {
            System.err.println("error: no header file specified");
            System.exit(1);
            return;
        }

        var headerPath = Path.of(header);
        if (!Files.exists(headerPath)) {
            System.err.println(
                    "error: header not found: " + headerPath);
            System.exit(1);
            return;
        }

        var extraArgs = new ArrayList<String>();
        for (var inc : clangIncludes) {
            extraArgs.add("-I");
            extraArgs.add(inc);
        }
        for (var def : defines) {
            extraArgs.add("-D" + def);
        }

        try {
            var parser = new ClangAstParser(extraArgs, "clang");

            // --dump-includes: parse everything, write symbol list,
            // and exit without generating code.
            if (dumpIncludesPath != null) {
                var ir = parser.parse(headerPath);
                dumpIncludes(ir, headerPath,
                        Path.of(dumpIncludesPath));
                return;
            }

            var filter = new IncludeFilter(
                    Set.copyOf(incFunctions),
                    Set.copyOf(incStructs),
                    Set.copyOf(incUnions),
                    Set.copyOf(incEnums),
                    Set.copyOf(incTypedefs),
                    Set.copyOf(incConstants));

            var ir = parser.parse(headerPath, filter);

            var config = new CodegenConfig(
                    pkg, className, headerInclude, emitComments);
            var codegen = new JavaCodegen(config);
            var files = codegen.generate(ir);

            var outDir = Path.of(output);
            for (var entry : files.entrySet()) {
                var dest = outDir.resolve(entry.getKey());
                Files.createDirectories(dest.getParent());
                Files.writeString(dest, entry.getValue());
                System.out.println("  wrote " + dest);
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Write all discovered symbols to a file in a format that can be
     * read back via {@code @argfile}. Matches jextract's
     * {@code --dump-includes} output.
     */
    private static void dumpIncludes(
            Header ir, Path headerPath, Path outputPath)
            throws IOException {
        var sb = new StringBuilder();
        sb.append("#### Extracted from: ")
                .append(headerPath.toAbsolutePath()).append("\n\n");

        var listed = new HashSet<String>();

        for (var f : ir.functions()) {
            sb.append("--include-function ")
                    .append(f.name()).append('\n');
        }
        for (var s : ir.structs()) {
            listed.add(s.name());
            sb.append(s.isUnion()
                            ? "--include-union "
                            : "--include-struct ")
                    .append(s.name()).append('\n');
        }
        for (var e : ir.enums()) {
            listed.add(e.name());
            sb.append("--include-enum ")
                    .append(e.name()).append('\n');
        }
        for (var t : ir.typedefs()) {
            if (listed.contains(t.name())) continue;
            sb.append("--include-typedef ")
                    .append(t.name()).append('\n');
        }
        for (var c : ir.constants()) {
            sb.append("--include-constant ")
                    .append(c.name()).append('\n');
        }

        Files.writeString(outputPath, sb.toString());
        System.out.println("  dumped includes to " + outputPath);
    }

    /**
     * Expand {@code @file} arguments by reading lines from the file.
     * Lines starting with {@code #} are comments. Trailing
     * {@code # ...} comments on flag lines are stripped.
     */
    private static String[] expandArgFiles(String[] args)
            throws IOException {
        var expanded = new ArrayList<String>();
        for (var arg : args) {
            if (arg.startsWith("@")) {
                var path = Path.of(arg.substring(1));
                if (!Files.exists(path)) {
                    throw new IOException(
                            "argument file not found: " + path);
                }
                for (var line : Files.readAllLines(path)) {
                    line = line.strip();
                    if (line.isEmpty() || line.startsWith("#"))
                        continue;

                    // Strip trailing comment.
                    int commentIdx = line.indexOf('#');
                    if (commentIdx > 0) {
                        line = line.substring(0, commentIdx).strip();
                    }
                    if (line.isEmpty()) continue;

                    // Split flag and value.
                    var parts = line.split("\\s+", 2);
                    for (var part : parts) {
                        if (!part.isEmpty()) expanded.add(part);
                    }
                }
            } else {
                expanded.add(arg);
            }
        }
        return expanded.toArray(String[]::new);
    }

    private static void addCommaSeparated(
            Set<String> set, String value) {
        for (var part : value.split(",")) {
            var trimmed = part.strip();
            if (!trimmed.isEmpty()) set.add(trimmed);
        }
    }

    private static void printUsage() {
        System.out.println("""
                cinterop-gen: Generate GraalVM CInterop bindings from C headers

                Usage: cinterop-gen [options] <header.h>
                       cinterop-gen @argfile <header.h>

                Options:
                  -p, --package <pkg>           Java package (default: generated)
                  -o, --output <dir>            Output directory (default: .)
                  -c, --class-name <name>       Functions/constants class name
                  --header-include <hdr>        C header for @CContext
                  --no-comments                 Suppress C type comments
                  -I <path>                     Additional include path
                  -D <define>                   Preprocessor define

                Include filters (whitelist mode)

                  By default, every declaration reachable from the header
                  (including transitive #includes) is generated. When any
                  --include-* flag is present, only matching declarations
                  are generated.

                  --include-function <names>    Functions to include
                  --include-struct <names>      Structs to include
                  --include-union <names>       Unions to include
                  --include-enum <names>        Enums to include
                  --include-typedef <names>     Typedefs to include
                  --include-constant <names>    Macro constants to include

                  Each flag accepts comma-separated names or prefix globs
                  (e.g. SYS_*). Repeatable.

                Symbol discovery

                  --dump-includes <file>        Write all discovered symbols
                                                to a file and exit. Edit the
                                                file and pass it back with
                                                @argfile.
                """);
    }
}
