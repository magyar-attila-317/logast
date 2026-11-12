package dev.logast.parse;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** @param discovered source files found on disk (in scope); each is in {@code files} or in {@code parseErrors} */
public record ParsedProject(Path root, List<SourceFileInfo> files, List<Path> classpath,
                            List<String> parseErrors, ClasspathProbe probe, int discovered) {

    public Map<String, SourceFileInfo> byRelPath() {
        return files.stream().collect(Collectors.toMap(SourceFileInfo::relPath, Function.identity()));
    }
}
