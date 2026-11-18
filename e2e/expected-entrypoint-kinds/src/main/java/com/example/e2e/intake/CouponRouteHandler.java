package com.example.e2e.intake;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

@Component
public class CouponRouteHandler {
    private final CouponFeed feed;

    CouponRouteHandler(CouponFeed feed) {
        this.feed = feed;
    }

    public ServerResponse show(ServerRequest request) {
        return ServerResponse.ok().body(request.pathVariable("couponId"));
    }

    public ServerResponse register(ServerRequest request) {
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
