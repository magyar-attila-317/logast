package com.example.shop.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/** No cache library on the classpath: Spring Boot's ConcurrentHashMap caches never evict (#90). */
@Configuration
@EnableCaching
public class CatalogCacheConfig { // expect: CACHE_UNBOUNDED
}
