package dev.logast;

import dev.logast.rules.MessageTemplate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageTemplateTest {
    @Test
    void wrappedAndNamedPlaceholdersLeaveNoDebris() {
        assertThat(MessageTemplate.stripPlaceholders("Invalid funder setup [pledgeId={}, sponsorshipId={}, funders=({})]"))
                .isEqualTo("Invalid funder setup");
        assertThat(MessageTemplate.stripPlaceholders("Invalid funder setup (pledgeId={}, sponsorshipId={}, funders={})"))
                .isEqualTo("Invalid funder setup");
        assertThat(MessageTemplate.stripPlaceholders("Invalid funder setup: pledgeId={}, sponsorshipId={}, funders=({})"))
                .isEqualTo("Invalid funder setup");
        assertThat(MessageTemplate.stripPlaceholders("Order {} shipped to '{}'")).isEqualTo("Order shipped to");
        assertThat(MessageTemplate.stripPlaceholders("Payment failed: {}")).isEqualTo("Payment failed");
        assertThat(MessageTemplate.stripPlaceholders("Pledge applied, pledgeId: {}")).isEqualTo("Pledge applied");
    }

    @Test
    void namesWrittenInFrontOfWrappedPlaceholders() {
        assertThat(MessageTemplate.placeholderNames("x [a={}, funders=({}), b: {}, pledgeId: '{}']"))
                .containsExactly("a", "funders", null, "pledgeId");
    }

    /** A house-format suffix written without the space after the comma is still house format (user report). */
    @org.junit.jupiter.api.Test
    void suffixWithoutSpaceAfterCommaIsCompliant() {
        org.assertj.core.api.Assertions.assertThat(dev.logast.rules.MessageTemplate.compliantKeys("Something happened [key1={},key2={}]"))
                .containsExactly("key1", "key2");
        org.assertj.core.api.Assertions.assertThat(dev.logast.rules.MessageTemplate.compliantKeys("Something happened [key1={} , key2={}]"))
                .containsExactly("key1", "key2");
        org.assertj.core.api.Assertions.assertThat(dev.logast.rules.MessageTemplate.compliantBase("Something happened [key1={},key2={}]"))
                .isEqualTo("Something happened");
    }
}
