package dev.logast.kb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** #205: a schema whose migrations were left out (they need what no repo makes) is said to be incomplete. */
class IncompleteSchemaTest {
    @TempDir
    Path tmp;

    private static final String ACCOUNTING = """
            {"header":{"schema":"kb-facts/0","extractor":"java+flyway","repo":"accounting"}}
            {"node":{"id":"table:public.ledger","kind":"table","attrs":{"columns":["id bigint"]},"evidence":{"file":"V1__ledger.sql"}}}
            {"edge":{"from":"service:accounting","kind":"reads","to":"table:public.items_view","attrs":{"via":"migration V2__shared.sql","leftOut":true,"madeElsewhere":true,"objectKind":"relation","asked":"items_view","searchSchema":"public","lost":["ledger_line"]},"provenance":"extracted","evidence":{"file":"V2__shared.sql"}}}
            {"edge":{"from":"service:accounting","kind":"reads","to":"table:catalog.items","attrs":{"via":"migration V3__fk.sql","standIn":true,"madeElsewhere":true,"objectKind":"relation","asked":"catalog.items","searchSchema":"public","columnsAssumed":["id bigint"]},"provenance":"extracted","evidence":{"file":"V3__fk.sql"}}}
            """;

    private KnowledgeBase kb() throws IOException {
        Files.writeString(tmp.resolve("accounting.jsonl"), ACCOUNTING);
        return KnowledgeBase.load(KnowledgeBase.factFiles(List.of(tmp)));
    }

    @Test
    void theSummaryDescribeAndTableAnswersSayTheSchemaIsIncomplete() throws IOException {
        KnowledgeBase k = kb();
        assertThat(k.summary()).contains("INCOMPLETE schemas").contains("service:accounting");
        assertThat(k.describe("service:accounting", 20))
                .contains("INCOMPLETE schema: migration V2__shared.sql was left out: it needs relation items_view, which no repo in the facts makes; missing with it: [ledger_line]")
                .contains("run against stand-ins): table:catalog.items");
        assertThat(k.tableAccess("ledger_line", true, 10)).contains("a migration that creates it was left out")
                .contains("service:accounting: migration V2__shared.sql was left out");
        assertThat(k.tableAccess("nothing_like_it", true, 10)).doesNotContain("left out");
    }
}
