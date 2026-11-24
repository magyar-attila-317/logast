Export: none
WARNING:   No exporter on the classpath: meters are collected but never leave the application.
Identity
  spring.application.name: e2e-shop
  spring.application.version: ${APPLICATION_VERSION:unknown}
  meter tags: application, version

Measured without code: 16 entrypoints
  1 by Spring Kafka, e.g. spring.kafka.listener{…}  (listener OrderEvents.onOrder)
  12 by Spring MVC, e.g. http.server.requests{method="DELETE", uri="/pledges/{pledgeId}"}
  3 by Spring scheduling, e.g. tasks.scheduled.execution{code.namespace="com.example.e2e.edge.EdgeJob", code.function="run"}
  1 Spring Data repositories, e.g. spring.data.repository.invocations{repository="OrderRecordRepository", method=…}

Entrypoints no metric covers: 5
  event (2)
    @EventListener SettlementJobExecutor.onReady(ApplicationReadyEvent)  src/main/java/com/example/e2e/job/SettlementJobExecutor.java:17
    @TransactionalEventListener OrderNotifications.onCommitted(OrderPlaced)  src/main/java/com/example/e2e/order/OrderNotifications.java:22
  jms (1)
    @JmsListener pledge-audits  src/main/java/com/example/e2e/messaging/PledgeMessages.java:37
  kafka (1)
    @KafkaListener pledge-batches  src/main/java/com/example/e2e/messaging/PledgeMessages.java:19
  rabbit (1)
    @RabbitListener pledge-returns  src/main/java/com/example/e2e/messaging/PledgeMessages.java:28

Meters in code: 3   (logast explain metrics . --format table lists them)

Outcomes logged but not counted: 14  failed 2, sent 2, archived 1, audited 1, completed 1, parsed 1, placed 1, published 1, recorded 1, saved 1, shipped 1, stored 1
  pick the ones a dashboard or alert needs; snippets (tag or counter) in --format json and in the METRIC_OUTCOME_UNMEASURED findings
  src/main/java/com/example/e2e/edge/Audited.java:11  Audited.audit
    INFO "Pledge audited"  outcome=audited  frequency low
    from scheduled @Scheduled EdgeJob.run
  src/main/java/com/example/e2e/edge/EdgeFlow.java:45  EdgeFlow.process
    INFO "Pledge archived"  outcome=archived  frequency low
    from scheduled @Scheduled EdgeJob.run
  src/main/java/com/example/e2e/edge/EdgeFlow.java:48  EdgeFlow.process
    ERROR "Edge flow async work failed"  outcome=failed  frequency low
    from scheduled @Scheduled EdgeJob.run
  src/main/java/com/example/e2e/edge/EdgeFlow.java:51  EdgeFlow.process
    INFO "Pledge parsed"  outcome=parsed  frequency low
    from scheduled @Scheduled EdgeJob.run
  src/main/java/com/example/e2e/ledger/GatewayCalls.java:18  GatewayCalls.store
    INFO "Gateway reply stored"  outcome=stored  frequency unknown: no entrypoint is known to reach it
  src/main/java/com/example/e2e/messaging/ReactivePledgeLookup.java:17  ReactivePledgeLookup.find
    WARN "Pledge lookup failed"  outcome=failed  frequency unknown: no entrypoint is known to reach it
  src/main/java/com/example/e2e/order/OrderNotifications.java:16  OrderNotifications.onPlaced
    INFO "Order confirmation sent"  outcome=sent  frequency unknown: no rate for "POST /orders" in .logast/decisions/traffic.yaml
    from http POST /orders
  src/main/java/com/example/e2e/order/OrderNotifications.java:23  OrderNotifications.onCommitted
    INFO "Order audit recorded"  outcome=recorded  frequency unknown: no rate for "@TransactionalEventListener OrderNotifications.onCommitted(OrderPlaced)" in .logast/decisions/traffic.yaml
    from event @TransactionalEventListener OrderNotifications.onCommitted(OrderPlaced)
  src/main/java/com/example/e2e/order/OrderService.java:37  OrderService.place
    INFO "Order placed"  outcome=placed  frequency unknown: no rate for "POST /orders" in .logast/decisions/traffic.yaml
    from http POST /orders
  src/main/java/com/example/e2e/order/OrderService.java:48  OrderService.ship
    INFO "Orders shipped"  outcome=shipped  frequency unknown: no entrypoint is known to reach it
  src/main/java/com/example/e2e/pledge/PledgeRepository.java:33  PledgeRepository.countUse
    INFO "Pledge usage saved"  outcome=saved  frequency unknown: no rate for "POST /pledges/{pledgeId}/use" in .logast/decisions/traffic.yaml
    from http POST /pledges/{pledgeId}/use
  src/main/java/com/example/e2e/pledge/PledgeSender.java:14  PledgeSender.send
    INFO "Pledge sent"  outcome=sent  frequency unknown: no rate for "POST /pledges/allot" in .logast/decisions/traffic.yaml
    from http POST /pledges/allot; http POST /pledges/{pledgeId}/publish
  src/main/java/com/example/e2e/pledge/PledgeService.java:44  PledgeService.publish
    INFO "Pledge published"  outcome=published  frequency unknown: no rate for "POST /pledges/{pledgeId}/publish" in .logast/decisions/traffic.yaml
    from http POST /pledges/{pledgeId}/publish
  src/main/java/com/example/e2e/pledge/PledgeService.java:51  PledgeService.allot
    INFO "Pledge allotment completed"  outcome=completed  frequency unknown: no rate for "POST /pledges/allot" in .logast/decisions/traffic.yaml
    from http POST /pledges/allot
