package com.example.e2e.web;

import com.example.e2e.logging.MdcScope;
import com.example.e2e.pledge.PledgeId;
import com.example.e2e.pledge.PledgeLookup;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PledgeDetailsController {
    private final PledgeLookup lookup;

    public PledgeDetailsController(PledgeLookup lookup) {
        this.lookup = lookup;
    }

    @GetMapping("/pledges/{pledgeId}/details")
    public String details(@PathVariable String pledgeId) {
        try (var mdc = MdcScope.of("pledge.id", pledgeId)) {
            return lookup.find(pledgeId);
        }
    }

    @PostMapping("/pledges/{pledgeId}/hold")
    public void hold(@PathVariable PledgeId pledgeId) {
        lookup.find(pledgeId.value());
    }
}
