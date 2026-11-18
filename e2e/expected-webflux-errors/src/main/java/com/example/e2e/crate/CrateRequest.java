package com.example.e2e.crate;

import jakarta.validation.constraints.NotBlank;

public record CrateRequest(@NotBlank String label) {
}
