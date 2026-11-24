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

    /** @param lenientFills texts for orders that no longer exist are skipped silently (the automatic .logast/decisions/messages.yaml) */
    public record Selection(boolean auto, Set<String> only, Map<String, Fill> fills, boolean lenientFills) {
        public Selection(boolean auto, Set<String> only, Map<String, Fill> fills) {
            this(auto, only, fills, false);
        }
    }

    /**
     * @param before the scan of the code as it was; {@code after} the scan once every applied order is in (both left
     *               out of the JSON output)
     * @param fixed  file → line in the result → "RULE id" of each applied order located there (the coverage report, #72)
     */
    public record Result(List<String> applied, Map<String, String> rejected, List<String> resolvedIndirectly,
                         List<String> changedFiles, String diff, int rounds,
                         @com.fasterxml.jackson.annotation.JsonIgnore ScanResult before,
                         @com.fasterxml.jackson.annotation.JsonIgnore ScanResult after,
                         List<String> violations,
                         @com.fasterxml.jackson.annotation.JsonIgnore Map<String, String> stepDiffs,
                         @com.fasterxml.jackson.annotation.JsonIgnore List<String> trace,
                         @com.fasterxml.jackson.annotation.JsonIgnore Map<String, TreeMap<Integer, Set<String>>> fixed,
                         List<String> behavioural, List<String> keptByHand) {
        public Result(List<String> applied, Map<String, String> rejected, List<String> resolvedIndirectly,
                      List<String> changedFiles, String diff, int rounds, ScanResult before, ScanResult after,
                      List<String> violations, Map<String, String> stepDiffs) {
            this(applied, rejected, resolvedIndirectly, changedFiles, diff, rounds, before, after, violations, stepDiffs, List.of(), Map.of(),
                    List.of(), List.of());
        }
    }

    private final Function<Path, ScanResult> scanner;
    private final FillValidator validator;
    private java.util.function.Consumer<String> progress = s -> {
    };
    private java.util.function.BiFunction<Path, List<String>, List<String>> invariants = (work, files) -> List.of();

    private Rules.Step until;

    /** Stop after this pipeline step (null: run all), e.g. only setup and new lines first. */
    public Applier until(Rules.Step step) {
        this.until = step;
        return this;
    }

    private Set<Rules.Step> skipped = EnumSet.noneOf(Rules.Step.class);
    private java.util.function.Predicate<String> inPaths = file -> true;

    /**
     * #288: apply only orders on files matching one of these globs (relative to the project, e.g.
     * {@code src/main/java/com/example/orders/**}); the setup step (build file, configuration, support classes) always
     * applies, the code depends on it. Empty: every file.
     */
    public Applier paths(List<String> globs) {
        if (globs.isEmpty()) {
            inPaths = file -> true;
            return this;
        }
        List<java.nio.file.PathMatcher> matchers = globs.stream()
                .map(g -> java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + g)).toList();
        inPaths = file -> file != null && matchers.stream().anyMatch(m -> m.matches(Path.of(file)));
        return this;
    }

    /** #287: leave these steps out of every pass: their orders stay open findings, the other steps apply as usual. */
    /**
     * #317: ids of orders an earlier apply applied. One that is back as an open order lost its fix since, which is what
     * a person reverting it does: left as the person changed it (listed in {@link Result#keptByHand()}), unless the run
     * names it ({@code --only}) or brings a text for it (messages.yaml).
     */
    public Applier declined(Set<String> ids, java.util.function.Predicate<String> changedSince) {
        this.declined = Set.copyOf(ids);
        this.changedSince = changedSince;
        return this;
    }

    private Set<String> declined = Set.of();
    /** #317: whether a file differs from what the last apply wrote into it (a person changed it since). */
    private java.util.function.Predicate<String> changedSince = f -> false;
    private final Set<String> keptByHand = new java.util.TreeSet<>();

    public Applier skip(Set<Rules.Step> steps) {
        this.skipped = steps.isEmpty() ? EnumSet.noneOf(Rules.Step.class) : EnumSet.copyOf(steps);
        return this;
    }

    /** Properties the changed files must have (Invariants): checked on the scratch copy before anything is written. */
    public Applier onInvariants(java.util.function.BiFunction<Path, List<String>, List<String>> check) {
        this.invariants = check;
        return this;
    }

    private java.util.function.Consumer<Path> onFinish = work -> {
    };

    /**
     * Called with the scratch copy after the run (the last scan analysed it; with write, the project now holds the same
     * files), before the copy is deleted: #125 writes the reports from that analysis, whose files are still there.
     */
    public Applier onFinish(java.util.function.Consumer<Path> action) {
        this.onFinish = action;
        return this;
    }

    /**
     * Receives a short line for every step (copy, analysis, each round, re-checks); the CLI shows it as it goes.
     * Lines starting with "~" are details: worth showing on a live status line, not worth a line of their own.
     */
    public Applier onProgress(java.util.function.Consumer<String> progress) {
        this.progress = progress;
        return this;
    }

    private CompileCheck compile;
    private boolean lombokWarned;

    /** Type-check every round's changed files against this classpath (#44); without it only the syntax is checked. */
    public Applier compileWith(List<Path> classpath) {
        CompileCheck c = new CompileCheck(classpath);
        this.compile = c.available() ? c : null;
        return this;
    }

    public Applier(Function<Path, ScanResult> scanner, FillValidator validator) {
        this.scanner = scanner;
        this.validator = validator;
    }

    public Result apply(Path root, Selection sel, boolean write) {
        Sweep sweep = sweepStale(Path.of(System.getProperty("java.io.tmpdir")), STALE_WITHOUT_PID, root);
        sweep.killed().forEach(progress);
        if (sweep.busyOwner() != null) {
            // #293: two runs on one project would write over each other's result
            throw new IllegalStateException("another logast apply (pid " + sweep.busyOwner() + ") is working on this project: "
                    + "wait for it to finish, or stop it");
        }
        progress.accept("copying the project to a scratch folder");
        Path work = copy(root);
        Path scratch = work.getParent();
        // a run stopped by SIGTERM (an OOM killer, Ctrl-C) skips the finally below: the hook still removes the copy (#283)
        Thread cleanup = new Thread(() -> deleteTree(scratch), "logast-scratch-cleanup");
        Runtime.getRuntime().addShutdownHook(cleanup);
        try {
            Result r = run(root, work, sel, write);
            onFinish.accept(work);
            return r;
        } finally {
            deleteTree(scratch);
            try {
                Runtime.getRuntime().removeShutdownHook(cleanup);
            } catch (IllegalStateException shuttingDown) {
                // already exiting: the hook runs and finds nothing left
            }
        }
    }

    /** The scratch folder's owner, so a later run can tell a dead run's leftover from a live run's copy (#283). */
    static final String PID_FILE = ".logast-pid";
    /** The project the scratch folder is a copy of (#293: one run per project). */
    static final String ROOT_FILE = ".logast-root";
    /** The step running and the heap in use, rewritten as the run goes (#293: what a killed run was doing). */
    static final String STEP_FILE = ".logast-step";

    /**
     * @param removed   scratch folders removed
     * @param killed    one line per removed folder whose run was stopped mid-way: its step and heap
     * @param busyOwner the pid of a live run working on the same project, or null
     */
    record Sweep(int removed, List<String> killed, Long busyOwner) {
    }
    /** A scratch folder without a pid file (logast 0.4 and earlier) counts as left over after this long. */
    static final java.time.Duration STALE_WITHOUT_PID = java.time.Duration.ofHours(6);

    /**
     * Removes scratch folders earlier runs left in {@code tmp} (#283): killed with SIGKILL, the shutdown hook never ran.
     * A folder goes when the process its pid file names is gone, or when it has no pid file and is older than
     * {@code withoutPid}. A live run's folder, and anything unreadable, stays.
     *
     * @param root the project about to be applied (null: none), to find a live run on it
     */
    static Sweep sweepStale(Path tmp, java.time.Duration withoutPid, Path root) {
        int removed = 0;
        List<String> killed = new ArrayList<>();
        Long busy = null;
        String project = root == null ? null : root.toAbsolutePath().normalize().toString();
        try (var dirs = Files.newDirectoryStream(tmp, "logast-apply-*")) {
            for (Path d : dirs) {
                if (!Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                boolean stale;
                try {
                    Path pid = d.resolve(PID_FILE);
                    if (Files.exists(pid)) {
                        long owner = Long.parseLong(Files.readString(pid).strip());
                        stale = owner != ProcessHandle.current().pid() && ProcessHandle.of(owner).map(h -> !h.isAlive()).orElse(true);
                        Path of = d.resolve(ROOT_FILE);
                        if (!stale && owner != ProcessHandle.current().pid() && project != null && Files.exists(of)
                                && Files.readString(of).strip().equals(project)) {
                            busy = owner;
                        }
                        Path step = d.resolve(STEP_FILE);
                        if (stale && Files.exists(step)) {
                            killed.add("an earlier apply (pid " + owner + ") was stopped during " + Files.readString(step).strip()
                                    + ": if the system ran out of memory, give logast more (LOGAST_OPTS=-Xmx4g) or run fewer packs (--pack)");
                        }
                    } else {
                        stale = Files.getLastModifiedTime(d).toInstant().isBefore(java.time.Instant.now().minus(withoutPid));
                    }
                } catch (IOException | NumberFormatException e) {
                    stale = false;
                }
                if (stale) {
                    deleteTree(d);
                    removed++;
                }
            }
        } catch (IOException ignored) {
            // no temp folder to sweep
        }
        return new Sweep(removed, killed, busy);
    }

    /** #293: the step now running and the heap in use, for the note a later run prints if this one gets killed. */
    private static void recordStep(Path scratch, Rules.Step step) {
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        try {
            Files.writeString(scratch.resolve(STEP_FILE), "step " + step.name().toLowerCase(Locale.ROOT) + " with "
                    + usedMb + " MB of " + rt.maxMemory() / (1024 * 1024) + " MB heap in use");
        } catch (IOException ignored) {
            // only a note
        }
    }

    /**
     * #279: the test sources that mention a changed class and no longer compile against the result (a fix changed a
     * signature they call), as CHECK FAILED lines: the change stays, the test needs the same change.
     */
    private List<String> brokenTests(Path root, Path work, List<String> changed) {
        if (compile == null) {
            return List.of();
        }
        Set<String> names = new HashSet<>();
        for (String f : changed) {
            if (f.endsWith(".java") && f.contains("src/main/java/") && apiChanged(root.resolve(f), work.resolve(f))) {
                names.add(f.substring(f.lastIndexOf('/') + 1, f.length() - ".java".length()));
            }
        }
        if (names.isEmpty()) {
            return List.of();
        }
        java.util.regex.Pattern mentions = java.util.regex.Pattern.compile("\\b(" + String.join("|", names) + ")\\b");
        List<String> tests = new ArrayList<>();
        try (var files = Files.walk(work)) {
            files.filter(p -> p.toString().endsWith(".java") && p.toString().replace('\\', '/').contains("/src/test/java/"))
                    .forEach(p -> {
                        try {
                            if (mentions.matcher(Files.readString(p)).find()) {
                                tests.add(work.relativize(p).toString().replace('\\', '/'));
                            }
                        } catch (IOException ignored) {
                            // unreadable: not checked
                        }
                    });
        } catch (IOException e) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        compile.brokenTests(root, work, tests).forEach((file, error) -> out.add(file + ": no longer compiles against the "
                + "changed code (" + error + "): the fix changed a signature this test calls, update the test the same way"));
        return out;
    }

    /**
     * #279, #308: whether a change can break a test: the class's API (signatures, supertypes, annotations, instance
     * fields, {@link dev.logast.parse.ApiDigest}) differs. Log lines, MDC scopes and private helpers cannot; compiling
     * every test that mentions a changed class took most of an apply on a large service.
     */
    private static boolean apiChanged(Path before, Path after) {
        if (!Files.exists(before) || !Files.exists(after)) {
            return true;   // a new or removed class: what tests see changed
        }
        try {
            String was = dev.logast.parse.ApiDigest.of(dev.logast.parse.SourceText.read(before));
            String now = dev.logast.parse.ApiDigest.of(dev.logast.parse.SourceText.read(after));
            return was == null || !was.equals(now);
        } catch (IOException e) {
            return true;
        }
    }

    /** Passes over the whole pipeline before apply stops (each pass only applies what is still open). */
    static final int MAX_PASSES = 3;

    private Result run(Path root, Path work, Selection sel, boolean write) {
        return new Run(work, sel).go(root, write);
    }

    /**
     * One apply on the scratch copy: the passes over the pipeline (docs/pipeline-plan.md), each step's rounds, and what
     * they record. Each step works on what the previous steps really produced; the pipeline repeats until a pass applies
     * nothing, as a fix in a later step can open up one in an earlier step (a scope moved up makes a lower one redundant,
     * then its messages can drop the key). One run = the end state.
     */
    private final class Run {
        private final Path work;
        private Selection sel;
        private ScanResult scan;
        private final List<String> applied = new ArrayList<>();
        private final Map<String, String> rejected = new LinkedHashMap<>();
        private final List<String> indirect = new ArrayList<>();
        private final Set<String> everSelected = new HashSet<>();
        private int round = 0;
        private boolean first = true;
        /** Orders waiting for a dependency of a later step. */
        private final Set<String> carried = new LinkedHashSet<>();
        private final Map<String, String> stepDiffs = new LinkedHashMap<>();
        private final Set<String> appliedInPass = new HashSet<>();
        /**
         * Guards: an order that is back unchanged right after its own edit did not see that edit (it would only add the
         * same lines again: blocked and reported); a pass that ends where an earlier one ended undoes itself (stopped).
         */
        private final List<String> selfCheck = new ArrayList<>();
        private final Set<String> stuck = new HashSet<>();
        private final Set<String> passEnds = new HashSet<>();
        /** What apply did and what each round's rescan brought up: .logast/reports/apply-trace.txt. */
        private final List<String> trace = new ArrayList<>();
        /** An id applied in an earlier pass runs again only if another order changed one of its files since. */
        private final Map<String, Integer> appliedAt = new HashMap<>();
        /** file -> id -> the round it was last edited. */
        private final Map<String, Map<String, Integer>> touches = new HashMap<>();
        /** Where each applied order sits, followed through the later rounds' edits to the line in the result (#72). */
        private final Map<String, TreeMap<Integer, Set<String>>> fixed = new TreeMap<>();
        private int seq = 0;

        Run(Path work, Selection sel) {
            this.work = work;
            this.sel = sel;
        }

        Result go(Path root, boolean write) {
            progress.accept("analysing the code");
            scan = scanner.apply(work);
            // written texts go to the lines they were written for, also when a line's id shifted since
            if (!sel.fills().isEmpty()) {
                sel = new Selection(sel.auto(), sel.only(), FillsFile.match(sel.fills(), scan.orders()), sel.lenientFills());
            }
            ScanResult before = scan;
            passEnds.add(treeHash(work));
            trace.add("scan: " + scan.orders().size() + " orders");
            for (int pass = 1; pass <= MAX_PASSES; pass++) {
                if (!pass(pass)) {
                    break;
                }
            }
            carried.forEach(id -> rejected.put(id, "waits for a fix that was not applied"));

            progress.accept("~comparing with the original (" + plural(applied.size(), "fix") + " in " + plural(round, "round") + ")");
            List<String> changed = new ArrayList<>();
            StringBuilder diff = new StringBuilder();
            compare(root, work, changed, diff);
            // #316: the changes that can change what the service does (the rest only changes its logging); before the
            // write-back, while root still holds the code as it was
            List<String> behavioural = new ChangeClassifier(null).classify(root, work, changed).stream()
                    .map(ChangeClassifier.Behavioural::describe).toList();
            progress.accept("~checking the result");
            List<String> violations = new ArrayList<>(selfCheck);
            if (!changed.isEmpty()) {
                violations.addAll(invariants.apply(work, changed));
                violations.addAll(brokenTests(root, work, changed));
            }
            if (write) {
                copyBack(root, work, changed);
            }
            return new Result(applied, rejected, indirect, changed, diff.toString(), round, before, scan, violations, stepDiffs, trace, fixed,
                    behavioural, List.copyOf(keptByHand));
        }

        /** One pass over the steps; false when the passes stop (nothing applied, --until, or a pass undoing another). */
        private boolean pass(int pass) {
            // an order applied in an earlier pass may come back when a later fix changed its code again (a scope unwrapped,
            // then another one put there): within a pass an id is applied once, a new pass may apply it again
            appliedInPass.clear();
            everSelected.clear();
            boolean[] announced = {pass == 1};
            trace.add("== pass " + pass);
            for (Rules.Step step : Rules.Step.values()) {
                if (until != null && step.ordinal() > until.ordinal()) {
                    break;
                }
                if (skipped.contains(step)) {
                    continue;
                }
                step(step, pass, announced);
            }
            if (appliedInPass.isEmpty() || until != null) {
                return false;
            }
            if (!passEnds.add(treeHash(work))) {
                selfCheck.add("pass " + pass + " ended where an earlier pass ended: its fixes undo each other " + new TreeSet<>(appliedInPass));
                return false;
            }
            if (pass == MAX_PASSES) {
                progress.accept("still changing after " + MAX_PASSES + " passes: the rest is left for the next run");
            }
            return true;
        }

        /** A step's rounds: apply what is selected, rescan, select what the rescan brought up, until nothing is left. */
        private void step(Rules.Step step, int pass, boolean[] announced) {
            recordStep(work.getParent(), step);
            Map<String, String> beforeStep = new LinkedHashMap<>();   // content of each file before this step touched it
            Set<String> pending = new LinkedHashSet<>(carried);
            carried.clear();
            Set<String> skip = new HashSet<>(appliedInPass);
            skip.addAll(stuck);
            if (pass > 1) {
                skipUnseenOwnFix(step, skip);
            }
            select(scan, sel, pending, everSelected, rejected, skip, step, first);
            if (pending.isEmpty()) {
                return;
            }
            if (!announced[0]) {
                progress.accept("pass " + pass + ": earlier fixes opened up new ones");
                announced[0] = true;
            }
            String name = step.name().toLowerCase(Locale.ROOT);
            int stepRounds = 0;
            while (!pending.isEmpty() && stepRounds < MAX_ROUNDS) {
                stepRounds++;
                if (!round(step, name, stepRounds, pending, beforeStep)) {
                    break;
                }
            }
            int rounds = stepRounds;
            pending.forEach(id -> rejected.put(id, "could not be applied without conflicts after " + rounds + " rounds; rescan and retry"));
            String d = diffOf(work, beforeStep);
            if (!d.isEmpty()) {
                stepDiffs.merge(name, d, String::concat);
            }
        }

        /** An order applied in an earlier pass whose files nothing else changed since is back: it did not see its own fix. */
        private void skipUnseenOwnFix(Rules.Step step, Set<String> skip) {
            for (WorkOrder o : scan.orders()) {
                Integer at = appliedAt.get(o.id());
                if (at == null || stuck.contains(o.id()) || Rules.stepOf(o.rule()) != step) {
                    continue;
                }
                boolean touchedByOthers = o.edits().stream().map(Edit::file).distinct().anyMatch(f -> touches.getOrDefault(f, Map.of())
                        .entrySet().stream().anyMatch(t -> !t.getKey().equals(o.id()) && t.getValue() >= at));
                if (!touchedByOthers) {
                    skip.add(o.id());
                    if (stuck.add(o.id())) {
                        selfCheck.add(o.file() + ":" + o.line() + "  " + o.rule() + " (" + o.id() + ") is back although "
                                + "nothing else changed its code: it did not see its own fix (applied once, not repeated)");
                    }
                }
            }
        }

        /** What a round applies: the orders that validate, whose dependencies are met and whose edits do not conflict. */
        private record Batch(List<Edit> accepted, List<String> ids, Map<String, List<Edit>> editsOf) {
        }

        private Batch batch(Rules.Step step, Set<String> pending, Map<String, WorkOrder> byId) {
            List<WorkOrder> batch = pending.stream().map(byId::get)
                    .sorted(Comparator.comparingInt(Applier::priority).thenComparing(WorkOrder::id)).toList();
            List<Edit> accepted = new ArrayList<>();
            List<String> acceptedIds = new ArrayList<>();
            Map<String, List<Edit>> editsOf = new HashMap<>();
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
                    // a dependency of a later step: this order waits for that step
                    boolean later = missingDeps.stream().allMatch(d -> byId.containsKey(d)
                            && Rules.stepOf(byId.get(d).rule()).ordinal() > step.ordinal());
                    if (later) {
                        carried.add(o.id());
                    } else if (missingDeps.stream().anyMatch(keptByHand::contains)) {
                        keptByHand.add(o.id());   // #317: builds on a fix someone changed back: left with it
                    } else {
                        rejected.put(o.id(), "depends on " + missingDeps + ": select it in the same apply");
                    }
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
                editsOf.put(o.id(), rendered);
            }
            return new Batch(accepted, acceptedIds, editsOf);
        }

        /** One round of a step: apply a batch, record it, rescan and select again; false when nothing could be applied. */
        private boolean round(Rules.Step step, String name, int stepRounds, Set<String> pending, Map<String, String> beforeStep) {
            round++;
            progress.accept("~" + name + ", round " + stepRounds + ": " + plural(pending.size(), "fix") + " to apply, " + applied.size() + " done");
            Map<String, WorkOrder> byId = new LinkedHashMap<>();
            scan.orders().forEach(o -> byId.put(o.id(), o));
            for (Iterator<String> it = pending.iterator(); it.hasNext(); ) {
                String id = it.next();
                if (!byId.containsKey(id)) {
                    it.remove();
                    if (first && stepRounds == 1) {
                        rejected.put(id, "no such order in a fresh scan (stale orders file? rescan)");
                    } else {
                        indirect.add(id);   // an earlier fix took care of it
                    }
                }
            }
            first = false;
            Batch b = batch(step, pending, byId);
            List<Edit> accepted = b.accepted();
            List<String> acceptedIds = b.ids();
            Map<String, List<Edit>> editsOf = b.editsOf();
            if (acceptedIds.isEmpty()) {
                return false;
            }
            progress.accept(name + ", round " + stepRounds + ": applying " + plural(acceptedIds.size(), "fix") + " to "
                    + plural((int) accepted.stream().map(Edit::file).distinct().count(), "file") + " (" + applied.size() + " done so far)");
            for (Edit e : accepted) {
                if (!beforeStep.containsKey(e.file())) {   // null = the step creates the file
                    Path p = work.resolve(e.file());
                    beforeStep.put(e.file(), Files.exists(p) ? text(bytes(p)) : null);
                }
            }
            trace.add(name + " round " + stepRounds + ": applying " + acceptedIds.stream().map(id -> describe(byId.get(id))).toList());
            Map<String, String> signatures = new HashMap<>();
            acceptedIds.forEach(id -> signatures.put(id, signature(byId.get(id))));
            seq++;
            Map<String, String> roundBefore = new HashMap<>();
            accepted.stream().map(Edit::file).filter(f -> f.endsWith(".java")).distinct().forEach(f -> {
                Path p = work.resolve(f);
                roundBefore.put(f, Files.exists(p) ? text(bytes(p)) : null);
            });
            Map<String, String> failed = applyRound(work, acceptedIds, editsOf);
            failed.forEach((id, why) -> {
                WorkOrder o = byId.get(id);
                selfCheck.add(o.file() + ":" + o.line() + "  " + o.rule() + " (" + id + ") " + why);
            });
            List<String> broken = failed.keySet().stream().flatMap(id -> editsOf.get(id).stream().map(Edit::file)).distinct().toList();
            record(acceptedIds, byId, failed, pending);
            roundBefore.forEach((f, old) -> {
                Path p = work.resolve(f);
                LineMap.follow(fixed, f, old, Files.exists(p) ? text(bytes(p)) : null);
            });
            rescan(step, name, stepRounds, pending, signatures, broken);
            return true;
        }

        /** What a round applied: the ids, when and where (for the next passes and the ledger), or why one failed. */
        private void record(List<String> acceptedIds, Map<String, WorkOrder> byId, Map<String, String> failed, Set<String> pending) {
            for (String id : acceptedIds) {
                WorkOrder o = byId.get(id);
                if (failed.containsKey(id)) {
                    rejected.put(id, failed.get(id));
                } else {
                    appliedInPass.add(id);
                    appliedAt.put(id, seq);
                    for (Edit e : o.edits()) {
                        touches.computeIfAbsent(e.file(), k -> new HashMap<>()).put(id, seq);
                    }
                    if (!applied.contains(id)) {
                        applied.add(id);
                    }
                }
                pending.remove(id);
            }
            for (String id : acceptedIds) {
                WorkOrder o = byId.get(id);
                if (!failed.containsKey(id) && o.file() != null && o.file().endsWith(".java") && o.line() > 0) {
                    fixed.computeIfAbsent(o.file(), k -> new TreeMap<>()).computeIfAbsent(o.line(), k -> new LinkedHashSet<>())
                            .add(o.rule() + " " + o.id());
                }
            }
        }

        /** The scan after a round: an order back unchanged did not see its own fix; what appeared is traced and selected. */
        private void rescan(Rules.Step step, String name, int stepRounds, Set<String> pending, Map<String, String> signatures,
                            List<String> broken) {
            progress.accept("~" + name + ", round " + stepRounds + ": checking the changed code (" + applied.size() + " done)");
            Set<String> previous = new HashSet<>();
            scan.orders().forEach(o -> previous.add(o.id()));
            scan = scanner.apply(work);
            for (WorkOrder o : scan.orders()) {
                if (signatures.containsKey(o.id()) && !broken.contains(o.file()) && signature(o).equals(signatures.get(o.id()))
                        && stuck.add(o.id())) {
                    selfCheck.add(o.file() + ":" + o.line() + "  " + o.rule() + " (" + o.id() + ") did not see its own fix: "
                            + "applied once, not repeated");
                }
            }
            List<String> appeared = scan.orders().stream().filter(o -> !previous.contains(o.id()) && o.mechanical())
                    .map(Applier::describe).toList();
            if (!appeared.isEmpty()) {
                trace.add("  rescan: new " + appeared);
            }
            if (sel.auto()) {
                select(scan, sel, pending, everSelected, rejected, new HashSet<>(applied), step, false);
            }
        }
    }

    /** "RULE id file:line" for the trace. */
    private static String describe(WorkOrder o) {
        return o.rule() + " " + o.id() + " " + o.file() + ":" + o.line();
    }

    /** What an order would write, independent of where it sits: unchanged means it would write the same again. */
    private static String signature(WorkOrder o) {
        return o.rule() + "|" + o.title() + "|" + o.edits().stream().map(e -> e.file() + "|" + e.kind() + "|" + e.text()).sorted().toList();
    }

    /** Content hash of the scratch copy's source and resource files. */
    private static String treeHash(Path work) {
        try (var s = Files.walk(work)) {
            StringBuilder sb = new StringBuilder();
            for (Path p : s.filter(Files::isRegularFile).filter(p -> !p.toString().contains("/.logast/")).sorted().toList()) {
                sb.append(work.relativize(p)).append('\0')
                        .append(dev.logast.parse.Hashing.sha256(new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.ISO_8859_1)))
                        .append('\n');
            }
            return dev.logast.parse.Hashing.sha256(sb.toString());
        } catch (IOException | java.io.UncheckedIOException e) {
            return String.valueOf(System.nanoTime());   // unreadable (binary): no oscillation check
        }
    }

    private void select(ScanResult scan, Selection sel, Set<String> pending, Set<String> everSelected,
                        Map<String, String> rejected, Set<String> applied, Rules.Step step, boolean first) {
        for (WorkOrder o : scan.orders()) {
            if (Rules.stepOf(o.rule()) != step) {
                continue;   // picked up in its own step, against the code the earlier steps produced
            }
            if (step != Rules.Step.SETUP && !inPaths.test(o.file())) {
                continue;   // #288: outside the batch this run applies
            }
            boolean wanted = sel.fills().containsKey(o.id()) || sel.only().contains(o.id())
                    || (sel.auto() && o.mechanical());
            // an order back that an earlier apply made, in a file that is no longer as that apply left it: changed back
            // by hand. In a file still as logast wrote it, the order came back for another reason, and applies
            if (wanted && declined.contains(o.id()) && o.file() != null && changedSince.test(o.file())
                    && !sel.only().contains(o.id()) && !sel.fills().containsKey(o.id())) {
                keptByHand.add(o.id());   // #317: applied before, changed back since: the person's version stands
                wanted = false;
            }
            if (!sel.only().isEmpty() && !sel.only().contains(o.id()) && !sel.fills().containsKey(o.id())) {
                wanted = false;
            }
            if (wanted && !applied.contains(o.id()) && !rejected.containsKey(o.id()) && everSelected.add(o.id())) {
                pending.add(o.id());
            }
        }
        Set<String> present = new HashSet<>();
        scan.orders().forEach(o -> present.add(o.id()));
        // #56: an --only id that matches no order is said, like a stale messages.yaml entry, not silently dropped
        for (String id : sel.only()) {
            if (!present.contains(id) && first && everSelected.add(id)) {
                pending.add(id);
            }
        }
        for (String id : sel.fills().keySet()) {
            if (present.contains(id) || sel.lenientFills() || !first) {
                continue;   // selected in its step above; a finished entry of .logast/decisions/messages.yaml; or already reported
            }
            if (everSelected.add(id)) {
                pending.add(id); // reported as stale: no such order
            }
        }
    }

    private static String plural(int n, String word) {
        return n + " " + (n == 1 ? word : word.endsWith("x") ? word + "es" : word + "s");
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
                String written = fill == null || fill.slots().get(s.getKey()) == null ? "" : fill.slots().get(s.getKey()).strip();
                // #231: nobody wrote a text: the generated one, marked so MESSAGE_SUGGESTED asks for better words
                boolean generated = written.isEmpty() && s.getValue().generated() != null;
                String value = generated ? s.getValue().generated() : written;
                List<String> keys = new ArrayList<>(s.getValue().keys());
                if (s.getKey().equals(o.context().argsSlot())) {
                    keys.addAll(extra);
                }
                String rendered = Text.javaString(value);
                rendered = rendered.substring(1, rendered.length() - 1) + MessageTemplate.suffix(keys);
                String placeholder = "{{" + s.getKey() + "}}";
                int at = text.indexOf(placeholder);
                text = text.replace(placeholder, rendered);
                if (generated && at >= 0) {
                    text = markWording(text, at + rendered.length());
                }
            }
            text = text.replace("{{ARGS}}", String.join("", extra.stream().map(a -> ", " + a).toList()));
            out.add(new Edit(e.file(), e.kind(), e.start(), e.end(), text));
        }
        return out;
    }

    /** #231: {@code // logast:wording} after the statement that ends on the line of {@code from}, when it ends there. */
    static String markWording(String text, int from) {
        int eol = text.indexOf('\n', from);
        int end = eol < 0 ? text.length() : eol;
        if (!text.substring(from, end).stripTrailing().endsWith(";")) {
            return text;
        }
        return text.substring(0, end).stripTrailing() + " " + Slot.WORDING_MARKER + text.substring(end);
    }

    /**
     * The new text of every file the edits touch (descending offsets), computed in memory: nothing is written. A file
     * mapped to null is deleted. Java files that no longer parse go to {@code broken} instead.
     */
    private static Map<String, String> contents(Path work, List<Edit> edits, Map<String, String> broken) {
        Map<String, List<Edit>> byFile = new TreeMap<>();
        edits.forEach(e -> byFile.computeIfAbsent(e.file(), k -> new ArrayList<>()).add(e));
        Map<String, String> out = new TreeMap<>();
        for (var entry : byFile.entrySet()) {
            Path p = work.resolve(entry.getKey());
            try {
                List<Edit> fileEdits = entry.getValue();
                if (fileEdits.stream().anyMatch(e -> e.kind().equals("delete"))) {
                    out.put(entry.getKey(), null);
                    continue;
                }
                List<Edit> merges = fileEdits.stream().filter(Edit::merge).toList();
                Optional<Edit> create = fileEdits.stream().filter(e -> e.kind().equals("create")).findFirst();
                if (create.isPresent() || merges.size() == fileEdits.size() && !Files.exists(p)) {
                    String text = create.map(Edit::text).orElse("");
                    for (Edit m : merges) {
                        text = text.isBlank() ? m.text() : mergeInto(text, m);
                    }
                    out.put(entry.getKey(), text);
                    continue;
                }
                String before = dev.logast.parse.SourceText.read(p);
                StringBuilder sb = new StringBuilder(before);
                List<Edit> sorted = new ArrayList<>(fileEdits.stream().filter(e -> !e.merge()).toList());
                // descending start; at equal start apply ranges before inserts so inserted text lands in front
                sorted.sort(Comparator.comparingInt(Edit::start).reversed()
                        .thenComparing(e -> e.start() == e.end() ? 1 : 0));
                for (Edit e : sorted) {
                    sb.replace(e.start(), e.end(), e.text());
                }
                String after = sb.toString();
                for (Edit m : merges) {   // against the text as it is now, so several orders can touch one config file per round
                    after = after.isBlank() ? m.text() : mergeInto(after, m);
                }
                if (p.toString().endsWith(".java")) {
                    after = ImportFixer.fix(before, after, ImportFixer.learn(edits.stream().map(Edit::text).toList()), siblingTypes(p));
                }
                if (p.toString().endsWith(".java") && !ProjectParser.parsesCleanly(work, work.relativize(p), after)) {
                    broken.put(entry.getKey(), "does not parse");
                    continue;
                }
                out.put(entry.getKey(), after);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        return out;
    }

    /** #248: the types of the file's package declared in its other files (one top-level type per file, named after it). */
    private static Set<String> siblingTypes(Path file) {
        Path dir = file.getParent();
        if (dir == null) {
            return Set.of();
        }
        try (var s = Files.list(dir)) {
            String own = file.getFileName().toString();
            return s.map(x -> x.getFileName().toString()).filter(n -> n.endsWith(".java") && !n.equals(own))
                    .map(n -> n.substring(0, n.length() - ".java".length())).collect(java.util.stream.Collectors.toSet());
        } catch (java.io.IOException e) {
            return Set.of();
        }
    }

    private static String mergeInto(String text, Edit m) {
        return m.kind().equals("properties-merge") ? dev.logast.rules.ServiceYaml.mergeProperties(text, m.text(), m.start() == 1)
                : dev.logast.rules.SetupRules.mergeYaml(text, m.text(), m.start() == 1);
    }

    private static void write(Path work, Map<String, String> contents) {
        try {
            for (var e : contents.entrySet()) {
                Path p = work.resolve(e.getKey());
                if (e.getValue() == null) {
                    Files.deleteIfExists(p);
                } else {
                    Files.createDirectories(p.getParent());
                    dev.logast.parse.SourceText.write(p, e.getValue());   // in the file's charset and line ending
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Applies the round's orders as a whole (#44, #54): computes every file in memory, and rejects each order that
     * touches a file which no longer parses or has compile errors it did not have before; then recomputes without them,
     * until what is left is clean. Only then are files written, so an order is applied in all its files or in none.
     * Returns the rejected orders with the reason.
     */
    private Map<String, String> applyRound(Path work, List<String> ids, Map<String, List<Edit>> editsOf) {
        Map<String, String> rejectedNow = new LinkedHashMap<>();
        List<String> live = new ArrayList<>(ids);
        while (true) {
            List<Edit> edits = new ArrayList<>();
            for (String id : live) {
                editsOf.get(id).stream().filter(e -> !edits.contains(e)).forEach(edits::add);
            }
            Map<String, String> broken = new LinkedHashMap<>();
            Map<String, String> out = contents(work, edits, broken);
            if (broken.isEmpty() && compile != null) {
                Map<String, String> java = new LinkedHashMap<>();
                out.forEach((f, t) -> {
                    if (t != null && f.endsWith(".java")) {
                        java.put(f, t);
                    }
                });
                compile.newErrors(work, java).forEach((f, err) -> broken.put(f, "does not compile: " + err));
                if (compile.problem() != null && !lombokWarned) {
                    lombokWarned = true;   // #81: say once why the compile check is limited
                    progress.accept(compile.lombokProblem() == null || compile.problem().contains("newer Java")
                            ? "compile check skipped: " + compile.problem()
                            : "Lombok is on the classpath but does not run in logast's compile check, so lines "
                            + "using Lombok loggers are not compile-checked (javac: " + compile.lombokProblem() + ")");
                }
            }
            if (broken.isEmpty()) {
                write(work, out);
                return rejectedNow;
            }
            for (Iterator<String> it = live.iterator(); it.hasNext(); ) {
                String id = it.next();
                List<String> files = editsOf.get(id).stream().map(Edit::file).filter(broken::containsKey).distinct().toList();
                if (!files.isEmpty()) {
                    rejectedNow.put(id, "produced code that " + files.stream().map(f -> f + " " + broken.get(f)).toList()
                            + " (not applied in any of its files)");
                    it.remove();
                }
            }
            if (live.isEmpty()) {
                return rejectedNow;
            }
        }
    }

    // ---- scratch copy -------------------------------------------------------------------------

    static Path copy(Path root) {
        try {
            // the copy keeps the project's folder name: what is derived from it (the service name when no
            // settings.gradle names it) must come out the same as on the project itself
            Path name = root.toAbsolutePath().normalize().getFileName();
            Path scratch = Files.createTempDirectory("logast-apply-");
            Files.writeString(scratch.resolve(PID_FILE), String.valueOf(ProcessHandle.current().pid()));
            Files.writeString(scratch.resolve(ROOT_FILE), root.toAbsolutePath().normalize().toString());
            Path work = scratch.resolve(name == null ? "project" : name.toString());
            Files.createDirectories(work);
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(root) && skipped(root, dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Files.createDirectories(work.resolve(root.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    // sockets, pipes and devices (e.g. a tool's daemon.sock) cannot be copied and are never edited
                    if ((attrs.isRegularFile() || attrs.isSymbolicLink())
                            && (inSource(root.relativize(file).toString()) || !BUILD_OUTPUT.contains(String.valueOf(file.getParent().getFileName())))) {
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

    private static final Set<String> BUILD_OUTPUT = Set.of("build", "target");

    /**
     * Left out of the scratch copy: build output and tool folders, except the generated sources in build/ and target/
     * (#51: their types resolve the analysis; they are never compared or written back).
     */
    /** Inside a source tree ({@code …/src/…}): a "build" or "out" folder there is a package (…common.build), not output. */
    static boolean inSource(String rel) {
        String[] segs = rel.replace('\\', '/').split("/");
        for (int i = 0; i < segs.length - 1; i++) {
            if (segs[i].equals("src")) {
                return true;
            }
        }
        return false;
    }

    private static boolean skipped(Path root, Path dir) {
        if (inSource(root.relativize(dir).toString())) {
            return false;
        }
        String name = dir.getFileName().toString();
        if (BUILD_OUTPUT.contains(String.valueOf(dir.getParent().getFileName()))) {
            return !name.equals("generated") && !name.equals("generated-sources");
        }
        return SKIP.contains(name) && !BUILD_OUTPUT.contains(name);
    }

    private static void compare(Path root, Path work, List<String> changed, StringBuilder diff) {
        Set<String> rels = new TreeSet<>();
        for (Path base : List.of(root, work)) {
            try (var s = Files.walk(base)) {
                s.filter(Files::isRegularFile)
                        .map(p -> base.relativize(p).toString().replace('\\', '/'))
                        .filter(r -> inSource(r) || Arrays.stream(r.split("/")).noneMatch(SKIP::contains))
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

    /** Unified diff of the files a step touched: their content before the step against their content now. */
    private static String diffOf(Path work, Map<String, String> before) {
        StringBuilder sb = new StringBuilder();
        for (var e : before.entrySet()) {
            Path p = work.resolve(e.getKey());
            String now = Files.exists(p) ? text(bytes(p)) : null;
            if (Objects.equals(now, e.getValue())) {
                continue;
            }
            Path rel = Path.of(e.getKey());
            try (InMemoryDiffEntry d = new InMemoryDiffEntry(e.getValue() == null ? null : rel, now == null ? null : rel, null,
                    e.getValue() == null ? "" : e.getValue(), now == null ? "" : now, Set.of())) {
                sb.append(d.getDiff());
            }
        }
        return sb.toString();
    }

    private static byte[] bytes(Path p) {
        try {
            return Files.exists(p) ? Files.readAllBytes(p) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The text logast works on, or null for binary content (fonts, PDFs, images in resources). */
    private static String text(byte[] b) {
        return dev.logast.parse.SourceText.textOrNull(b);
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

    private static void deleteTree(Path dir) {
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best effort cleanup of the scratch copy
        }
    }
}
