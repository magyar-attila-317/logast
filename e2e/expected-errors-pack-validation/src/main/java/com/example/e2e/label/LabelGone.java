package com.example.e2e.label;

import org.springframework.http.ProblemDetail;

public class LabelGone extends ProblemDetail {
    public LabelGone() {
        super(410);
    }
}
