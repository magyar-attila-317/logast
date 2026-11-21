package dev.logast.kb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** #215: configuration values and constants match too, but after what they are about; alone, they are listed. */
class FindRawKindsTest {
    @TempDir
    Path tmp;

    @Test
    void rawFactsComeAfterWhatTheyAreAbout() throws IOException {
        Files.writeString(tmp.resolve("billing.jsonl"), """
                {"header":{"schema":"kb-facts/0","extractor":"java","repo":"billing"}}
                {"node":{"id":"job:billing:com.example.InvoiceJob","kind":"job","evidence":{"file":"InvoiceJob.java"}}}
                {"node":{"id":"property:billing:billing.invoice.cron","kind":"property","attrs":{"value":"0 0 1 * * ?"},"evidence":{"file":"application.yml"}}}
                {"node":{"id":"constant:billing:Keys.INVOICE","kind":"constant","attrs":{"value":"invoice"},"evidence":{"file":"Keys.java"}}}
                """);
        KnowledgeBase kb = KnowledgeBase.load(List.of(tmp));
        assertThat(kb.find("invoice", null, 10)).startsWith("1 nodes:\n- job:billing:com.example.InvoiceJob")
                .contains("(and 2 configuration/code values: 1 constant, 1 property; give kind to list them)")
                .doesNotContain("- property:");
        assertThat(kb.find("invoice", "property", 10)).contains("- property:billing:billing.invoice.cron");
        assertThat(kb.find("0 0 1", null, 10)).as("only raw facts match: listed").contains("- property:billing:billing.invoice.cron");
    }
}
