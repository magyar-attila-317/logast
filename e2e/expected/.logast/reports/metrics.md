Export: none
WARNING:   No exporter on the classpath: meters are collected but never leave the application.
Identity
  spring.application.name: e2e-shop
  spring.application.version: ${APPLICATION_VERSION:unknown}
  meter tags: application, version

Measured without code: 16 entrypoints
  1 by Spring Kafka, e.g. spring.kafka.listener{…}  (listener OrderEvents.onOrder)
  12 by Spring MVC, e.g. http.server.requests{method="DELETE", uri="/coupons/{couponId}"}
  3 by Spring scheduling, e.g. tasks.scheduled.execution{code.namespace="com.example.e2e.coupon.CouponCleanupJob", code.function="cleanup"}
  1 Spring Data repositories, e.g. spring.data.repository.invocations{repository="OrderRecordRepository", method=…}

Entrypoints no metric covers: 5
  event (2)
    @EventListener SettlementJobExecutor.onReady(ApplicationReadyEvent)  src/main/java/com/example/e2e/job/SettlementJobExecutor.java:17
    @TransactionalEventListener OrderNotifications.onCommitted(OrderPlaced)  src/main/java/com/example/e2e/order/OrderNotifications.java:23
  jms (1)
    @JmsListener coupon-audits  src/main/java/com/example/e2e/messaging/CouponMessages.java:37
  kafka (1)
    @KafkaListener coupon-batches  src/main/java/com/example/e2e/messaging/CouponMessages.java:19
  rabbit (1)
    @RabbitListener coupon-returns  src/main/java/com/example/e2e/messaging/CouponMessages.java:28

Meters in code: 4   (logast explain metrics . --format table lists them)

Outcomes logged but not counted: 12  failed 2, sent 2, archived 1, audited 1, completed 1, parsed 1, placed 1, published 1, saved 1, shipped 1
  pick the ones a dashboard or alert needs; snippets (tag or counter) in --format json and in the METRIC_OUTCOME_UNMEASURED findings
  src/main/java/com/example/e2e/coupon/CouponRepository.java:33  CouponRepository.countUse
    INFO "Coupon usage saved"  outcome=saved  frequency unknown: no rate for "POST /coupons/{couponId}/use" in .logast/decisions/traffic.yaml
    from http POST /coupons/{couponId}/use
  src/main/java/com/example/e2e/coupon/CouponSender.java:14  CouponSender.send
    INFO "Coupon sent"  outcome=sent  frequency unknown: no rate for "POST /coupons/redeem" in .logast/decisions/traffic.yaml
    from http POST /coupons/redeem; http POST /coupons/{couponId}/publish
  src/main/java/com/example/e2e/coupon/CouponService.java:44  CouponService.publish
    INFO "Coupon published"  outcome=published  frequency unknown: no rate for "POST /coupons/{couponId}/publish" in .logast/decisions/traffic.yaml
    from http POST /coupons/{couponId}/publish
  src/main/java/com/example/e2e/coupon/CouponService.java:51  CouponService.redeem
    INFO "Coupon redemption completed"  outcome=completed  frequency unknown: no rate for "POST /coupons/redeem" in .logast/decisions/traffic.yaml
    from http POST /coupons/redeem
  src/main/java/com/example/e2e/edge/Audited.java:11  Audited.audit
    INFO "Coupon audited"  outcome=audited  frequency low
    from scheduled @Scheduled EdgeJob.run
  src/main/java/com/example/e2e/edge/EdgeFlow.java:45  EdgeFlow.process
    INFO "Coupon archived"  outcome=archived  frequency low
    from scheduled @Scheduled EdgeJob.run
  src/main/java/com/example/e2e/edge/EdgeFlow.java:48  EdgeFlow.process
    ERROR "Edge flow async work failed"  outcome=failed  frequency low
    from scheduled @Scheduled EdgeJob.run
  src/main/java/com/example/e2e/edge/EdgeFlow.java:51  EdgeFlow.process
    INFO "Coupon parsed"  outcome=parsed  frequency low
    from scheduled @Scheduled EdgeJob.run
  src/main/java/com/example/e2e/messaging/ReactiveCouponLookup.java:17  ReactiveCouponLookup.find
    WARN "Coupon lookup failed"  outcome=failed  frequency unknown: no entrypoint is known to reach it
  src/main/java/com/example/e2e/order/OrderNotifications.java:17  OrderNotifications.onPlaced
    INFO "Order confirmation sent"  outcome=sent  frequency unknown: no rate for "POST /orders" in .logast/decisions/traffic.yaml
    from http POST /orders
  src/main/java/com/example/e2e/order/OrderService.java:37  OrderService.place
    INFO "Order placed"  outcome=placed  frequency unknown: no rate for "POST /orders" in .logast/decisions/traffic.yaml
    from http POST /orders
  src/main/java/com/example/e2e/order/OrderService.java:48  OrderService.ship
    INFO "Orders shipped"  outcome=shipped  frequency unknown: no entrypoint is known to reach it
