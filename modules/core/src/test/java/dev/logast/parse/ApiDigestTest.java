package dev.logast.parse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What other files (and tests) see of a class: body edits keep it, signatures and instance fields change it. */
class ApiDigestTest {
    private static final String RECORDER = """
            package a;

            @lombok.RequiredArgsConstructor
            public class Recorder {
                private final Ledger ledger;

                public void record(String id) {
                    System.out.println(id);
                }
            }
            """;

    @Test
    void bodyEditsKeepItSignaturesAndFieldsChangeIt() {
        String digest = ApiDigest.of(RECORDER);
        assertThat(ApiDigest.of(RECORDER.replace("System.out.println(id);", "log.info(\"Recorded [id={}]\", id);")))
                .as("a log line").isEqualTo(digest);
        assertThat(ApiDigest.of(RECORDER.replace("    private final Ledger ledger;\n",
                "    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Recorder.class);\n    private final Ledger ledger;\n")))
                .as("a logger").isEqualTo(digest);
        assertThat(ApiDigest.of(RECORDER.replace("    }\n}", "    }\n\n    private void audit() {\n    }\n}")))
                .as("a private helper").isEqualTo(digest);
        assertThat(ApiDigest.of(RECORDER.replace("public void record(String id)", "public void record(String id, long cents)")))
                .as("a parameter").isNotEqualTo(digest);
        assertThat(ApiDigest.of(RECORDER.replace("    private final Ledger ledger;\n", "    private final Ledger ledger;\n    private final Clock clock;\n")))
                .as("a final field: Lombok's constructor takes it").isNotEqualTo(digest);
    }
}
