package com.example.e2e.reactive;

import com.example.e2e.messaging.ReactivePledgeLookup;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@Component
public class ReactivePledgeHandler {
    private final ReactivePledgeLookup lookup;

    ReactivePledgeHandler(ReactivePledgeLookup lookup) {
        this.lookup = lookup;
    }

    public Mono<ServerResponse> show(ServerRequest request) {
        String pledgeId = request.pathVariable("pledgeId");
        return lookup.find(pledgeId).flatMap(found -> ServerResponse.ok().bodyValue(found));
    }

    public Mono<ServerResponse> register(ServerRequest request) {
        return request.bodyToMono(String.class).flatMap(body -> ServerResponse.accepted().build());
    }

    public Mono<ServerResponse> remove(ServerRequest request) {
        return lookup.find(request.pathVariable("pledgeId")).then(ServerResponse.noContent().build());
    }
}
