package com.example.e2e.intake;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/** Functional endpoints: handler methods instead of @RequestMapping. */
@Configuration
public class CouponRoutes {
    static final String BASE = "/coupon-routes";

    @Bean
    public RouterFunction<ServerResponse> couponRouter(CouponRouteHandler handler) {
        return RouterFunctions.route()
                .path(BASE, builder -> builder
                        .GET("/{couponId}", handler::show)
                        .POST("", handler::register))
                .DELETE(BASE + "/{couponId}", request -> handler.remove(request))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> couponReplace(CouponRouteHandler handler) {
        return RouterFunctions.route(RequestPredicates.PUT("/coupon-routes/{couponId}"), handler::replace);
    }
}
