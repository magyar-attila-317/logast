package com.example.e2e.edge;

import com.example.e2e.stipend.PledgeDetails;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OriginCheck implements Check<PledgeDetails> {
    @Override
    public Optional<String> test(PledgeDetails pledge) {
        log.debug("Origin checked [pledgeId={}, origin={}]", pledge.pledgeId(), pledge.origin());
        return Optional.empty();
    }
}
