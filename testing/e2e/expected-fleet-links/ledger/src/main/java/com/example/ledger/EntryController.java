package com.example.ledger;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class EntryController {
    @GetMapping("/entries/{entryId}")
    String entry(@PathVariable long entryId) {
        return "entry";
    }
}
