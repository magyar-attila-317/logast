package dev.logast.rules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PluralTest {
    @Test
    void englishPlurals() {
        assertThat(CoverageRules.plural("menu category")).isEqualTo("menu categories");
        assertThat(CoverageRules.plural("kds ticket")).isEqualTo("kds tickets");
        assertThat(CoverageRules.plural("tax box")).isEqualTo("tax boxes");
        assertThat(CoverageRules.plural("payment day")).isEqualTo("payment days");
        assertThat(CoverageRules.plural("items")).isEqualTo("items");
    }
}
