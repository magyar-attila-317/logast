package dev.logast.rules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** #61: suppression comments name rules exactly. */
class SuppressionsTest {

    @Test
    void ruleIdsMatchExactlyNotAsSubstrings() {
        assertThat(Suppressions.lineIgnores("x(); // logast:ignore METRIC_TAG_KEYS_MISMATCH", "METRIC_TAG_KEY")).isFalse();
        assertThat(Suppressions.lineIgnores("x(); // logast:ignore METRIC_TAG_KEY", "METRIC_TAG_KEYS_MISMATCH")).isFalse();
        assertThat(Suppressions.lineIgnores("x(); // logast:ignore LOG_FORMAT_X", "LOG_FORMAT")).isFalse();
        assertThat(Suppressions.lineIgnores("x(); // logast:ignore METRIC_TAG_KEY", "METRIC_TAG_KEY")).isTrue();
    }

    @Test
    void existingSyntaxKeepsWorking() {
        assertThat(Suppressions.lineIgnores("x(); // logast:ignore", "LOG_FORMAT")).isTrue();
        assertThat(Suppressions.lineIgnores("x(); /* logast:ignore */", "LOG_FORMAT")).isTrue();
        assertThat(Suppressions.lineIgnores("// logast:ignore LOG_LEVEL LOG_FORMAT", "LOG_FORMAT")).isTrue();
        assertThat(Suppressions.lineIgnores("// logast:ignore LOG_LEVEL, LOG_FORMAT", "LOG_FORMAT")).isTrue();
        assertThat(Suppressions.lineIgnores("// logast:ignore LOG_LEVEL,LOG_FORMAT -- legacy parser", "LOG_FORMAT")).isTrue();
        assertThat(Suppressions.lineIgnores("x(); /* logast:ignore LOG_FORMAT */ y();", "LOG_FORMAT")).isTrue();
        // a marker before the ignore (what a metrics "skip" leaves behind)
        assertThat(Suppressions.lineIgnores("x(); // logast: METRIC_OUTCOME_UNMEASURED OUM-027987 // logast:ignore METRIC_OUTCOME_UNMEASURED",
                "METRIC_OUTCOME_UNMEASURED")).isTrue();
    }

    @Test
    void theRuleListEndsAtTheFirstWordThatIsNotARuleId() {
        assertThat(Suppressions.lineIgnores("// logast:ignore LOG_LEVEL because LOG_FORMAT is wanted", "LOG_FORMAT")).isFalse();
        // a reason without a rule id is not a blanket ignore (as before)
        assertThat(Suppressions.lineIgnores("// logast:ignore -- not auditable", "AUDIT_MISSING")).isFalse();
    }

    @Test
    void sourceMarkersAndFileIgnoresAreNotLineIgnores() {
        assertThat(Suppressions.lineIgnores("x(); // logast: LOG_FORMAT FMT-0447e4", "LOG_FORMAT")).isFalse();
        assertThat(Suppressions.lineIgnores("// logast:ignore-file LOG_FORMAT", "LOG_FORMAT")).isFalse();
        assertThat(Suppressions.lineIgnores("// logast:ignored LOG_FORMAT", "LOG_FORMAT")).isFalse();
    }

    @Test
    void fileIgnoresNameTheirRules() {
        String text = "package a;\n// logast:ignore-file LOG_FORMAT, LOG_LEVEL -- generated\nclass A {}\n// logast:ignore-file\n";
        assertThat(Suppressions.fileIgnores(text)).containsExactly("LOG_FORMAT", "LOG_LEVEL");
        assertThat(Suppressions.fileIgnores("class A {} // logast:ignore LOG_FORMAT\n")).isEmpty();
    }
}
