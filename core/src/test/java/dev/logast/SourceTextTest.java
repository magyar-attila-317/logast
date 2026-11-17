package dev.logast;

import dev.logast.parse.SourceText;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** #54: files are worked on as LF text without a BOM and written back byte for byte in their own format. */
class SourceTextTest {

    @Test
    void everyFormatComesBackByteForByte() {
        byte[][] files = {
                "class A {}\n".getBytes(StandardCharsets.UTF_8),
                "class A {\r\n  // é\r\n}\r\n".getBytes(StandardCharsets.UTF_8),
                "﻿a: 1\r\nb: 2\r\n".getBytes(StandardCharsets.UTF_8),
                "// café õ \u0080\u009d\nclass A {}\n".getBytes(StandardCharsets.ISO_8859_1),
                "mixed\r\nlines\n".getBytes(StandardCharsets.UTF_8),
                "lone\rcarriage\r\r\n".getBytes(StandardCharsets.UTF_8),
        };
        for (byte[] b : files) {
            SourceText t = SourceText.decode(b);
            assertThat(t.encode(t.text(), "A.java")).as(new String(b, StandardCharsets.ISO_8859_1)).isEqualTo(b);
        }
    }

    @Test
    void textIsLfWithoutBomAndInsertedLinesGetTheFileFormat() {
        SourceText t = SourceText.decode("﻿a: 1\r\nb: 2\r\n".getBytes(StandardCharsets.UTF_8));
        assertThat(t.text()).isEqualTo("a: 1\nb: 2\n");
        assertThat(new String(t.encode(t.text() + "c: 3\n", "application.yml"), StandardCharsets.UTF_8))
                .isEqualTo("﻿a: 1\r\nb: 2\r\nc: 3\r\n");
        SourceText mixed = SourceText.decode("a\r\nb\n".getBytes(StandardCharsets.UTF_8));
        assertThat(mixed.crlf()).as("not every line is CRLF: kept as it is").isFalse();
        assertThat(mixed.text()).isEqualTo("a\r\nb\n");
    }

    @Test
    void charsALatin1FileCannotHoldBecomeUnicodeEscapesInJava() {
        SourceText t = SourceText.decode("// café\n".getBytes(StandardCharsets.ISO_8859_1));
        assertThat(t.charset()).isEqualTo(StandardCharsets.ISO_8859_1);
        assertThat(new String(t.encode("// café\nlog.info(\"→ done\");\n", "A.java"), StandardCharsets.ISO_8859_1))
                .isEqualTo("// café\nlog.info(\"\\u2192 done\");\n");
    }
}
