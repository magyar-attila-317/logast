package com.example.e2e.edge;

import com.example.e2e.stipend.PledgeCheck;
import com.example.e2e.stipend.PledgeDetails;
import com.example.e2e.stipend.KioskTotals;
import com.example.e2e.logging.MdcScope;
import com.example.e2e.logging.Outcomes;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Holds the row (and its pledgeId) for the whole flow; every call below is one edge case. */
@Slf4j
@Service
public class EdgeFlow implements Audited {
    public void process(KioskTotals kioskData) {
        try (var mdc = MdcScope.of("pledgeId", kioskData.pledgeId())) {
            log.info("Edge processing started");
            PledgeRules.check(kioskData.pledgeId());                                   // @UtilityClass, methods not declared static
            List.of(kioskData).forEach(this::publishOne);                              // method reference
            for (Exclusion exclusion : Exclusion.values()) {                           // enum constants with bodies
                exclusion.test(kioskData);
            }
            PledgeDetails pledge = new PledgeDetails(kioskData.pledgeId(), null, null, null);
            try (var mdc2 = MdcScope.of("sponsorshipId", pledge.sponsorshipId())) {
                PledgeCheck inline = (c, s) -> {                                           // lambda implementing an interface
                    log.debug("Inline check");
                    return Optional.empty();
                };
                inline.validate(pledge, kioskData);
                new PledgeCheck() {                                                        // anonymous implementation
                    @Override
                    public Optional<String> validate(PledgeDetails c, KioskTotals s) {
                        log.debug("Anonymous check");
                        return Optional.empty();
                    }
                }.validate(pledge, kioskData);
                Check<PledgeDetails> origin = new OriginCheck();                           // generic interface
                origin.test(pledge);
                audit(kioskData.pledgeId());                                               // default method of an interface
                EdgeLogHelper.info("Pledge processed [pledgeId={}]", kioskData.pledgeId()); // a home-made log helper
                CompletableFuture.runAsync(() ->                                           // another thread: MDC does not follow
                        log.info("Pledge archived [pledgeId={}]", kioskData.pledgeId()));
                Outcomes.count("pledge", "archived");
                log.info("Pledge parsed"); // a transformed value
                Outcomes.count("pledge", "parsed");
                new PledgeTask(kioskData.pledgeId()).run();                                // an id kept in an instance field
                log.info("Edge processing completed");
                Outcomes.count("edge.processing", "completed");
            }
        }
    }

    private void publishOne(KioskTotals row) {
        log.debug("Publishing pledge");
    }
}
