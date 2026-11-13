package dev.logast.support;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AfterCommitTest {
    /** A transaction manager with real synchronization and no resource behind it. */
    static final class NoResourceTxManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object tx, TransactionDefinition def) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }

    private final List<String> lines = new ArrayList<>();
    private final TransactionTemplate tx = new TransactionTemplate(new NoResourceTxManager());

    @Test
    void writesOnCommitWithTheMdcOfTheCall() {
        tx.executeWithoutResult(s -> {
            try (var mdc = MdcScope.of("order.id", "o-1")) {
                AfterCommit.log(() -> lines.add("updated " + MDC.get("order.id")));
            }
            assertThat(lines).isEmpty();                       // not before the commit
        });
        assertThat(lines).containsExactly("updated o-1");
        assertThat(MDC.get("order.id")).isNull();
    }

    @Test
    void dropsTheLineOnRollback() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            AfterCommit.log(() -> lines.add("updated"));
            throw new IllegalStateException("boom");
        })).hasMessage("boom");
        assertThat(lines).isEmpty();
    }

    @Test
    void writesAtOnceWithoutATransaction() {
        AfterCommit.log(() -> lines.add("updated"));
        assertThat(lines).containsExactly("updated");
    }
}
