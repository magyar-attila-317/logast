package com.example.e2e.shelf;

import com.example.lib.shelf.ReadOnlyGet;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;

@TrayEndpoint
public class TrayController {
    @GetMapping({"/{trayId}", "/by-label/{label}"})
    public String tray(@PathVariable(required = false) String trayId, @PathVariable(required = false) String label) {
        return "tray";
    }

    @PutMapping(path = {"/{trayId}/seal", "/by-label/{label}/seal"})
    public String seal(@PathVariable(required = false) String trayId, @PathVariable(required = false) String label) {
        return "sealed";
    }

    @JsonGet("/count")
    public int count() {
        return 3;
    }

    @ReadOnlyGet("/latest")
    public String latest() {
        return "latest";
    }
}
