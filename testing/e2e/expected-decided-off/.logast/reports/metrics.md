Export: none
WARNING:   No exporter on the classpath: meters are collected but never leave the application.
Identity
  spring.application.name: e2e-shop
  spring.application.version: ${APPLICATION_VERSION:unknown}
  meter tags: application, version

Measured without code: 19 entrypoints
  3 by ObservedContext, e.g. settlement-job.execute{error=…}  (timer from the observation; also settlement-job.execute.active)
  1 by Spring Kafka, e.g. spring.kafka.listener{…}  (listener OrderEvents.onOrder)
  12 by Spring MVC, e.g. http.server.requests{method="DELETE", uri="/pledges/{pledgeId}"}
  3 by Spring scheduling, e.g. tasks.scheduled.execution{code.namespace="com.example.e2e.edge.EdgeJob", code.function="run"}
  1 Spring Data repositories, e.g. spring.data.repository.invocations{repository="OrderRecordRepository", method=…}

Entrypoints no metric covers: 2
  jms (1)
    @JmsListener pledge-audits  src/main/java/com/example/e2e/messaging/PledgeMessages.java:38
  rabbit (1)
    @RabbitListener pledge-returns  src/main/java/com/example/e2e/messaging/PledgeMessages.java:29

Meters in code: 4   (logast explain metrics . --format table lists them)

Outcomes logged but not counted: 3  failed 2, archived 1
  pick the ones a dashboard or alert needs; snippets (tag or counter) in --format json and in the METRIC_OUTCOME_UNMEASURED findings
  src/main/java/com/example/e2e/edge/EdgeFlow.java:46  EdgeFlow.process
    INFO "Pledge archived"  outcome=archived  frequency low
    from scheduled @Scheduled EdgeJob.run
  src/main/java/com/example/e2e/edge/EdgeFlow.java:49  EdgeFlow.process
    ERROR "Pledge sweep failed"  outcome=failed  frequency low
    from scheduled @Scheduled EdgeJob.run
  src/main/java/com/example/e2e/messaging/ReactivePledgeLookup.java:17  ReactivePledgeLookup.find
    WARN "Pledge lookup failed"  outcome=failed  frequency unknown: no entrypoint is known to reach it
