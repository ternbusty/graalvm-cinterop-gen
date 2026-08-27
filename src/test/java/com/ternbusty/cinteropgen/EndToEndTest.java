package com.ternbusty.cinteropgen;

import com.ternbusty.cinteropgen.codegen.CodegenConfig;
import com.ternbusty.cinteropgen.codegen.JavaCodegen;
import com.ternbusty.cinteropgen.ir.Header;
import com.ternbusty.cinteropgen.mapper.TypeMapper;
import com.ternbusty.cinteropgen.parser.ClangAstParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndToEndTest {

    private Header header;
    private Map<String, String> generated;

    @BeforeAll
    void setup() throws Exception {
        var headerPath = Path.of(
                getClass().getClassLoader().getResource("sample.h").toURI());
        var parser = new ClangAstParser();
        header = parser.parse(headerPath);

        var config = new CodegenConfig(
                "com.example.sample", "", "sample.h", true);
        var codegen = new JavaCodegen(config);
        generated = codegen.generate(header);
    }

    // ── Parser ───────────────────────────────────────────────────

    @Nested
    class ParserTests {

        @Test
        void structsFound() {
            var names = header.structs().stream()
                    .map(Header.StructDecl::name).toList();
            assertTrue(names.contains("point"));
            assertTrue(names.contains("rect"));
            assertTrue(names.contains("config"));
        }

        @Test
        void anonymousStructTypedef() {
            var names = header.structs().stream()
                    .map(Header.StructDecl::name).toList();
            assertTrue(names.contains("record_t"),
                    "typedef struct { ... } record_t should be found");
        }

        @Test
        void anonymousStructTypedefHasFields() {
            var record = header.structs().stream()
                    .filter(s -> s.name().equals("record_t"))
                    .findFirst().orElseThrow();
            var fieldNames = record.fields().stream()
                    .map(Header.StructField::name).toList();
            assertTrue(fieldNames.contains("name"));
            assertTrue(fieldNames.contains("id"));
            assertTrue(fieldNames.contains("score"));
        }

        @Test
        void unionFound() {
            var number = header.structs().stream()
                    .filter(s -> s.name().equals("number"))
                    .findFirst();
            assertTrue(number.isPresent());
            assertTrue(number.get().isUnion());
        }

        @Test
        void structFields() {
            var point = header.structs().stream()
                    .filter(s -> s.name().equals("point"))
                    .findFirst().orElseThrow();
            var fieldNames = point.fields().stream()
                    .map(Header.StructField::name).toList();
            assertTrue(fieldNames.contains("x"));
            assertTrue(fieldNames.contains("y"));
        }

        @Test
        void enumFound() {
            var names = header.enums().stream()
                    .map(Header.EnumDecl::name).toList();
            assertTrue(names.contains("color"));
        }

        @Test
        void anonymousEnumTypedef() {
            var names = header.enums().stream()
                    .map(Header.EnumDecl::name).toList();
            assertTrue(names.contains("log_level_t"),
                    "typedef enum { ... } log_level_t should be found");
        }

        @Test
        void anonymousEnumTypedefHasConstants() {
            var logLevel = header.enums().stream()
                    .filter(e -> e.name().equals("log_level_t"))
                    .findFirst().orElseThrow();
            var names = logLevel.constants().stream()
                    .map(Header.EnumConstant::name).toList();
            assertTrue(names.contains("LOG_DEBUG"));
            assertTrue(names.contains("LOG_ERROR"));
        }

        @Test
        void enumConstants() {
            var color = header.enums().stream()
                    .filter(e -> e.name().equals("color"))
                    .findFirst().orElseThrow();
            var values = color.constants().stream()
                    .collect(Collectors.toMap(
                            Header.EnumConstant::name,
                            Header.EnumConstant::value));
            assertEquals(0L, values.get("COLOR_RED"));
            assertEquals(1L, values.get("COLOR_GREEN"));
            assertEquals(2L, values.get("COLOR_BLUE"));
        }

        @Test
        void functionsFound() {
            var names = header.functions().stream()
                    .map(Header.FunctionDecl::name).toList();
            assertTrue(names.contains("sample_add"));
            assertTrue(names.contains("sample_greet"));
            assertTrue(names.contains("sample_alloc"));
            assertTrue(names.contains("sample_free"));
        }

        @Test
        void variadicDetected() {
            var printf = header.functions().stream()
                    .filter(f -> f.name().equals("sample_printf"))
                    .findFirst().orElseThrow();
            assertTrue(printf.isVariadic());
        }

        @Test
        void macrosFound() {
            var names = header.constants().stream()
                    .map(Header.MacroConstant::name).toList();
            assertTrue(names.contains("SAMPLE_VERSION"));
            assertTrue(names.contains("MAX_NAME_LEN"));
            assertTrue(names.contains("PI_APPROX"));
        }

        @Test
        void noSystemMacros() {
            for (var mc : header.constants()) {
                assertFalse(mc.name().startsWith("TARGET_OS"),
                        "system macro leaked: " + mc.name());
            }
        }

        @Test
        void functionPointerTypedefDetected() {
            var names = header.functionPointers().stream()
                    .map(Header.FunctionPointerDecl::name).toList();
            assertTrue(names.contains("callback_fn"),
                    "callback_fn typedef should be detected");
        }

        @Test
        void functionPointerParams() {
            var cb = header.functionPointers().stream()
                    .filter(fp -> fp.name().equals("callback_fn"))
                    .findFirst().orElseThrow();
            assertEquals("void", cb.returnQualType());
            assertEquals(2, cb.params().size());
        }

        @Test
        void bitfieldDetected() {
            var flags = header.structs().stream()
                    .filter(s -> s.name().equals("flags"))
                    .findFirst().orElseThrow();
            assertTrue(flags.fields().stream()
                    .allMatch(Header.StructField::isBitfield),
                    "all fields in flags struct should be bitfields");
        }

        @Test
        void bitfieldWidth() {
            var flags = header.structs().stream()
                    .filter(s -> s.name().equals("flags"))
                    .findFirst().orElseThrow();
            var readField = flags.fields().stream()
                    .filter(f -> f.name().equals("read"))
                    .findFirst().orElseThrow();
            assertEquals(1, readField.bitWidth());
        }
    }

    // ── Codegen ──────────────────────────────────────────────────

    @Nested
    class CodegenTests {

        @Test
        void structFileGenerated() {
            assertTrue(generated.containsKey(
                    "com/example/sample/Point.java"));
        }

        @Test
        void structHasCStructAnnotation() {
            var src = generated.get("com/example/sample/Point.java");
            assertTrue(src.contains("@CStruct(\"point\")"));
        }

        @Test
        void structHasCField() {
            var src = generated.get("com/example/sample/Point.java");
            assertTrue(src.contains("@CField(\"x\")"));
            assertTrue(src.contains("int x();"));
            assertTrue(src.contains("void x(int value);"));
        }

        @Test
        void rectHasCFieldAddressForNested() {
            var src = generated.get("com/example/sample/Rect.java");
            assertTrue(src.contains("@CFieldAddress(\"origin\")"));
        }

        @Test
        void anonymousTypedefStructGenerated() {
            assertTrue(generated.containsKey(
                    "com/example/sample/RecordT.java"),
                    "RecordT.java should be generated for typedef struct");
            var src = generated.get("com/example/sample/RecordT.java");
            assertTrue(src.contains("@CStruct(\"record_t\")"));
            assertTrue(src.contains("@CField(\"id\")"));
        }

        @Test
        void anonymousTypedefEnumGenerated() {
            assertTrue(generated.containsKey(
                    "com/example/sample/LogLevelT.java"),
                    "LogLevelT.java should be generated for typedef enum");
            var src = generated.get("com/example/sample/LogLevelT.java");
            assertTrue(src.contains("LOG_DEBUG = 0"));
            assertTrue(src.contains("LOG_ERROR = 3"));
        }

        @Test
        void enumGenerated() {
            var src = generated.get("com/example/sample/Color.java");
            assertTrue(src.contains("COLOR_RED = 0"));
            assertTrue(src.contains("COLOR_GREEN = 1"));
        }

        @Test
        void functionsClassGenerated() {
            var src = generated.get("com/example/sample/Sample.java");
            assertTrue(src.contains("@CFunction(\"sample_add\")"));
            assertTrue(src.contains("int sample_add(int a, int b)"));
        }

        @Test
        void voidPointerReturn() {
            var src = generated.get("com/example/sample/Sample.java");
            assertTrue(src.contains("VoidPointer sample_alloc"));
        }

        @Test
        void charPointerParam() {
            var src = generated.get("com/example/sample/Sample.java");
            assertTrue(src.contains("CCharPointer name") ||
                    src.contains("CCharPointer fmt"));
        }

        @Test
        void packageDeclaration() {
            for (var src : generated.values()) {
                assertTrue(src.contains("package com.example.sample;"));
            }
        }

        @Test
        void ccontextDirectives() {
            var src = generated.get("com/example/sample/Sample.java");
            assertTrue(src.contains("Directives"));
            assertTrue(src.contains("getHeaderFiles"));
            assertTrue(src.contains("\"<sample.h>\""));
        }

        @Test
        void unionFileGenerated() {
            assertTrue(generated.containsKey(
                    "com/example/sample/Number.java"));
            var src = generated.get("com/example/sample/Number.java");
            assertTrue(src.contains("@CStruct(\"number\")"));
        }

        @Test
        void sizeTMappedToUnsignedWord() {
            var src = generated.get("com/example/sample/Sample.java");
            assertTrue(src.contains("UnsignedWord size") ||
                    src.contains("UnsignedWord len"));
        }

        // ── CFunctionPointer ─────────────────────────────────────

        @Test
        void cfunctionPointerGenerated() {
            assertTrue(generated.containsKey(
                    "com/example/sample/CallbackFn.java"),
                    "CallbackFn.java should be generated for callback_fn");
        }

        @Test
        void cfunctionPointerHasAnnotations() {
            var src = generated.get("com/example/sample/CallbackFn.java");
            assertTrue(src.contains("@CFunctionPointer"));
            assertTrue(src.contains("extends CFunctionPointer"));
            assertTrue(src.contains("@InvokeCFunctionPointer"));
        }

        @Test
        void cfunctionPointerInvokeSignature() {
            var src = generated.get("com/example/sample/CallbackFn.java");
            assertTrue(src.contains("void invoke("));
            assertTrue(src.contains("int arg0"));
            assertTrue(src.contains("CCharPointer arg1"));
        }

        @Test
        void functionPointerParamUsesInterfaceName() {
            var src = generated.get("com/example/sample/Sample.java");
            assertTrue(src.contains("CallbackFn cb"),
                    "callback_fn param should use CallbackFn type, not PointerBase");
        }

        // ── @CPointerTo ─────────────────────────────────────────

        @Test
        void cpointerToGenerated() {
            assertTrue(generated.containsKey(
                    "com/example/sample/PointPointer.java"),
                    "PointPointer.java should be generated for struct point **");
        }

        @Test
        void cpointerToHasAnnotation() {
            var src = generated.get("com/example/sample/PointPointer.java");
            assertTrue(src.contains("@CPointerTo(Point.class)"));
            assertTrue(src.contains("extends PointerBase"));
        }

        @Test
        void cpointerToHasReadWrite() {
            var src = generated.get("com/example/sample/PointPointer.java");
            assertTrue(src.contains("Point read()"));
            assertTrue(src.contains("void write(Point value)"));
        }

        @Test
        void doublePointerParamUsesPointerToType() {
            var src = generated.get("com/example/sample/Sample.java");
            assertTrue(src.contains("PointPointer out"),
                    "struct point ** param should use PointPointer type");
        }

        // ── Bitfield ─────────────────────────────────────────────

        @Test
        void bitfieldSkippedWithComment() {
            var src = generated.get("com/example/sample/Flags.java");
            assertTrue(src.contains("Bitfield 'read'"));
            assertTrue(src.contains("@CField does not support bitfields"));
            // Should NOT have @CField for bitfield fields.
            assertFalse(src.contains("@CField(\"read\")"));
        }

        // ── Variadic ─────────────────────────────────────────────

        @Test
        void variadicHasWarningComment() {
            var src = generated.get("com/example/sample/Sample.java");
            // Find the @CFunction annotation for printf.
            int idx = src.indexOf("@CFunction(\"sample_printf\")");
            assertTrue(idx > 0);
            var before = src.substring(Math.max(0, idx - 200), idx);
            assertTrue(before.contains("Variadic"),
                    "variadic function should have a warning comment");
        }

        // ── Struct value return ──────────────────────────────────

        @Test
        void structReturnHasWarningComment() {
            var src = generated.get("com/example/sample/Sample.java");
            int idx = src.indexOf("@CFunction(\"sample_make_point\")");
            assertTrue(idx > 0);
            var before = src.substring(Math.max(0, idx - 200), idx);
            assertTrue(before.contains("struct by value"),
                    "struct-returning function should have a warning comment");
        }
    }

    // ── TypeMapper unit tests ────────────────────────────────────

    @Nested
    class TypeMapperTests {

        private final TypeMapper tm = new TypeMapper();

        @Test
        void voidPointer() {
            assertEquals("VoidPointer", tm.map("void *"));
        }

        @Test
        void charPointer() {
            assertEquals("CCharPointer", tm.map("const char *"));
            assertEquals("CCharPointer", tm.map("char *"));
        }

        @Test
        void structPointer() {
            assertEquals("Point", tm.map("struct point *"));
        }

        @Test
        void sizeT() {
            assertEquals("UnsignedWord", tm.map("size_t"));
        }

        @Test
        void int32T() {
            assertEquals("int", tm.map("int32_t"));
        }

        @Test
        void enumToInt() {
            assertEquals("int", tm.map("enum color"));
        }

        @Test
        void intPointer() {
            assertEquals("CIntPointer", tm.map("int *"));
        }

        @Test
        void constIntPointer() {
            assertEquals("CIntPointer", tm.map("const int *"));
        }

        @Test
        void functionPointer() {
            assertEquals("PointerBase",
                    tm.map("void (*)(int, const char *)"));
        }

        @Test
        void functionPointerTypedefResolved() {
            var tm2 = new TypeMapper(Set.of("callback_fn"));
            assertEquals("CallbackFn", tm2.map("callback_fn"));
        }

        @Test
        void doublePointerToStruct() {
            assertEquals("PointPointer",
                    tm.map("struct point **"));
        }

        @Test
        void extractReturnType() {
            assertEquals("int",
                    ClangAstParser.extractReturnType("int (int, int)"));
            assertEquals("void *",
                    ClangAstParser.extractReturnType("void *(size_t)"));
            assertEquals("void",
                    ClangAstParser.extractReturnType("void (void *)"));
            assertEquals("struct point",
                    ClangAstParser.extractReturnType(
                            "struct point (int, int)"));
        }

        @Test
        void isStructByValue() {
            assertTrue(tm.isStructByValue("struct point"));
            assertFalse(tm.isStructByValue("struct point *"));
            assertFalse(tm.isStructByValue("int"));
        }

        @Test
        void doublePointerStructName() {
            assertEquals("point",
                    TypeMapper.doublePointerStructName("struct point **"));
            assertNull(
                    TypeMapper.doublePointerStructName("struct point *"));
            assertNull(TypeMapper.doublePointerStructName("int **"));
        }
    }
}
