package com.ternbusty.cinteropgen;

import com.ternbusty.cinteropgen.codegen.CodegenConfig;
import com.ternbusty.cinteropgen.codegen.JavaCodegen;
import com.ternbusty.cinteropgen.parser.ClangAstParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class Main {

    public static void main(String[] args) {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
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
        var includes = new ArrayList<String>();
        var defines = new ArrayList<String>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-p", "--package" -> pkg = args[++i];
                case "-o", "--output" -> output = args[++i];
                case "-c", "--class-name" -> className = args[++i];
                case "--header-include" -> headerInclude = args[++i];
                case "--no-comments" -> emitComments = false;
                case "-I" -> includes.add(args[++i]);
                case "-D" -> defines.add(args[++i]);
                default -> {
                    if (args[i].startsWith("-I")) {
                        includes.add(args[i].substring(2));
                    } else if (args[i].startsWith("-D")) {
                        defines.add(args[i].substring(2));
                    } else if (!args[i].startsWith("-")) {
                        header = args[i];
                    } else {
                        System.err.println("Unknown option: " + args[i]);
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
            System.err.println("error: header not found: " + headerPath);
            System.exit(1);
            return;
        }

        // Build extra clang args.
        var extraArgs = new ArrayList<String>();
        for (var inc : includes) {
            extraArgs.add("-I");
            extraArgs.add(inc);
        }
        for (var def : defines) {
            extraArgs.add("-D" + def);
        }

        try {
            var parser = new ClangAstParser(extraArgs, "clang");
            var ir = parser.parse(headerPath);

            var config = new CodegenConfig(pkg, className, headerInclude, emitComments);
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

    private static void printUsage() {
        System.out.println("""
                cinterop-gen: Generate GraalVM @CFunction/@CStruct bindings from C headers

                Usage: cinterop-gen <header.h> [options]

                Options:
                  -p, --package <pkg>      Java package name (default: generated)
                  -o, --output <dir>       Output directory (default: .)
                  -c, --class-name <name>  Functions/constants class name
                  --header-include <hdr>   C header for @CContext
                  --no-comments            Suppress C type comments
                  -I <path>               Additional include path
                  -D <define>             Preprocessor define
                  -h, --help              Show this help
                """);
    }
}
