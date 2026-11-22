package com.example.e2e.label;

import org.orchardlib.errors.NotFound;

public class LabelMissing extends NotFound {
    public LabelMissing(String labelCode) {
        super("no label " + labelCode);
    }
}
