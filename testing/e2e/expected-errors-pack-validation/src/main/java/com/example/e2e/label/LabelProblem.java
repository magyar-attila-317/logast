package com.example.e2e.label;

import org.springframework.http.ProblemDetail;

public class LabelProblem extends ProblemDetail {
    public LabelProblem(String detail) {
        setDetail(detail);
    }
}
