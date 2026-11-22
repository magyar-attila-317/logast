package dev.logast.parse;

import org.openrewrite.java.tree.J;

import java.nio.file.Path;

/**
 * One parsed Java file.
 *
 * @param relPath  path relative to the project root, '/' separated
 * @param text     file content exactly as on disk; all offsets refer to it
 * @param editable false when the printed tree does not reproduce the disk text, so offsets cannot be trusted
 */
public record SourceFileInfo(Path absPath, String relPath, String text, J.CompilationUnit cu,
                             String sha256, boolean editable) {

    public String lineText(int line) {
        int idx = 0;
        for (int l = 1; l < line && idx >= 0; l++) {
            idx = text.indexOf('\n', idx);
            if (idx >= 0) {
                idx++;
            }
        }
        if (idx < 0 || idx > text.length()) {
            return "";
        }
        int end = text.indexOf('\n', idx);
        return text.substring(idx, end < 0 ? text.length() : end);
    }

    public String slice(int start, int end) {
        return text.substring(start, end);
    }
}
