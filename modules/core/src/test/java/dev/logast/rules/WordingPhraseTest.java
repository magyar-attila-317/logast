package dev.logast.rules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** #307: acronyms (with digits too) keep their capitals in generated subjects. */
class WordingPhraseTest {
    @Test
    void acronymsStayUpperCase() {
        assertThat(Wording.phrase("NTAKReporting")).isEqualTo("NTAK reporting");
        assertThat(Wording.phrase("E2EDataSeeder")).isEqualTo("E2E data seeder");
        assertThat(Wording.phrase("pledgeMessages")).isEqualTo("pledge messages");
    }
}
