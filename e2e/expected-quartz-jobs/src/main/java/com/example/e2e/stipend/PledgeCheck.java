package com.example.e2e.stipend;

import java.util.Optional;

/** One check of a matched pledge; implementations are nested classes of PledgeChecks. */
public interface PledgeCheck {
    Optional<String> validate(PledgeDetails pledge, KioskTotals kioskData);
}
