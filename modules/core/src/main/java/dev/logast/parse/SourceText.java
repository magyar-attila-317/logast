package dev.logast.parse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * How logast reads and writes the files of a project (#54): in the charset and with the line ending they have.
 * <p>The text logast works on has LF line endings and no BOM. A file that is not UTF-8 is read as ISO-8859-1, which
 * maps every byte to one char and back: a Latin-1, Latin-2 or windows-1252 file keeps every byte outside the edits.
 * A file whose every line ends with CRLF is worked on with LF and written back with CRLF, so the lines an edit inserts
 * get the file's line ending. Writing a file looks at what is on disk: an existing file keeps its charset, BOM and line
 * ending; a new file is UTF-8 with LF.
 */
public record SourceText(String text, Charset charset, boolean bom, boolean crlf) {
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final SourceText NEW_FILE = new SourceText("", StandardCharsets.UTF_8, false, false);

    /** The text of these bytes, with the format to write it back in. */
    public static SourceText decode(byte[] bytes) {
        boolean bom = startsWithBom(bytes);
        ByteBuffer body = ByteBuffer.wrap(bytes, bom ? UTF8_BOM.length : 0, bytes.length - (bom ? UTF8_BOM.length : 0));
        String raw;
        Charset charset;
        try {
            raw = StandardCharsets.UTF_8.newDecoder().decode(body).toString();
            charset = StandardCharsets.UTF_8;
        } catch (CharacterCodingException e) {
            raw = new String(bytes, StandardCharsets.ISO_8859_1);
            charset = StandardCharsets.ISO_8859_1;
            bom = false;
        }
        boolean crlf = raw.contains("\r\n") && raw.replace("\r\n", "").indexOf('\n') < 0;
        return new SourceText(crlf ? raw.replace("\r\n", "\n") : raw, charset, bom, crlf);
    }

    /** These bytes for {@code text} (LF line endings) in this format; chars the charset lacks become Unicode escapes in Java and .properties files. */
    public byte[] encode(String text, String fileName) {
        String out = crlf ? text.replace("\n", "\r\n") : text;
        if (charset.equals(StandardCharsets.ISO_8859_1) && (fileName.endsWith(".java") || fileName.endsWith(".properties"))) {
            StringBuilder sb = new StringBuilder(out.length());
            out.chars().forEach(c -> sb.append(c > 0xFF ? String.format("\\u%04x", c) : String.valueOf((char) c)));
            out = sb.toString();
        }
        byte[] body = out.getBytes(charset);
        if (!bom) {
            return body;
        }
        byte[] all = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, all, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, all, UTF8_BOM.length, body.length);
        return all;
    }

    /** The text of a file, LF line endings and no BOM. */
    public static String read(Path p) throws IOException {
        return decode(Files.readAllBytes(p)).text();
    }

    /** {@link #read}, unchecked. */
    public static String readUnchecked(Path p) {
        try {
            return read(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes {@code text} in the format of the file on disk (new file: UTF-8, LF). */
    public static void write(Path p, String text) throws IOException {
        SourceText format = Files.exists(p) ? decode(Files.readAllBytes(p)) : NEW_FILE;
        Files.write(p, format.encode(text, String.valueOf(p.getFileName())));
    }

    /** Text for a diff: the decoded text, or null for binary content (a NUL byte: fonts, images, PDFs in resources). */
    public static String textOrNull(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        for (byte b : bytes) {
            if (b == 0) {
                return null;
            }
        }
        return decode(bytes).text();
    }

    private static boolean startsWithBom(byte[] b) {
        return b.length >= 3 && b[0] == UTF8_BOM[0] && b[1] == UTF8_BOM[1] && b[2] == UTF8_BOM[2];
    }
}
