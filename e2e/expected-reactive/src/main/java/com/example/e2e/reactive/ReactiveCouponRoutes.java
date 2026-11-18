package com.example.e2e.reactive;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/** WebFlux functional endpoints. */
@Configuration
public class ReactiveCouponRoutes {
    @Bean
    public RouterFunction<ServerResponse> reactiveCouponRouter(ReactiveCouponHandler handler) {
        return RouterFunctions.route()
                .path("/reactive-routes", builder -> builder
                        .GET("/{couponId}", handler::show)
                        .POST("", handler::register))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> reactiveCouponRemoval(ReactiveCouponHandler handler) {
        return RouterFunctions.route(RequestPredicates.DELETE("/reactive-routes/{couponId}"), handler::remove);
    }
}
