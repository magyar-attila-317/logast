package com.example.e2e.label;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
class LabelController {
    private final LabelStore store;

    LabelController(LabelStore store) {
        this.store = store;
    }

    @PostMapping("/labels")
    String create(@RequestBody LabelRequest request) {
        log.info("Label creation started"); // logast:wording
        return request.labelCode();
    }

    @PutMapping("/labels/{labelCode}")
    String update(@PathVariable String labelCode, @Valid @RequestBody LabelRequest request) {
        log.info("Label update started"); // logast:wording
        if (request.text() == null) {
            throw new LabelStale(labelCode);
        }
        return labelCode;
    }

    @PostMapping("/labels/notes")
    String note(@RequestBody String text) {
        log.info("Label notes started"); // logast:wording
        return text;
    }

    @GetMapping("/labels/{labelCode}")
    String find(@PathVariable String labelCode) {
        String label = store.find(labelCode);
        if (label == null) {
            throw new LabelMissing(labelCode);
        }
        if (label.isBlank()) {
            throw new LabelLocked(labelCode);
        }
        return label;
    }
}
