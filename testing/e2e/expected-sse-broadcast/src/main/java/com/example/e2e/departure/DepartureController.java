package com.example.e2e.departure;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class DepartureController {
    private final DepartureFeed feed;

    public DepartureController(DepartureFeed feed) {
        this.feed = feed;
    }

    @GetMapping("/departures/{subscriberId}/stream")
    public SseEmitter stream(@PathVariable String subscriberId) {
        return feed.subscribe(subscriberId);
    }

    @PostMapping("/departures")
    public void announce(@RequestBody String departure) {
        feed.publish(departure);
    }

    @PostMapping("/departures/delays")
    public void delay(@RequestBody String departure) {
        feed.delay(departure);
    }

    @PostMapping("/departures/cancellations")
    public void cancel(@RequestBody String departure) {
        feed.cancel(departure);
    }
}
