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
            var names = header.structs().stream().map(Header.StructDecl::name).toList();
            assertTrue(names.contains("point"), "point struct not found");
            assertTrue(names.contains("rect"), "rect struct not found");
            assertTrue(names.contains("config"), "config struct not found");
        }

        @Test
        void unionFound() {
            var number = header.structs().stream()
                    .filter(s -> s.name().equals("number"))
                    .findFirst();
            assertTrue(number.isPresent(), "number union not found");
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
            var names = header.enums().stream().map(Header.EnumDecl::name).toList();
            assertTrue(names.contains("color"), "color enum not found");
        }

        @Test
        void enumConstants() {
            var color = header.enums().stream()
                    .filter(e -> e.name().equals("color"))
                    .findFirst().orElseThrow();
            var values = color.constants().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            Header.EnumConstant::name, Header.EnumConstant::value));
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
            var names = header.constants().stream()
                    .map(Header.MacroConstant::name).toList();
            for (var name : names) {
                assertFalse(name.startsWith("TARGET_OS"),
                        "system macro leaked: " + name);
            }
        }
    }

    // ── Codegen ──────────────────────────────────────────────────

    @Nested
    class CodegenTests {

        @Test
        void structFileGenerated() {
            assertTrue(generated.containsKey("com/example/sample/Point.java"));
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
            assertTrue(src.contains("@CFieldAddress(\"origin\")"),
                    "nested struct origin should use @CFieldAddress");
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
            assertTrue(generated.containsKey("com/example/sample/Number.java"));
            var src = generated.get("com/example/sample/Number.java");
            assertTrue(src.contains("@CStruct(\"number\")"));
        }

        @Test
        void sizeTMappedToUnsignedWord() {
            var src = generated.get("com/example/sample/Sample.java");
            assertTrue(src.contains("UnsignedWord size") ||
                    src.contains("UnsignedWord len"));
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
            assertEquals("PointerBase", tm.map("void (*)(int, const char *)"));
        }

        @Test
        void extractReturnType() {
            assertEquals("int", ClangAstParser.extractReturnType("int (int, int)"));
            assertEquals("void *", ClangAstParser.extractReturnType("void *(size_t)"));
            assertEquals("void", ClangAstParser.extractReturnType("void (void *)"));
            assertEquals("struct point", ClangAstParser.extractReturnType("struct point (int, int)"));
        }
    }
}
