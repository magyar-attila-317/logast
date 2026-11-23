package dev.logast.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #250: the MDC registry and aliases are built once per policy: rules ask for them per value they follow, and on a
 * service of a few thousand files rebuilding them each time (every key restyled) kept apply busy for half an hour.
 */
class PolicyDerivedTest {
    @Test
    void theMdcMapsAreBuiltOncePerPolicyAndReadOnly() {
        Policy policy = Policy.defaults();
        assertThat(policy.mdcRegistry()).isSameAs(policy.mdcRegistry());
        assertThat(policy.mdcAliases()).isSameAs(policy.mdcAliases());
        assertThatThrownBy(() -> policy.mdcAliases().put("orderId", "order.id")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(Policy.defaults().mdcRegistry()).as("another policy (even an equal one) has its own").isNotSameAs(policy.mdcRegistry());
    }
}
