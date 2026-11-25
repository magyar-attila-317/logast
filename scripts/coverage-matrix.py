#!/usr/bin/env python3
"""#7: the coverage matrix (docs/coverage.md): which e2e scenarios contain each value logast distinguishes.

Reads the shared fixture (testing/e2e/before, used by most scenarios) and the Java text each e2e test writes itself
(E2eGoldenTest), and lists per value the scenarios whose code matches its pattern. A pattern is a code shape seen in
source text, so a row is "contains", not "asserts": read the scenario to see what it checks. Values no scenario
contains are the gaps. Run from the repository root:

    python3 scripts/coverage-matrix.py > docs/coverage.md
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEST = os.path.join(ROOT, "modules/cli/src/test/java/dev/logast/cli/E2eGoldenTest.java")
BEFORE = os.path.join(ROOT, "testing/e2e/before")

DIMENSIONS = [
    ("Entry", [
        ("HTTP (MVC)", r"@(Get|Post|Put|Delete|Patch|Request)Mapping"),
        ("@Scheduled", r"@Scheduled"),
        ("Kafka, single record", r"@KafkaListener(?![^\n]*batch)"),
        ("Kafka, batch", r"@KafkaListener\([^)]*batch|List<ConsumerRecord"),
        ("JMS", r"@JmsListener"),
        ("Rabbit", r"@RabbitListener"),
        ("@EventListener (sync)", r"@EventListener"),
        ("@Async", r"@Async"),
        ("@TransactionalEventListener", r"@TransactionalEventListener"),
        ("main / runner (CommandLineRunner, ApplicationRunner)", r"CommandLineRunner|ApplicationRunner"),
        ("reactive (Mono / Flux)", r"\bMono<|\bFlux<"),
        ("Quartz job", r"QuartzJobBean|implements\s+(org\.quartz\.)?Job\b"),
        ("Spring Batch (Tasklet, ItemProcessor / Writer)", r"\bTasklet\b|ItemProcessor<|ItemWriter<"),
        ("SQS", r"@SqsListener"),
        ("router functions (MVC / WebFlux)", r"RouterFunction"),
        ("Spring Integration @ServiceActivator", r"@ServiceActivator"),
        ("gRPC service", r"StreamObserver<"),
        ("Redis pub/sub listener", r"MessageListenerAdapter|implements\s+MessageListener\b"),
        ("Spring Cloud Stream functional consumer", r"Consumer<Message<|@Bean[^;]*\n[^;]*Consumer<"),
    ]),
    ("Call shape", [
        ("through an interface", r"\binterface\s+\w+"),
        ("abstract method", r"\babstract\s+\w[\w<>, ]*\s+\w+\("),
        ("lambda", r"->"),
        ("method reference", r"\w::\w"),
        ("anonymous class", r"new\s+\w+(<[^>]*>)?\(\)\s*\{"),
        ("enum constant body", r"enum\s+\w+[^{]*\{[^}]*\w+\s*(\([^)]*\))?\s*\{"),
        ("static helper / @UtilityClass", r"@UtilityClass|private\s+\w+\(\)\s*\{\s*\}"),
        ("Lombok accessor", r"@(Getter|Data|Value)\b"),
        ("another thread (executor, CompletableFuture)", r"Executor|CompletableFuture|new\s+Thread\("),
        ("event publish", r"publishEvent\("),
    ]),
    ("Value origin", [
        ("parameter as id", r"\(\s*(final\s+)?(String|Long|UUID|long)\s+\w*Id\b"),
        ("record accessor (x.id())", r"\.\w*[iI]d\(\)"),
        ("getter (x.getId())", r"\.get\w*Id\(\)"),
        ("field", r"this\.\w+Id\b"),
        ("loop variable", r"for\s*\(\s*(var|\w+)\s+\w+\s*:"),
        ("reassigned / null-checked value", r"if\s*\(\s*\w+\s*==\s*null\)"),
        ("request header", r"getHeader\(|@RequestHeader"),
    ]),
    ("Logging in place", [
        ("@Slf4j", r"@Slf4j"),
        ("plain SLF4J logger", r"LoggerFactory\.getLogger"),
        ("placeholders", r"log\.\w+\(\"[^\"]*\{\}"),
        ("[k=v] suffix", r"\[\w+=\{\}"),
        ("concatenation", r"log\.\w+\(\"[^\"]*\"\s*\+"),
        ("MDC.put", r"MDC\.put\("),
        ("MDC.putCloseable", r"MDC\.putCloseable\("),
        ("MDC.clear", r"MDC\.clear\("),
        ("MdcScope / ObservedContext already there", r"MdcScope\.|ObservedContext\."),
        ("// logast: markers", r"//\s*logast:"),
    ]),
]

ENVIRONMENT = [
    ("JDK 21 / 25", "every scenario: `:cli:e2e` (JDK 25) and `:cli:e2eJdk21`"),
    ("Lombok on the classpath or not", "every scenario: the e2e variants run with and without Lombok"),
    ("class folders on the classpath", "every scenario: a variant with the project's own class folders"),
    ("a library type logast cannot see", "`testing/e2e/platform` (the platform scenarios)"),
    ("Linux / macOS", "CI: Linux on every push, macOS nightly"),
]


def scenarios():
    """(name, text) per e2e test method: its own text plus e2e/before when it migrates the shared fixture."""
    src = open(TEST, encoding="utf-8").read()
    before = []
    for d, _, files in os.walk(os.path.join(BEFORE, "src")):
        for f in files:
            if f.endswith(".java"):
                before.append(open(os.path.join(d, f), encoding="utf-8").read())
    shared = "\n".join(before)
    out = [("e2e/before (most scenarios)", shared)]
    # every method of the test class (tests and the helpers that write fixtures), each up to the next one
    header = re.compile(r"\n((?:    @[\w.]+(?:\([^\n]*\))?\n)*)    (?:(?:public|private|protected|static|final)\s+)*"
                        r"[\w<>\[\]?.][\w<>\[\], ?.]*\s+(\w+)\([^)]*\)\s*(?:throws [\w., ]+)?\s*\{")
    marks = [(m.start(), m.group(2), "@Test" in m.group(1) or "ParameterizedTest" in m.group(1)) for m in header.finditer(src)]
    methods = {}
    for i, (at, name, test) in enumerate(marks):
        end = marks[i + 1][0] if i + 1 < len(marks) else len(src)
        body = src[at:end]
        methods.setdefault(name, []).append((test, body))
    def blocks(body):
        return "\n".join(re.findall(r'"""(.*?)"""', body, re.S))
    helpers = {n: "\n".join(blocks(b) for t, b in v if not t) for n, v in methods.items()}
    for name, versions in methods.items():
        for test, body in versions:
            if not test:
                continue
            text = blocks(body)
            # the fixtures the helpers it calls write (one level)
            for h, htext in helpers.items():
                if htext.strip() and re.search(r"\b" + re.escape(h) + r"\(", body):
                    text += "\n" + htext
            if text.strip():
                out.append((name, text))
    return out


def main():
    found = scenarios()
    w = sys.stdout.write
    w("# Coverage matrix (#7)\n\n")
    w("Which e2e scenarios contain each value logast distinguishes. Generated by `python3 scripts/coverage-matrix.py`\n")
    w("from the shared fixture (`testing/e2e/before`, which most scenarios migrate) and the Java each scenario writes itself.\n")
    w("A row means the code shape is there, not what is asserted about it: read the scenario. A value with no scenario is a\n")
    w("gap; the bugs found so far came from two values meeting, so a gap here is where a combination is untested.\n\n")
    gaps = []
    for dim, values in DIMENSIONS:
        w("## " + dim + "\n\n| value | scenarios | in e2e/before |\n|---|---|---|\n")
        for label, pattern in values:
            rx = re.compile(pattern)
            hits = [n for n, t in found[1:] if rx.search(t)]
            shared = bool(rx.search(found[0][1]))
            if not hits and not shared:
                gaps.append(dim + ": " + label)
            shown = ", ".join("`" + h + "`" for h in hits[:4]) + (" and %d more" % (len(hits) - 4) if len(hits) > 4 else "")
            w("| %s | %s | %s |\n" % (label, shown or "—", "yes" if shared else "—"))
        w("\n")
    w("## Environment\n\n| value | covered by |\n|---|---|\n")
    for label, how in ENVIRONMENT:
        w("| %s | %s |\n" % (label, how))
    w("\n## Gaps\n\n")
    w("".join("- " + g + "\n" for g in gaps) if gaps else "None found by these patterns.\n")


if __name__ == "__main__":
    main()
