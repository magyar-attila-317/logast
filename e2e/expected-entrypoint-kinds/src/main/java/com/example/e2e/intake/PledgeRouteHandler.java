package com.example.e2e.intake;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

@Slf4j
@Component
public class PledgeRouteHandler {
    private final PledgeFeed feed;

    PledgeRouteHandler(PledgeFeed feed) {
        this.feed = feed;
    }

    public ServerResponse show(ServerRequest request) {
        return ServerResponse.ok().body(request.pathVariable("pledgeId"));
    }

    public ServerResponse register(ServerRequest request) {
        log.info("Pledge route creation started"); // logast:wording
        return ServerResponse.accepted().build();
    }

    public ServerResponse remove(ServerRequest request) {
        feed.load();
        return ServerResponse.noContent().build();
    }

    public ServerResponse replace(ServerRequest request) {
        feed.load();
        return ServerResponse.noContent().build();
    }
}
