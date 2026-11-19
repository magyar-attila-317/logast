# Queries for e2e-shop

Written by every `logast scan` and `logast apply` from the code as it stands (`logast explain queries` prints it). Time ranges and thresholds are starting points.

## Prometheus (PromQL)

Every meter carries `application="e2e-shop"`. Percentiles need `management.metrics.distribution.percentiles-histogram.<meter>: true`; without it there is no `_bucket` series.

### HTTP endpoints (`http.server.requests`)

Calls per second:

```promql
sum by (method, uri) (rate(http_server_requests_seconds_count{application="e2e-shop"}[5m]))
```

Share of failed calls (0 to 1):

```promql
sum by (method, uri) (rate(http_server_requests_seconds_count{application="e2e-shop", outcome="SERVER_ERROR"}[5m]))
  / sum by (method, uri) (rate(http_server_requests_seconds_count{application="e2e-shop"}[5m]))
```

Average duration (seconds):

```promql
sum by (method, uri) (rate(http_server_requests_seconds_sum{application="e2e-shop"}[5m]))
  / sum by (method, uri) (rate(http_server_requests_seconds_count{application="e2e-shop"}[5m]))
```

p95 duration (seconds; needs the percentiles histogram):

```promql
histogram_quantile(0.95, sum by (le, method, uri) (rate(http_server_requests_seconds_bucket{application="e2e-shop"}[5m])))
```

For one of them, add its labels to the selector:

| entrypoint | labels |
|---|---|
| DELETE /pledges/{pledgeId} | `method="DELETE", uri="/pledges/{pledgeId}"` |
| GET /audit/settings | `method="GET", uri="/audit/settings"` |
| GET /pledges | `method="GET", uri="/pledges"` |
| GET /pledges/{pledgeId}/details | `method="GET", uri="/pledges/{pledgeId}/details"` |
| GET /quotes/{productId} | `method="GET", uri="/quotes/{productId}"` |
| GET /quotes/{productId}/refresh | `method="GET", uri="/quotes/{productId}/refresh"` |
| POST /admin/pledges/{pledgeId}/revoke | `method="POST", uri="/admin/pledges/{pledgeId}/revoke"` |
| POST /pledges/allot | `method="POST", uri="/pledges/allot"` |
| POST /pledges/{pledgeId}/hold | `method="POST", uri="/pledges/{pledgeId}/hold"` |
| POST /pledges/{pledgeId}/publish | `method="POST", uri="/pledges/{pledgeId}/publish"` |
| POST /pledges/{pledgeId}/use | `method="POST", uri="/pledges/{pledgeId}/use"` |
| POST /orders | `method="POST", uri="/orders"` |

### Scheduled jobs (`tasks.scheduled.execution`)

Calls per second:

```promql
sum by (code_namespace, code_function) (rate(tasks_scheduled_execution_seconds_count{application="e2e-shop"}[5m]))
```

Share of failed calls (0 to 1):

```promql
sum by (code_namespace, code_function) (rate(tasks_scheduled_execution_seconds_count{application="e2e-shop", outcome="ERROR"}[5m]))
  / sum by (code_namespace, code_function) (rate(tasks_scheduled_execution_seconds_count{application="e2e-shop"}[5m]))
```

Average duration (seconds):

```promql
sum by (code_namespace, code_function) (rate(tasks_scheduled_execution_seconds_sum{application="e2e-shop"}[5m]))
  / sum by (code_namespace, code_function) (rate(tasks_scheduled_execution_seconds_count{application="e2e-shop"}[5m]))
```

p95 duration (seconds; needs the percentiles histogram):

```promql
histogram_quantile(0.95, sum by (le, code_namespace, code_function) (rate(tasks_scheduled_execution_seconds_bucket{application="e2e-shop"}[5m])))
```

For one of them, add its labels to the selector:

| entrypoint | labels |
|---|---|
| @Scheduled PledgeCleanupJob.cleanup | `code_namespace="com.example.e2e.pledge.PledgeCleanupJob", code_function="cleanup"` |
| @Scheduled PledgeReloadJob.run | `code_namespace="com.example.e2e.pledge.PledgeReloadJob", code_function="run"` |
| @Scheduled EdgeJob.run | `code_namespace="com.example.e2e.edge.EdgeJob", code_function="run"` |

### Kafka listeners (`spring.kafka.listener`)

Calls per second:

```promql
sum by (spring_kafka_listener_id) (rate(spring_kafka_listener_seconds_count{application="e2e-shop"}[5m]))
```

Share of failed calls (0 to 1):

```promql
sum by (spring_kafka_listener_id) (rate(spring_kafka_listener_seconds_count{application="e2e-shop", error!="none"}[5m]))
  / sum by (spring_kafka_listener_id) (rate(spring_kafka_listener_seconds_count{application="e2e-shop"}[5m]))
```

Average duration (seconds):

```promql
sum by (spring_kafka_listener_id) (rate(spring_kafka_listener_seconds_sum{application="e2e-shop"}[5m]))
  / sum by (spring_kafka_listener_id) (rate(spring_kafka_listener_seconds_count{application="e2e-shop"}[5m]))
```

p95 duration (seconds; needs the percentiles histogram):

```promql
histogram_quantile(0.95, sum by (le, spring_kafka_listener_id) (rate(spring_kafka_listener_seconds_bucket{application="e2e-shop"}[5m])))
```

### Observed flows and timers

`error` is the exception's class name, `none` when the call succeeded.

#### pledge-messages.on-batch (kafka @KafkaListener pledge-batches)

Calls per second:

```promql
sum(rate(pledge_messages_on_batch_seconds_count{application="e2e-shop"}[5m]))
```

Failures per second, by exception:

```promql
sum by (error) (rate(pledge_messages_on_batch_seconds_count{application="e2e-shop", error!="none"}[5m]))
```

Average duration (seconds):

```promql
sum(rate(pledge_messages_on_batch_seconds_sum{application="e2e-shop"}[5m])) / sum(rate(pledge_messages_on_batch_seconds_count{application="e2e-shop"}[5m]))
```

Slowest call in the last minutes (seconds):

```promql
max(pledge_messages_on_batch_seconds_max{application="e2e-shop"})
```

#### order.notifications (event @TransactionalEventListener OrderNotifications.onCommitted(OrderPlaced))

Calls per second:

```promql
sum(rate(order_notifications_seconds_count{application="e2e-shop"}[5m]))
```

Failures per second, by exception:

```promql
sum by (error) (rate(order_notifications_seconds_count{application="e2e-shop", error!="none"}[5m]))
```

Average duration (seconds):

```promql
sum(rate(order_notifications_seconds_sum{application="e2e-shop"}[5m])) / sum(rate(order_notifications_seconds_count{application="e2e-shop"}[5m]))
```

Slowest call in the last minutes (seconds):

```promql
max(order_notifications_seconds_max{application="e2e-shop"})
```

#### publishing

Calls per second:

```promql
sum(rate(publishing_seconds_count{application="e2e-shop"}[5m]))
```

Failures per second, by exception:

```promql
sum by (error) (rate(publishing_seconds_count{application="e2e-shop", error!="none"}[5m]))
```

Average duration (seconds):

```promql
sum(rate(publishing_seconds_sum{application="e2e-shop"}[5m])) / sum(rate(publishing_seconds_count{application="e2e-shop"}[5m]))
```

Slowest call in the last minutes (seconds):

```promql
max(publishing_seconds_max{application="e2e-shop"})
```

#### settlement-job-executor.on-ready (event @EventListener SettlementJobExecutor.onReady(ApplicationReadyEvent))

Calls per second:

```promql
sum(rate(settlement_job_executor_on_ready_seconds_count{application="e2e-shop"}[5m]))
```

Failures per second, by exception:

```promql
sum by (error) (rate(settlement_job_executor_on_ready_seconds_count{application="e2e-shop", error!="none"}[5m]))
```

Average duration (seconds):

```promql
sum(rate(settlement_job_executor_on_ready_seconds_sum{application="e2e-shop"}[5m])) / sum(rate(settlement_job_executor_on_ready_seconds_count{application="e2e-shop"}[5m]))
```

Slowest call in the last minutes (seconds):

```promql
max(settlement_job_executor_on_ready_seconds_max{application="e2e-shop"})
```

### Business outcomes

**tally** (outcome: completed), per hour  
`src/main/java/com/example/e2e/job/TallyJob.java:18`

```promql
sum by (outcome) (increase(tally_total{application="e2e-shop"}[1h]))
```

**pledge** (outcome: audited, parsed, published, sent), per hour  
`src/main/java/com/example/e2e/pledge/PledgeSender.java:16`

```promql
sum by (outcome) (increase(pledge_total{application="e2e-shop"}[1h]))
```

**pledge.messages** (outcome: completed), per hour  
`src/main/java/com/example/e2e/messaging/PledgeMessages.java:33`

```promql
sum by (outcome) (increase(pledge_messages_total{application="e2e-shop"}[1h]))
```

**pledge.allotment** (outcome: completed), per hour  
`src/main/java/com/example/e2e/pledge/PledgeService.java:54`

```promql
sum by (outcome) (increase(pledge_allotment_total{application="e2e-shop"}[1h]))
```

**pledge.resend** (outcome: failed), per hour  
`src/main/java/com/example/e2e/pledge/PledgeSender.java:27`

```promql
sum by (outcome) (increase(pledge_resend_total{application="e2e-shop"}[1h]))
```

**pledge.usage** (outcome: saved), per hour  
`src/main/java/com/example/e2e/pledge/PledgeRepository.java:35`

```promql
sum by (outcome) (increase(pledge_usage_total{application="e2e-shop"}[1h]))
```

**pledge.usage.uses** (outcome: saved), per hour  
`src/main/java/com/example/e2e/pledge/PledgeRepository.java:35`

```promql
sum by (outcome) (increase(pledge_usage_uses_total{application="e2e-shop"}[1h]))
```

**pledges** (outcome: updated), per hour  
`src/main/java/com/example/e2e/pledge/PledgeRepository.java:29`

```promql
sum by (outcome) (increase(pledges_total{application="e2e-shop"}[1h]))
```

**pledges.rows** (outcome: updated), average per run  
`src/main/java/com/example/e2e/pledge/PledgeRepository.java:29`

```promql
sum by (outcome) (rate(pledges_rows_sum{application="e2e-shop"}[1h])) / sum by (outcome) (rate(pledges_rows_count{application="e2e-shop"}[1h]))
```

**edge.processing** (outcome: completed), per hour  
`src/main/java/com/example/e2e/edge/EdgeFlow.java:56`

```promql
sum by (outcome) (increase(edge_processing_total{application="e2e-shop"}[1h]))
```

**export.job** (outcome: completed), per hour  
`src/main/java/com/example/e2e/job/ExportJob.java:16`

```promql
sum by (outcome) (increase(export_job_total{application="e2e-shop"}[1h]))
```

**order** (outcome: placed), per hour  
`src/main/java/com/example/e2e/order/OrderService.java:39`

```promql
sum by (outcome) (increase(order_total{application="e2e-shop"}[1h]))
```

**order.confirmation** (outcome: sent), per hour  
`src/main/java/com/example/e2e/order/OrderNotifications.java:19`

```promql
sum by (outcome) (increase(order_confirmation_total{application="e2e-shop"}[1h]))
```

**order.shipment** (outcome: skipped), per hour  
`src/main/java/com/example/e2e/order/OrderService.java:46`

```promql
sum by (outcome) (increase(order_shipment_total{application="e2e-shop"}[1h]))
```

**orders** (outcome: shipped), per hour  
`src/main/java/com/example/e2e/order/OrderService.java:51`

```promql
sum by (outcome) (increase(orders_total{application="e2e-shop"}[1h]))
```

**orders.items** (outcome: shipped), per hour  
`src/main/java/com/example/e2e/order/OrderService.java:51`

```promql
sum by (outcome) (increase(orders_items_total{application="e2e-shop"}[1h]))
```

**preload.export** (outcome: completed), per hour  
`src/main/java/com/example/e2e/job/PreloadJob.java:18`

```promql
sum by (outcome) (increase(preload_export_total{application="e2e-shop"}[1h]))
```

## New Relic (NRQL)

Metrics as Micrometer sends them over OTLP, New Relic's recommended route (resource attribute `service.name`, delta temporality): a timer is a distribution, a counter a count. Durations are in the unit the registry sends.

### HTTP endpoints (`http.server.requests`)

Calls per minute, failed share (%), average and p95 duration, per method, uri:

```sql
FROM Metric SELECT rate(count(http.server.requests), 1 minute), percentage(count(http.server.requests), WHERE outcome = 'SERVER_ERROR') AS 'failed %', average(http.server.requests), percentile(http.server.requests, 95) WHERE service.name = 'e2e-shop' FACET method, uri SINCE 1 hour ago
```

Calls per minute over time:

```sql
FROM Metric SELECT rate(count(http.server.requests), 1 minute) WHERE service.name = 'e2e-shop' FACET method, uri TIMESERIES
```

### Scheduled jobs (`tasks.scheduled.execution`)

Calls per minute, failed share (%), average and p95 duration, per code.namespace, code.function:

```sql
FROM Metric SELECT rate(count(tasks.scheduled.execution), 1 minute), percentage(count(tasks.scheduled.execution), WHERE outcome = 'ERROR') AS 'failed %', average(tasks.scheduled.execution), percentile(tasks.scheduled.execution, 95) WHERE service.name = 'e2e-shop' FACET code.namespace, code.function SINCE 1 hour ago
```

Calls per minute over time:

```sql
FROM Metric SELECT rate(count(tasks.scheduled.execution), 1 minute) WHERE service.name = 'e2e-shop' FACET code.namespace, code.function TIMESERIES
```

### Kafka listeners (`spring.kafka.listener`)

Calls per minute, failed share (%), average and p95 duration, per spring.kafka.listener.id:

```sql
FROM Metric SELECT rate(count(spring.kafka.listener), 1 minute), percentage(count(spring.kafka.listener), WHERE error != 'none') AS 'failed %', average(spring.kafka.listener), percentile(spring.kafka.listener, 95) WHERE service.name = 'e2e-shop' FACET spring.kafka.listener.id SINCE 1 hour ago
```

Calls per minute over time:

```sql
FROM Metric SELECT rate(count(spring.kafka.listener), 1 minute) WHERE service.name = 'e2e-shop' FACET spring.kafka.listener.id TIMESERIES
```

### Observed flows and timers

**pledge-messages.on-batch** (kafka @KafkaListener pledge-batches): calls, failures, average and slowest duration

```sql
FROM Metric SELECT count(`pledge-messages.on-batch`), filter(count(`pledge-messages.on-batch`), WHERE error != 'none') AS 'failed', average(`pledge-messages.on-batch`), max(`pledge-messages.on-batch`) WHERE service.name = 'e2e-shop' TIMESERIES
```

**order.notifications** (event @TransactionalEventListener OrderNotifications.onCommitted(OrderPlaced)): calls, failures, average and slowest duration

```sql
FROM Metric SELECT count(order.notifications), filter(count(order.notifications), WHERE error != 'none') AS 'failed', average(order.notifications), max(order.notifications) WHERE service.name = 'e2e-shop' TIMESERIES
```

**publishing**: calls, failures, average and slowest duration

```sql
FROM Metric SELECT count(publishing), filter(count(publishing), WHERE error != 'none') AS 'failed', average(publishing), max(publishing) WHERE service.name = 'e2e-shop' TIMESERIES
```

**settlement-job-executor.on-ready** (event @EventListener SettlementJobExecutor.onReady(ApplicationReadyEvent)): calls, failures, average and slowest duration

```sql
FROM Metric SELECT count(`settlement-job-executor.on-ready`), filter(count(`settlement-job-executor.on-ready`), WHERE error != 'none') AS 'failed', average(`settlement-job-executor.on-ready`), max(`settlement-job-executor.on-ready`) WHERE service.name = 'e2e-shop' TIMESERIES
```

### Business outcomes

**tally** (outcome: completed), per hour

```sql
FROM Metric SELECT sum(tally) WHERE service.name = 'e2e-shop' FACET outcome TIMESERIES 1 hour
```

**pledge** (outcome: audited, parsed, published, sent), per hour

```sql
FROM Metric SELECT sum(pledge) WHERE service.name = 'e2e-shop' FACET outcome TIMESERIES 1 hour
```

**pledge.messages** (outcome: completed), per hour

```sql
FROM Metric SELECT sum(pledge.messages) WHERE service.name = 'e2e-shop' FACET outcome TIMESERIES 1 hour
```

**pledge.allotment** (outcome: completed), per hour

```sql
FROM Metric SELECT sum(pledge.allotment) WHERE service.name = 'e2e-shop' FACET outcome TIMESERIES 1 hour
```

**pledge.resend** (outcome: failed), per hour

```sql
FROM Metric SELECT sum(pledge.resend) WHERE service.name = 'e2e-shop' FACET outcome TIMESERIES 1 hour
```

**pledge.usage** (outcome: saved), per hour

```sql
FROM Metric SELECT sum(pledge.usage) WHERE service.name = 'e2e-shop' FACET outcome TIMESERIES 1 hour
```

**pledge.usage.uses** (outcome: saved), per hour

```sql
FROM Metric SELECT sum(pledge.usage.uses) WHERE service.name = 'e2e-shop' FACET outcome TIMESERIES 1 hour
```

**pledges** (outcome: updated), per hour

```sql
FROM Metric SELECT sum(pledges) WHERE service.name = 'e2e-shop' FACET outcome TIMESERIES 1 hour
```

**pledges.rows** (outcome: updated), per run

```sql
FROM Metric SELECT average(pledges.rows), max(pledges.rows) WHERE service.name = 'e2e-shop' FACET outcome TIMESERIES
```

**edge.processing** (outcome: completed), per hour

```sql
FROM Metric SELECT sum(edge.processing) WHERE service.name = 'e2e-shop' FACET outcome TIMESERIES 1 hour
```

**export.job** (outcome: completed), per hour

```sql
FROM Metric SELECT sum(export.job) WHERE service.name = 'e2e-shop' FACET outcome TIMESERIES 1 hour
```

**order** (outcome: placed), per hour

```sql
FROM Metric SELECT sum(order) WHERE service.name = 'e2e-shop' FACET outcome TIMESERIES 1 hour
```

**order.confirmation** (outcome: sent), per hour

```sql
FROM Metric SELECT sum(order.confirmation) WHERE service.name = 'e2e-shop' FACET outcome TIMESERIES 1 hour
```

**order.shipment** (outcome: skipped), per hour

```sql
FROM Metric SELECT sum(order.shipment) WHERE service.name = 'e2e-shop' FACET outcome TIMESERIES 1 hour
```

**orders** (outcome: shipped), per hour

```sql
FROM Metric SELECT sum(orders) WHERE service.name = 'e2e-shop' FACET outcome TIMESERIES 1 hour
```

**orders.items** (outcome: shipped), per hour

```sql
FROM Metric SELECT sum(orders.items) WHERE service.name = 'e2e-shop' FACET outcome TIMESERIES 1 hour
```

**preload.export** (outcome: completed), per hour

```sql
FROM Metric SELECT sum(preload.export) WHERE service.name = 'e2e-shop' FACET outcome TIMESERIES 1 hour
```

## Splunk (SPL)

Logs as Spring Boot writes them in the `logstash` format, read as JSON (sourcetype `_json`, or `KV_MODE=json`; otherwise add `| spath` after the base search). The format comes from logging.structuredFormat in logast.yaml, else Spring's logging.structured.format.console, else the encoder the service uses; if the fields below are not in your logs, set logging.structuredFormat.

Base search: every line of the service.

```spl
index=shop-logs "service.name"="e2e-shop"
```

### Errors

Errors and warnings over time:

```spl
index=shop-logs "service.name"="e2e-shop" (level=ERROR OR level=WARN)
| timechart span=5m count by level
```

The most frequent errors:

```spl
index=shop-logs "service.name"="e2e-shop" level=ERROR
| stats count latest(_time) as last by logger_name, message
| sort - count
```

Exceptions by type (the first line of the stack trace):

```spl
index=shop-logs "service.name"="e2e-shop" stack_trace=*
| rex field=stack_trace "^(?<exception>[\w.$]+)"
| stats count by exception, logger_name
| sort - count
```

### One request, run or business id

Everything one trace logged, in order (the trace id from an error response or another line):

```spl
index=shop-logs "service.name"="e2e-shop" traceId="<trace id>"
| sort _time
| table _time level logger_name message
```

Everything logged for one `sponsorshipId`, across requests and jobs:

```spl
index=shop-logs "service.name"="e2e-shop" sponsorshipId="<sponsorshipId>"
| sort _time
| table _time traceId level message
```

Everything logged for one `pledgeId`, across requests and jobs:

```spl
index=shop-logs "service.name"="e2e-shop" pledgeId="<pledgeId>"
| sort _time
| table _time traceId level message
```

Everything logged for one `orderId`, across requests and jobs:

```spl
index=shop-logs "service.name"="e2e-shop" orderId="<orderId>"
| sort _time
| table _time traceId level message
```

The `sponsorshipId` values with the most errors:

```spl
index=shop-logs "service.name"="e2e-shop" level=ERROR sponsorshipId=*
| stats count dc(traceId) as traces by sponsorshipId
| sort - count
| head 20
```

The `pledgeId` values with the most errors:

```spl
index=shop-logs "service.name"="e2e-shop" level=ERROR pledgeId=*
| stats count dc(traceId) as traces by pledgeId
| sort - count
| head 20
```

The `orderId` values with the most errors:

```spl
index=shop-logs "service.name"="e2e-shop" level=ERROR orderId=*
| stats count dc(traceId) as traces by orderId
| sort - count
| head 20
```

### Log volume

The loggers that write the most (what LOG_VOLUME estimates, from real traffic):

```spl
index=shop-logs "service.name"="e2e-shop"
| stats count by logger_name, level
| sort - count
| head 20
```
