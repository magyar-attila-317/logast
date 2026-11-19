package dev.logast.fleet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.logast.CentralVocabulary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** #129: company.yaml's commented settings and the suggestions the members' snapshots make. */
class CompanyPolicyTest {
    private static final List<String> ALL = List.of("logging", "tracing", "metrics", "resilience", "errors", "audit");

    private static NamedFleet.Snapshot snap(String service, String format, String keyStyle) {
        return new NamedFleet.Snapshot(service, "/" + service, "2026-11-19T10:00:00Z", null, null, null, null,
                new NamedFleet.Settings(ALL, format, keyStyle, "vendor"));
    }

    @Test
    void theInitialFileSetsNothingAndKeepsTheVocabularyPart() throws Exception {
        String yaml = CompanyPolicy.initial("shop");
        assertThat(CompanyPolicy.active(yaml)).as("every setting commented out").isEmpty();
        assertThat(yaml).contains("# packs: [logging, tracing, metrics, resilience, errors, audit]   # the rule packs",
                        "#   keyStyle: camel   # how MDC keys", "#   mode: vendor", "#   repository: ",
                        CompanyPolicy.BEGIN, CompanyPolicy.END, "# >>> logast: generated", "vocabulary: {}");
        assertThat(new ObjectMapper(new YAMLFactory()).readTree(yaml).fieldNames()).toIterable().containsExactly("vocabulary");
        assertThat(CompanyPolicy.refresh(yaml, List.of()).text()).as("nothing to suggest: unchanged").isEqualTo(yaml);
    }

    @Test
    void suggestionsCountTheMembersAndNameTheOutliers() {
        List<CompanyPolicy.Suggestion> s = CompanyPolicy.suggestions(List.of(snap("checkout", "logstash", "camel"),
                snap("payments", "logstash", "snake"), snap("orders", "logstash", "camel"),
                new NamedFleet.Snapshot("old", "/old", "t", null, null, null, null, null)), Set.of());
        assertThat(s).extracting(CompanyPolicy.Suggestion::path)
                .containsExactly("packs", "logging.structuredFormat", "logging.keyStyle", "support.mode");
        CompanyPolicy.Suggestion format = s.get(1);
        assertThat(format.agree()).as("a snapshot without settings does not count").isEqualTo(3);
        assertThat(format.of()).isEqualTo(3);
        assertThat(format.comment()).isEqualTo("3 of 3 members use it: uncomment to make it the company's");
        CompanyPolicy.Suggestion keys = s.get(2);
        assertThat(keys.value()).isEqualTo("camel");
        assertThat(keys.agree()).isEqualTo(2);
        assertThat(keys.outliers()).isEqualTo(Map.of("payments", "snake"));
        assertThat(keys.comment()).isEqualTo("2 of 3 (payments: snake): decide, then uncomment");
        assertThat(CompanyPolicy.overviewLine(s, java.nio.file.Path.of("/f/company.yaml")))
                .isEqualTo("3 settings all members agree on aren't company policy yet → /f/company.yaml");
    }

    @Test
    void aRefreshKeepsHandWrittenSettingsAndSuggestsNothingForThem() {
        String yaml = CompanyPolicy.initial("shop").replace("# logging:\n#   structuredFormat", "logging:\n#   structuredFormat")
                .replace("#   keyStyle: camel   # how", "  keyStyle: snake   # how");
        List<NamedFleet.Snapshot> snaps = List.of(snap("checkout", "logstash", "camel"), snap("payments", "logstash", "camel"));
        String once = CompanyPolicy.refresh(yaml, snaps).text();
        assertThat(once).as("the hand line stays").contains("logging:\n#   structuredFormat: logstash   # the JSON",
                        "\n  keyStyle: snake   # how")
                .as("suggestions for the rest").contains("\n#   structuredFormat: logstash   # 2 of 2 members use it: uncomment")
                .as("not for the setting the company decided").doesNotContain("keyStyle: camel   # 2 of 2")
                .as("its logging: is set already: no second one to uncomment")
                .contains("# logging:   # set above already: put the line you want under that logging: instead");
        assertThat(CompanyPolicy.refresh(once, snaps).text()).as("a second refresh changes nothing").isEqualTo(once);

        // a suggestion uncommented in place: kept, moved above the part, no suggestion for it any more
        String inPlace = once.replace("# packs: [logging, tracing, metrics, resilience, errors, audit]   # 2 of 2",
                "packs: [logging, tracing, metrics, resilience, errors, audit]   # 2 of 2");
        String after = CompanyPolicy.refresh(inPlace, snaps).text();
        assertThat(after).containsOnlyOnce("\npacks: [logging, tracing").doesNotContain("# packs: [logging, tracing, metrics, resilience, errors, audit]   # 2 of 2");
        assertThat(after.indexOf("\npacks: [")).as("outside the generated part").isLessThan(after.indexOf(CompanyPolicy.BEGIN));
        assertThat(CompanyPolicy.active(after)).contains("packs", "logging.keyStyle");
    }

    @Test
    void theVocabularyPartAndTheSuggestionsAreRewrittenSeparately() throws Exception {
        List<NamedFleet.Snapshot> snaps = List.of(snap("checkout", "logstash", "camel"));
        String yaml = CompanyPolicy.refresh(CompanyPolicy.initial("shop"), snaps).text();
        String vocab = CentralVocabulary.mergeCompany(yaml, "vocabulary:\n  payment:\n    mdc:\n      key: paymentId\n");
        assertThat(vocab).as("the suggestions stay").contains("#   structuredFormat: logstash   # 1 of 1 member uses it")
                .contains("key: paymentId").doesNotContain("vocabulary: {}");
        assertThat(CompanyPolicy.refresh(vocab, snaps).text()).as("the vocabulary stays").isEqualTo(vocab);
    }

    @Test
    void aFileWithoutTheSuggestionsPartGetsItBeforeTheVocabulary() {
        String old = "logging:\n  keyStyle: camel\n" + CentralVocabulary.mergeCompany(null, "vocabulary: {}\n");
        String now = CompanyPolicy.refresh(old, List.of(snap("checkout", "ecs", "camel"))).text();
        assertThat(now).startsWith("logging:\n  keyStyle: camel\n\n" + CompanyPolicy.BEGIN)
                .contains("#   structuredFormat: ecs   # 1 of 1", "\n\n# >>> logast: generated")
                .doesNotContain("keyStyle: camel   #");
        assertThat(CompanyPolicy.refresh("logging: [unclosed\n", List.of()).text()).as("a broken file is left alone").isNull();
    }
}
