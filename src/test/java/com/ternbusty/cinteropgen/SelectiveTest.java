package com.ternbusty.cinteropgen;

import com.ternbusty.cinteropgen.codegen.CodegenConfig;
import com.ternbusty.cinteropgen.codegen.JavaCodegen;
import com.ternbusty.cinteropgen.ir.Header;
import com.ternbusty.cinteropgen.parser.ClangAstParser;
import com.ternbusty.cinteropgen.parser.IncludeFilter;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for include filtering and transitive include traversal.
 *
 * Default mode includes everything reachable (matching jextract).
 * When any --include-* flag is present, only matching declarations
 * are collected (whitelist mode).
 */
class SelectiveTest {

    private Path samplePath() throws Exception {
        return Path.of(getClass().getClassLoader()
                .getResource("sample.h").toURI());
    }

    private Path wrapperPath() throws Exception {
        return Path.of(getClass().getClassLoader()
                .getResource("wrapper.h").toURI());
    }

    // ── IncludeFilter matching ──────────────────────────────────

    @Nested
    class FilterMatchingTests {

        @Test
        void exactMatch() {
            var f = new IncludeFilter(
                    Set.of("sendmsg"), Set.of(), Set.of(),
                    Set.of(), Set.of(), Set.of());
            assertTrue(f.matchesFunction("sendmsg"));
            assertFalse(f.matchesFunction("recvmsg"));
        }

        @Test
        void prefixGlob() {
            var f = new IncludeFilter(
                    Set.of(), Set.of(), Set.of(),
                    Set.of(), Set.of(), Set.of("SYS_*"));
            assertTrue(f.matchesConstant("SYS_read"));
            assertTrue(f.matchesConstant("SYS_capset"));
            assertFalse(f.matchesConstant("CLONE_NEWNS"));
        }

        @Test
        void emptySetMatchesNothing() {
            assertFalse(IncludeFilter.NONE.matchesFunction("anything"));
        }

        @Test
        void noneFilterIsNotSelective() {
            assertFalse(IncludeFilter.NONE.isSelective());
        }

        @Test
        void anyEntryMakesSelective() {
            var f = new IncludeFilter(
                    Set.of("sendmsg"), Set.of(), Set.of(),
                    Set.of(), Set.of(), Set.of());
            assertTrue(f.isSelective());
        }

        @Test
        void unionFilterIsSelective() {
            var f = new IncludeFilter(
                    Set.of(), Set.of(), Set.of("number"),
                    Set.of(), Set.of(), Set.of());
            assertTrue(f.isSelective());
            assertTrue(f.matchesUnion("number"));
            assertFalse(f.matchesStruct("number"));
        }
    }

    // ── Default mode (include everything) ───────────────────────

    @Nested
    class DefaultModeTests {

        @Test
        void defaultModeIncludesTransitiveDeclarations()
                throws Exception {
            // Default mode (no filter) should see declarations from
            // transitive #includes, matching jextract's behaviour.
            var parser = new ClangAstParser();
            var header = parser.parse(wrapperPath());

            var funcNames = header.functions().stream()
                    .map(Header.FunctionDecl::name).toList();
            assertTrue(funcNames.contains("sample_add"),
                    "default mode should see sample.h functions");
            assertTrue(funcNames.contains("sample_greet"),
                    "default mode should see sample.h functions");

            var macroNames = header.constants().stream()
                    .map(Header.MacroConstant::name).toList();
            assertTrue(macroNames.contains("WRAPPER_EXTRA"),
                    "default mode should see wrapper.h's own macro");
            assertTrue(macroNames.contains("SAMPLE_VERSION"),
                    "default mode should see sample.h macros");
        }
    }

    // ── Whitelist mode (--include-*) ────────────────────────────

    @Nested
    class WhitelistModeTests {

        @Test
        void whitelistModeFindsIncludedFunctions() throws Exception {
            var filter = new IncludeFilter(
                    Set.of("sample_add", "sample_greet"),
                    Set.of(), Set.of(), Set.of(), Set.of(), Set.of());

            var parser = new ClangAstParser();
            var header = parser.parse(wrapperPath(), filter);

            var funcNames = header.functions().stream()
                    .map(Header.FunctionDecl::name).toList();
            assertTrue(funcNames.contains("sample_add"),
                    "whitelist mode should find sample_add");
            assertTrue(funcNames.contains("sample_greet"),
                    "whitelist mode should find sample_greet");
            // Should NOT contain functions not in the filter.
            assertFalse(funcNames.contains("sample_alloc"));
        }

        @Test
        void whitelistModeFindsIncludedStructs() throws Exception {
            var filter = new IncludeFilter(
                    Set.of(), Set.of("point"), Set.of(),
                    Set.of(), Set.of(), Set.of());

            var parser = new ClangAstParser();
            var header = parser.parse(wrapperPath(), filter);

            var structNames = header.structs().stream()
                    .map(Header.StructDecl::name).toList();
            assertTrue(structNames.contains("point"),
                    "whitelist mode should find struct point");
            assertFalse(structNames.contains("rect"),
                    "should not include rect (not in filter)");
        }

        @Test
        void whitelistModeFindsIncludedEnums() throws Exception {
            var filter = new IncludeFilter(
                    Set.of(), Set.of(), Set.of(),
                    Set.of("color"), Set.of(), Set.of());

            var parser = new ClangAstParser();
            var header = parser.parse(wrapperPath(), filter);

            var enumNames = header.enums().stream()
                    .map(Header.EnumDecl::name).toList();
            assertTrue(enumNames.contains("color"),
                    "whitelist mode should find enum color");
        }

        @Test
        void whitelistModeFindsAnonymousTypedefStruct()
                throws Exception {
            var filter = new IncludeFilter(
                    Set.of(), Set.of("record_t"), Set.of(),
                    Set.of(), Set.of(), Set.of());

            var parser = new ClangAstParser();
            var header = parser.parse(wrapperPath(), filter);

            var structNames = header.structs().stream()
                    .map(Header.StructDecl::name).toList();
            assertTrue(structNames.contains("record_t"),
                    "whitelist mode should resolve anonymous typedef struct");
        }

        @Test
        void whitelistModeFindsAnonymousTypedefEnum()
                throws Exception {
            var filter = new IncludeFilter(
                    Set.of(), Set.of(), Set.of(),
                    Set.of("log_level_t"), Set.of(), Set.of());

            var parser = new ClangAstParser();
            var header = parser.parse(wrapperPath(), filter);

            var enumNames = header.enums().stream()
                    .map(Header.EnumDecl::name).toList();
            assertTrue(enumNames.contains("log_level_t"),
                    "whitelist mode should resolve anonymous typedef enum");
        }

        @Test
        void whitelistModeFindsFunctionPointerTypedef()
                throws Exception {
            var filter = new IncludeFilter(
                    Set.of(), Set.of(), Set.of(),
                    Set.of(), Set.of("callback_fn"), Set.of());

            var parser = new ClangAstParser();
            var header = parser.parse(wrapperPath(), filter);

            var fpNames = header.functionPointers().stream()
                    .map(Header.FunctionPointerDecl::name).toList();
            assertTrue(fpNames.contains("callback_fn"),
                    "whitelist mode should find function pointer typedef");
        }
    }

    // ── Whitelist mode with macros ───────────────────────────────

    @Nested
    class WhitelistMacroTests {

        @Test
        void whitelistModeResolvesMacrosFromIncludedHeader()
                throws Exception {
            var filter = new IncludeFilter(
                    Set.of(), Set.of(), Set.of(),
                    Set.of(), Set.of(),
                    Set.of("SAMPLE_VERSION", "MAX_NAME_LEN"));

            var parser = new ClangAstParser();
            var header = parser.parse(wrapperPath(), filter);

            var macroNames = header.constants().stream()
                    .map(Header.MacroConstant::name).toList();
            assertTrue(macroNames.contains("SAMPLE_VERSION"),
                    "should resolve SAMPLE_VERSION from sample.h");
            assertTrue(macroNames.contains("MAX_NAME_LEN"),
                    "should resolve MAX_NAME_LEN from sample.h");
        }

        @Test
        void whitelistModeGlobConstant() throws Exception {
            var filter = new IncludeFilter(
                    Set.of(), Set.of(), Set.of(),
                    Set.of(), Set.of(), Set.of("SAMPLE_*"));

            var parser = new ClangAstParser();
            var header = parser.parse(wrapperPath(), filter);

            var macroNames = header.constants().stream()
                    .map(Header.MacroConstant::name).toList();
            assertTrue(macroNames.contains("SAMPLE_VERSION"),
                    "glob SAMPLE_* should match SAMPLE_VERSION");
            assertFalse(macroNames.contains("MAX_NAME_LEN"),
                    "glob SAMPLE_* should NOT match MAX_NAME_LEN");
        }

        @Test
        void directMacroResolvedFromSampleHeader() throws Exception {
            // SAMPLE_VERSION is defined as "1" in sample.h.
            var filter = new IncludeFilter(
                    Set.of(), Set.of(), Set.of(),
                    Set.of(), Set.of(), Set.of("SAMPLE_VERSION"));

            var parser = new ClangAstParser();
            var header = parser.parse(samplePath(), filter);

            var version = header.constants().stream()
                    .filter(c -> c.name().equals("SAMPLE_VERSION"))
                    .findFirst().orElseThrow();
            assertEquals("1", version.value());
        }
    }

    // ── Codegen with whitelist parse ─────────────────────────────

    @Nested
    class WhitelistCodegenTests {

        @Test
        void whitelistCodegenProducesOnlyRequestedFiles()
                throws Exception {
            var filter = new IncludeFilter(
                    Set.of("sample_add"),
                    Set.of("point"), Set.of(),
                    Set.of(), Set.of(), Set.of());

            var parser = new ClangAstParser();
            var header = parser.parse(wrapperPath(), filter);

            var config = new CodegenConfig(
                    "com.example.test", "", "wrapper.h", true);
            var codegen = new JavaCodegen(config);
            var generated = codegen.generate(header);

            assertTrue(generated.containsKey(
                    "com/example/test/Point.java"));
            assertTrue(generated.containsKey(
                    "com/example/test/Wrapper.java"));

            // Should NOT contain files for non-included types.
            assertFalse(generated.containsKey(
                    "com/example/test/Rect.java"));
            assertFalse(generated.containsKey(
                    "com/example/test/Color.java"));

            var funcSrc = generated.get(
                    "com/example/test/Wrapper.java");
            assertTrue(funcSrc.contains("sample_add"));
            assertFalse(funcSrc.contains("sample_greet"));
        }
    }
}
