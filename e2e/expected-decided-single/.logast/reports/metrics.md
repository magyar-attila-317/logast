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
    @EventListener SettlementJobExecutor.onReady(ApplicationReadyEvent)  src/main/java/com/example/e2e/job/SettlementJobExecutor.java:18
    @TransactionalEventListener OrderNotifications.onCommitted(OrderPlaced)  src/main/java/com/example/e2e/order/OrderNotifications.java:25
  jms (1)
    @JmsListener coupon-audits  src/main/java/com/example/e2e/messaging/CouponMessages.java:38
  kafka (1)
    @KafkaListener coupon-batches  src/main/java/com/example/e2e/messaging/CouponMessages.java:20
  rabbit (1)
    @RabbitListener coupon-returns  src/main/java/com/example/e2e/messaging/CouponMessages.java:29

Meters in code: 4   (logast explain metrics . --format table lists them)

Outcomes logged but not counted: 3  failed 2, archived 1
  pick the ones a dashboard or alert needs; snippets (tag or counter) in --format json and in the METRIC_OUTCOME_UNMEASURED findings
  src/main/java/com/example/e2e/edge/EdgeFlow.java:46  EdgeFlow.process
    INFO "Coupon archived"  outcome=archived  frequency low
    from scheduled @Scheduled EdgeJob.run
  src/main/java/com/example/e2e/edge/EdgeFlow.java:49  EdgeFlow.process
    ERROR "Coupon sweep failed"  outcome=failed  frequency low
    from scheduled @Scheduled EdgeJob.run
  src/main/java/com/example/e2e/messaging/ReactiveCouponLookup.java:17  ReactiveCouponLookup.find
    WARN "Coupon lookup failed"  outcome=failed  frequency unknown: no entrypoint is known to reach it
