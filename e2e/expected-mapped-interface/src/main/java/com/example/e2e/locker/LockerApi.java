package com.example.e2e.locker;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping(LockerPaths.BASE)
public interface LockerApi {
    @GetMapping(LockerPaths.LOCKER)
    String status(@PathVariable("lockerId") String lockerId);

    @PostMapping(path = LockerPaths.OPEN)
    String open(@PathVariable("lockerId") String lockerId);
}
