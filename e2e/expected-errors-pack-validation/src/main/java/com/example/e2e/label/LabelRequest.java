package com.example.e2e.label;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LabelRequest(@NotBlank String labelCode, @Size(max = 40) String text) {
}
