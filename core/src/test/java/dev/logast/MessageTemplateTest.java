package dev.logast;

import dev.logast.rules.MessageTemplate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageTemplateTest {
    @Test
    void wrappedAndNamedPlaceholdersLeaveNoDebris() {
        assertThat(MessageTemplate.stripPlaceholders("Invalid funder setup [couponId={}, agreementId={}, funders=({})]"))
                .isEqualTo("Invalid funder setup");
        assertThat(MessageTemplate.stripPlaceholders("Invalid funder setup (couponId={}, agreementId={}, funders={})"))
                .isEqualTo("Invalid funder setup");
        assertThat(MessageTemplate.stripPlaceholders("Invalid funder setup: couponId={}, agreementId={}, funders=({})"))
                .isEqualTo("Invalid funder setup");
        assertThat(MessageTemplate.stripPlaceholders("Order {} shipped to '{}'")).isEqualTo("Order shipped to");
        assertThat(MessageTemplate.stripPlaceholders("Payment failed: {}")).isEqualTo("Payment failed");
        assertThat(MessageTemplate.stripPlaceholders("Coupon applied, couponId: {}")).isEqualTo("Coupon applied");
    }

    @Test
    void namesWrittenInFrontOfWrappedPlaceholders() {
        assertThat(MessageTemplate.placeholderNames("x [a={}, funders=({}), b: {}, couponId: '{}']"))
                .containsExactly("a", "funders", null, "couponId");
    }
}
