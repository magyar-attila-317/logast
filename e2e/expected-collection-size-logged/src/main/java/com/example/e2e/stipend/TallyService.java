package com.example.e2e.stipend;

import com.example.e2e.logging.MdcScope;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** processRow holds the kiosk row for the whole flow: the highest stable point for its pledgeId. */
@Service
public class TallyService {
    private static final Logger log = LoggerFactory.getLogger(TallyService.class);

    public void processAll(List<KioskTotals> rows, List<PledgeDetails> allPledges) {
        for (KioskTotals row : rows) {
            processRow(row, allPledges);
        }
    }

    private StipendOutcome processRow(KioskTotals kioskData, List<PledgeDetails> allPledgeDetails) {
        try (var mdc = MdcScope.of("pledgeId", kioskData.pledgeId())) {
            log.trace("Processing pledge");
            List<PledgeDetails> matchingPledges = filterMatchingPledges(allPledgeDetails, kioskData.pledgeId());
            return StipendCalculator.calculate(kioskData, matchingPledges);
        }
    }

    private List<PledgeDetails> filterMatchingPledges(List<PledgeDetails> allPledges, String pledgeId) {
        return allPledges.stream().filter(pledge -> pledge.pledgeId().equals(pledgeId)).toList();
    }
}
