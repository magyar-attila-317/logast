package dev.logast.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** #205: a member whose migrations need another member's table is exported again once that table is known (or changes). */
class KbExportNeedsTest {
    @TempDir
    Path tmp;

    @Test
    void aMemberIsExportedAgainWhenWhatItNeedsResolvesDifferently() throws IOException {
        Path kb = tmp.resolve("kb");
        Path accounting = Files.createDirectories(kb.resolve("accounting"));
        Files.writeString(accounting.resolve("facts.jsonl"), """
                {"header":{"schema":"kb-facts/0","extractor":"java+flyway","repo":"accounting"}}
                {"edge":{"from":"service:accounting","kind":"reads","to":"table:catalog.items","attrs":{"via":"migration V2__shared.sql","leftOut":true,"madeElsewhere":true,"objectKind":"relation","asked":"catalog.items","searchSchema":"public","lost":["ledger_line"]},"provenance":"extracted","evidence":{"file":"V2__shared.sql"}}}
                {"edge":{"from":"service:accounting","kind":"reads","to":"table:public.orders_seq","attrs":{"via":"migration V4__seq.sql","standIn":true,"madeElsewhere":true,"objectKind":"sequence","asked":"orders_seq","searchSchema":"public"},"provenance":"extracted","evidence":{"file":"V4__seq.sql"}}}
                """);
        List<KbExport.Need> needs = KbExport.needs(accounting);
        assertThat(needs).extracting(KbExport.Need::asked).containsExactly("catalog.items");   // a sequence needs no columns
        KbExport.stamp(accounting, "abc", "opts", KbExport.resolved(needs, KbExport.tablesMade(kb)));
        assertThat(KbExport.needsChanged(accounting, KbExport.tablesMade(kb))).isFalse();

        Path catalog = Files.createDirectories(kb.resolve("catalog"));   // another member makes it now
        Files.writeString(catalog.resolve("facts.jsonl"), """
                {"header":{"schema":"kb-facts/0","extractor":"java+flyway","repo":"catalog"}}
                {"node":{"id":"table:catalog.items","kind":"table","attrs":{"columns":["id bigint","name text"]},"evidence":{"file":"V1__items.sql"}}}
                """);
        Map<String, List<String>> tables = KbExport.tablesMade(kb);
        assertThat(tables).containsEntry("catalog.items", List.of("id bigint", "name text"));
        assertThat(KbExport.needsChanged(accounting, tables)).isTrue();

        KbExport.stamp(accounting, "abc", "opts", KbExport.resolved(needs, tables));   // exported against it
        assertThat(KbExport.needsChanged(accounting, tables)).isFalse();
        Files.writeString(catalog.resolve("facts.jsonl"), Files.readString(catalog.resolve("facts.jsonl")).replace("\"name text\"", "\"title text\""));
        assertThat(KbExport.needsChanged(accounting, KbExport.tablesMade(kb))).isTrue();   // its columns changed
    }
}
