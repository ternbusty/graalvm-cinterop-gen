# cinterop-gen

GraalVM `@CFunction` / `@CStruct` Java バインディングを C ヘッダから自動生成するツールです。
[jextract](https://github.com/openjdk/jextract) が Panama FFM 向けのバインディングを生成するのと同じ役割を、GraalVM CInterop アノテーション向けに担います。

## アーキテクチャ

jextract と同じ 3 段パイプラインを踏襲しています。

1. **Parser** (`ClangAstParser`) … `clang -Xclang -ast-dump=json` の JSON AST を Gson で走査
2. **IR** (`Header` record) … struct / function / enum / typedef / macro を表す Java record 群
3. **Codegen** (`JavaCodegen`) … IR → `@CStruct` `@CField` `@CFunction` 等の Java ソースを出力

型変換は `TypeMapper` が担当し、`size_t` → `UnsignedWord`、`char *` → `CCharPointer` などの GraalVM 固有のマッピングを行います。

Python の libclang バインディングではなく clang コマンドの JSON 出力をパースする方式を採用しているため、ネイティブライブラリ依存がありません。

## 前提条件

- JDK 21 以上
- `clang` コマンドがパスに通っていること (macOS は Xcode Command Line Tools に同梱)

## ビルド

```bash
./gradlew build
```

## 使い方

```bash
./gradlew run --args="path/to/header.h -p com.example.mylib -o src/main/java --header-include mylib.h"
```

### オプション

| フラグ | 説明 |
|---|---|
| `-p` / `--package` | Java パッケージ名 |
| `-o` / `--output` | 出力先ディレクトリ |
| `-c` / `--class-name` | 関数/定数クラス名 (省略時はヘッダ名から導出) |
| `--header-include` | `@CContext` に渡すヘッダパス |
| `-I` | 追加インクルードパス (複数指定可) |
| `-D` | プリプロセッサ定義 (複数指定可) |
| `--no-comments` | C 型コメントを抑制 |

## 入出力の例

入力 (`sample.h`)

```c
struct point {
    int x;
    int y;
};

int sample_add(int a, int b);
void sample_greet(const char *name);
```

出力 (`Point.java`)

```java
package com.example;

import org.graalvm.nativeimage.c.struct.CField;
import org.graalvm.nativeimage.c.struct.CStruct;
import org.graalvm.word.PointerBase;

@CStruct("point")
public interface Point extends PointerBase {

    @CField("x")
    int x();

    @CField("x")
    void x(int value);

    @CField("y")
    int y();

    @CField("y")
    void y(int value);
}
```

出力 (`Sample.java`)

```java
package com.example;

import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.type.CCharPointer;

public final class Sample {

    private Sample() {}

    @CFunction("sample_add")
    public static native int sample_add(int a, int b);

    @CFunction("sample_greet")
    public static native void sample_greet(CCharPointer name);
}
```

## テスト

```bash
./gradlew test
```

## ライセンス

MIT
