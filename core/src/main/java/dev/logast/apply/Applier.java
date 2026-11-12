package dev.logast.apply;

import dev.logast.orders.Edit;
import dev.logast.orders.ScanResult;
import dev.logast.orders.Slot;
import dev.logast.orders.WorkOrder;
import dev.logast.parse.ProjectParser;
import dev.logast.rules.MessageTemplate;
import dev.logast.rules.Rules;
import dev.logast.rules.Text;
import org.openrewrite.internal.InMemoryDiffEntry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Function;

/**
 * Applies selected orders in rounds on a scratch copy of the project. Each round applies every pending order
 * whose edits do not overlap (structural fixes first), then rescans; order ids are stable, so deferred orders
 * and their fills carry over. Only when everything parses are changes copied back (with {@code write}).
 */
public final class Applier {
    private static final int MAX_ROUNDS = 6;
    private static final Set<String> SKIP = Set.of("build", "target", ".git", ".gradle", ".idea", "node_modules", "out");

    public record Selection(boolean auto, Set<String> only, Map<String, Fill> fills) {
    }

    public record Result(List<String> applied, Map<String, String> rejected, List<String> resolvedIndirectly,
                         List<String> changedFiles, String diff, int rounds) {
    }

    private final Function<Path, ScanResult> scanner;
    private final FillValidator validator;

    public Applier(Function<Path, ScanResult> scanner, FillValidator validator) {
        this.scanner = scanner;
        this.validator = validator;
    }

    public Result apply(Path root, Selection sel, boolean write) {
        Path work = copy(root);
        try {
            return run(root, work, sel, write);
        } finally {
            deleteTree(work);
        }
    }

    private Result run(Path root, Path work, Selection sel, boolean write) {
        List<String> applied = new ArrayList<>();
        Map<String, String> rejected = new LinkedHashMap<>();
        List<String> indirect = new ArrayList<>();
        Set<String> pending = new LinkedHashSet<>();
        Set<String> everSelected = new HashSet<>();

        ScanResult scan = scanner.apply(work);
        select(scan, sel, pending, everSelected, rejected, Set.of());
        int round = 0;
        while (!pending.isEmpty() && round < MAX_ROUNDS) {
            round++;
            Map<String, WorkOrder> byId = new LinkedHashMap<>();
            scan.orders().forEach(o -> byId.put(o.id(), o));
            for (Iterator<String> it = pending.iterator(); it.hasNext(); ) {
                String id = it.next();
                if (!byId.containsKey(id)) {
                    it.remove();
                    if (round > 1) {
                        indirect.add(id);
                    } else {
                        rejected.put(id, "no such order in a fresh scan (stale orders file? rescan)");
                    }
                }
            }
            List<WorkOrder> batch = pending.stream().map(byId::get)
                    .sorted(Comparator.comparingInt(Applier::priority).thenComparing(WorkOrder::id)).toList();

            List<Edit> accepted = new ArrayList<>();
            List<String> acceptedIds = new ArrayList<>();
            for (WorkOrder o : batch) {
                List<String> problems = validator.validate(o, sel.fills().get(o.id()));
                if (!problems.isEmpty()) {
                    rejected.put(o.id(), String.join("; ", problems));
                    pending.remove(o.id());
                    continue;
                }
                List<String> missingDeps = o.dependsOn().stream()
                        .filter(d -> !applied.contains(d) && !pending.contains(d) && !acceptedIds.contains(d)).toList();
                if (!missingDeps.isEmpty()) {
                    rejected.put(o.id(), "depends on " + missingDeps + ": select it in the same apply");
                    pending.remove(o.id());
                    continue;
                }
                if (o.dependsOn().stream().anyMatch(d -> pending.contains(d) && !acceptedIds.contains(d))) {
                    continue; // dependency not applied yet: next round
                }
                List<Edit> rendered = render(o, sel.fills().get(o.id()));
                if (rendered.stream().anyMatch(e -> accepted.stream().anyMatch(a -> !a.equals(e) && a.overlaps(e)))) {
                    continue; // conflicts with an earlier order this round: retry after rescan
                }
                rendered.stream().filter(e -> !accepted.contains(e)).forEach(accepted::add);
                acceptedIds.add(o.id());
            }
            if (acceptedIds.isEmpty()) {
                break;
            }
            List<String> broken = applyEdits(work, accepted);
            for (String id : acceptedIds) {
                WorkOrder o = byId.get(id);
                if (o.edits().stream().anyMatch(e -> broken.contains(e.file()))) {
                    rejected.put(id, "produced code that does not parse in " + broken + " (batch reverted)");
                } else {
                    applied.add(id);
                }
                pending.remove(id);
            }
            scan = scanner.apply(work);
            if (sel.auto()) {
                select(scan, sel, pending, everSelected, rejected, new HashSet<>(applied));
            }
        }
        int rounds = round;
        pending.forEach(id -> rejected.put(id, "could not be applied without conflicts after " + rounds + " rounds; rescan and retry"));

        List<String> changed = new ArrayList<>();
        StringBuilder diff = new StringBuilder();
        compare(root, work, changed, diff);
        if (write) {
            copyBack(root, work, changed);
        }
        return new Result(applied, rejected, indirect, changed, diff.toString(), round);
    }

    private static void select(ScanResult scan, Selection sel, Set<String> pending, Set<String> everSelected,
                               Map<String, String> rejected, Set<String> applied) {
        for (WorkOrder o : scan.orders()) {
            boolean wanted = sel.fills().containsKey(o.id()) || sel.only().contains(o.id())
                    || (sel.auto() && o.mechanical());
            if (!sel.only().isEmpty() && !sel.only().contains(o.id()) && !sel.fills().containsKey(o.id())) {
                wanted = false;
            }
            if (wanted && !applied.contains(o.id()) && !rejected.containsKey(o.id()) && everSelected.add(o.id())) {
                pending.add(o.id());
            }
        }
        for (String id : sel.fills().keySet()) {
            if (everSelected.add(id)) {
                pending.add(id); // reported as stale if it does not exist
            }
        }
    }

    /** Structural changes first so that nested edits are recomputed against them. */
    static int priority(WorkOrder o) {
        return switch (o.rule()) {
            case Rules.SETUP_SUPPORT_STARTER, Rules.SETUP_OTEL_STARTER, Rules.SETUP_LOGBACK_XML,
                 Rules.SETUP_STRUCTURED_LOGGING -> 0;
            case Rules.ENTRYPOINT_NOT_OBSERVED, Rules.MISSING_DOMAIN_MDC, Rules.ENTRYPOINT_SILENT, Rules.REPEATED_LOG_VALUE,
                 Rules.BUSINESS_EVENT_UNMEASURED -> 1;
            case Rules.MDC_NOT_CLOSEABLE, Rules.MANUAL_TRACE_MDC -> 2;
            case Rules.SWALLOWED_EXCEPTION, Rules.LOG_AND_RETHROW -> 3;
            case Rules.ASYNC_CONTEXT_LOSS -> 4;
            default -> 5;
        };
    }

    /** Substitutes slot text, the [k=v] suffix and extra arguments into the order's edits. */
    static List<Edit> render(WorkOrder o, Fill fill) {
        List<Edit> out = new ArrayList<>();
        List<String> extra = fill == null ? List.of() : fill.args();
        for (Edit e : o.edits()) {
            String text = e.text();
            for (Map.Entry<String, Slot> s : o.slots().entrySet()) {
                String value = fill.slots().get(s.getKey()).strip();
                List<String> keys = new ArrayList<>(s.getValue().keys());
                if (s.getKey().equals(o.context().argsSlot())) {
                    keys.addAll(extra);
                }
                String rendered = Text.javaString(value);
                rendered = rendered.substring(1, rendered.length() - 1) + MessageTemplate.suffix(keys);
                text = text.replace("{{" + s.getKey() + "}}", rendered);
            }
            text = text.replace("{{ARGS}}", String.join("", extra.stream().map(a -> ", " + a).toList()));
            out.add(new Edit(e.file(), e.kind(), e.start(), e.end(), text));
        }
        return out;
    }

    /** Applies edits per file (descending offsets); returns files that no longer parse (and reverts them). */
    private static List<String> applyEdits(Path work, List<Edit> edits) {
        Map<String, List<Edit>> byFile = new TreeMap<>();
        edits.forEach(e -> byFile.computeIfAbsent(e.file(), k -> new ArrayList<>()).add(e));
        List<String> broken = new ArrayList<>();
        for (var entry : byFile.entrySet()) {
            Path p = work.resolve(entry.getKey());
            try {
                List<Edit> fileEdits = entry.getValue();
                if (fileEdits.stream().anyMatch(e -> e.kind().equals("delete"))) {
                    Files.deleteIfExists(p);
                    continue;
                }
                Optional<Edit> create = fileEdits.stream().filter(e -> e.kind().equals("create")).findFirst();
                if (create.isPresent()) {
                    Files.createDirectories(p.getParent());
                    Files.writeString(p, create.get().text());
                    continue;
                }
                String before = Files.readString(p);
                StringBuilder sb = new StringBuilder(before);
                List<Edit> sorted = new ArrayList<>(fileEdits);
                // descending start; at equal start apply ranges before inserts so inserted text lands in front
                sorted.sort(Comparator.comparingInt(Edit::start).reversed()
                        .thenComparing(e -> e.start() == e.end() ? 1 : 0));
                for (Edit e : sorted) {
                    sb.replace(e.start(), e.end(), e.text());
                }
                String after = sb.toString();
                if (p.toString().endsWith(".java")) {
                    after = ImportFixer.fix(after, ImportFixer.learn(edits.stream().map(Edit::text).toList()));
                }
                if (p.toString().endsWith(".java") && !ProjectParser.parsesCleanly(work, work.relativize(p), after)) {
                    broken.add(entry.getKey());
                    continue;
                }
                Files.writeString(p, after);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        return broken;
    }

    // ---- scratch copy -------------------------------------------------------------------------

    private static Path copy(Path root) {
        try {
            Path work = Files.createTempDirectory("logast-apply-");
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(root) && SKIP.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Files.createDirectories(work.resolve(root.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    // sockets, pipes and devices (e.g. a tool's daemon.sock) cannot be copied and are never edited
                    if (attrs.isRegularFile() || attrs.isSymbolicLink()) {
                        Files.copy(file, work.resolve(root.relativize(file).toString()),
                                StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;   // unreadable entry: not something logast edits
                }
            });
            return work;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void compare(Path root, Path work, List<String> changed, StringBuilder diff) {
        Set<String> rels = new TreeSet<>();
        for (Path base : List.of(root, work)) {
            try (var s = Files.walk(base)) {
                s.filter(Files::isRegularFile)
                        .map(p -> base.relativize(p).toString().replace('\\', '/'))
                        .filter(r -> Arrays.stream(r.split("/")).noneMatch(SKIP::contains))
                        .forEach(rels::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        for (String rel : rels) {
            byte[] a = bytes(root.resolve(rel));
            byte[] b = bytes(work.resolve(rel));
            if (Arrays.equals(a, b)) {
                continue;
            }
            changed.add(rel);
            String ta = text(a);
            String tb = text(b);
            if ((a != null && ta == null) || (b != null && tb == null)) {
                diff.append("Binary file ").append(rel).append(" changed\n");
                continue;
            }
            Path p = Path.of(rel);
            try (InMemoryDiffEntry d = new InMemoryDiffEntry(a == null ? null : p, b == null ? null : p, null,
                    ta == null ? "" : ta, tb == null ? "" : tb, Set.of())) {
                diff.append(d.getDiff());
            }
        }
    }

    private static byte[] bytes(Path p) {
        try {
            return Files.exists(p) ? Files.readAllBytes(p) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** UTF-8 text, or null for binary content (fonts, PDFs, images in resources). */
    private static String text(byte[] b) {
        if (b == null) {
            return null;
        }
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return null;
        }
    }

    private static void copyBack(Path root, Path work, List<String> changed) {
        try {
            for (String rel : changed) {
                Path src = work.resolve(rel);
                Path dst = root.resolve(rel);
                if (Files.exists(src)) {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.deleteIfExists(dst);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path p) {
        try {
            return Files.exists(p) ? Files.readString(p) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteTree(Path dir) {
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best effort cleanup of the scratch copy
        }
    }
}
