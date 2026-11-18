package com.example.e2e.intake;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/** Functional endpoints: handler methods instead of @RequestMapping. */
@Configuration
public class PledgeRoutes {
    static final String BASE = "/pledge-routes";

    @Bean
    public RouterFunction<ServerResponse> pledgeRouter(PledgeRouteHandler handler) {
        return RouterFunctions.route()
                .path(BASE, builder -> builder
                        .GET("/{pledgeId}", handler::show)
                        .POST("", handler::register))
                .DELETE(BASE + "/{pledgeId}", request -> handler.remove(request))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> pledgeReplace(PledgeRouteHandler handler) {
        return RouterFunctions.route(RequestPredicates.PUT("/pledge-routes/{pledgeId}"), handler::replace);
    }
}
