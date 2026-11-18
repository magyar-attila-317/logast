# Queries for e2e-shop

Written by `logast queries` from the code as it stands; run it again after `logast apply`. Time ranges and thresholds are starting points.

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
| DELETE /coupons/{couponId} | `method="DELETE", uri="/coupons/{couponId}"` |
| GET /audit/settings | `method="GET", uri="/audit/settings"` |
| GET /coupons | `method="GET", uri="/coupons"` |
| GET /coupons/{couponId}/details | `method="GET", uri="/coupons/{couponId}/details"` |
| GET /quotes/{productId} | `method="GET", uri="/quotes/{productId}"` |
| GET /quotes/{productId}/refresh | `method="GET", uri="/quotes/{productId}/refresh"` |
| POST /admin/coupons/{couponId}/revoke | `method="POST", uri="/admin/coupons/{couponId}/revoke"` |
| POST /coupons/redeem | `method="POST", uri="/coupons/redeem"` |
| POST /coupons/{couponId}/hold | `method="POST", uri="/coupons/{couponId}/hold"` |
| POST /coupons/{couponId}/publish | `method="POST", uri="/coupons/{couponId}/publish"` |
| POST /coupons/{couponId}/use | `method="POST", uri="/coupons/{couponId}/use"` |
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
| @Scheduled CouponCleanupJob.cleanup | `code_namespace="com.example.e2e.coupon.CouponCleanupJob", code_function="cleanup"` |
| @Scheduled CouponReloadJob.run | `code_namespace="com.example.e2e.coupon.CouponReloadJob", code_function="run"` |
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

#### coupon-messages.on-batch (kafka @KafkaListener coupon-batches)

Calls per second:

```promql
sum(rate(coupon_messages_on_batch_seconds_count{application="e2e-shop"}[5m]))
```

Failures per second, by exception:

```promql
sum by (error) (rate(coupon_messages_on_batch_seconds_count{application="e2e-shop", error!="none"}[5m]))
```

Average duration (seconds):

```promql
sum(rate(coupon_messages_on_batch_seconds_sum{application="e2e-shop"}[5m])) / sum(rate(coupon_messages_on_batch_seconds_count{application="e2e-shop"}[5m]))
```

Slowest call in the last minutes (seconds):

```promql
max(coupon_messages_on_batch_seconds_max{application="e2e-shop"})
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

**calculation** (outcome: completed), per hour  
`src/main/java/com/example/e2e/metrics/BusinessMetrics.java:79`

```promql
sum by (outcome) (increase(calculation_total{application="e2e-shop"}[1h]))
```

**coupon** (outcome: audited, parsed, published, sent), per hour  
`src/main/java/com/example/e2e/metrics/BusinessMetrics.java:44`

```promql
sum by (outcome) (increase(coupon_total{application="e2e-shop"}[1h]))
```

**coupon.lookup** (outcome: failed), per hour  
`src/main/java/com/example/e2e/metrics/BusinessMetrics.java:29`

```promql
sum by (outcome) (increase(coupon_lookup_total{application="e2e-shop"}[1h]))
```

**coupon.messages** (outcome: completed), per hour  
`src/main/java/com/example/e2e/metrics/BusinessMetrics.java:94`

```promql
sum by (outcome) (increase(coupon_messages_total{application="e2e-shop"}[1h]))
```

**coupon.redemption** (outcome: completed), per hour  
`src/main/java/com/example/e2e/metrics/BusinessMetrics.java:59`

```promql
sum by (outcome) (increase(coupon_redemption_total{application="e2e-shop"}[1h]))
```

**coupon.resend** (outcome: failed), per hour  
`src/main/java/com/example/e2e/metrics/BusinessMetrics.java:49`

```promql
sum by (outcome) (increase(coupon_resend_total{application="e2e-shop"}[1h]))
```

**coupon.usage** (outcome: saved), per hour  
`src/main/java/com/example/e2e/metrics/BusinessMetrics.java:39`

```promql
sum by (outcome) (increase(coupon_usage_total{application="e2e-shop"}[1h]))
```

**coupon.usage.uses** (outcome: saved), per hour  
`src/main/java/com/example/e2e/metrics/BusinessMetrics.java:39`

```promql
sum by (outcome) (increase(coupon_usage_uses_total{application="e2e-shop"}[1h]))
```

**coupons** (outcome: updated), per hour  
`src/main/java/com/example/e2e/metrics/BusinessMetrics.java:34`

```promql
sum by (outcome) (increase(coupons_total{application="e2e-shop"}[1h]))
```

**coupons.rows** (outcome: updated), average per run  
`src/main/java/com/example/e2e/metrics/BusinessMetrics.java:34`

```promql
sum by (outcome) (rate(coupons_rows_sum{application="e2e-shop"}[1h])) / sum by (outcome) (rate(coupons_rows_count{application="e2e-shop"}[1h]))
```

**edge.processing** (outcome: completed), per hour  
`src/main/java/com/example/e2e/metrics/BusinessMetrics.java:74`

```promql
sum by (outcome) (increase(edge_processing_total{application="e2e-shop"}[1h]))
```

**export.job** (outcome: completed), per hour  
`src/main/java/com/example/e2e/metrics/BusinessMetrics.java:84`

```promql
sum by (outcome) (increase(export_job_total{application="e2e-shop"}[1h]))
```

**order** (outcome: placed), per hour  
`src/main/java/com/example/e2e/metrics/BusinessMetrics.java:104`

```promql
sum by (outcome) (increase(order_total{application="e2e-shop"}[1h]))
```

**order.confirmation** (outcome: sent), per hour  
`src/main/java/com/example/e2e/metrics/BusinessMetrics.java:99`

```promql
sum by (outcome) (increase(order_confirmation_total{application="e2e-shop"}[1h]))
```

**order.shipment** (outcome: skipped), per hour  
`src/main/java/com/example/e2e/metrics/BusinessMetrics.java:109`

```promql
sum by (outcome) (increase(order_shipment_total{application="e2e-shop"}[1h]))
```

**orders** (outcome: shipped), per hour  
`src/main/java/com/example/e2e/metrics/BusinessMetrics.java:114`

```promql
sum by (outcome) (increase(orders_total{application="e2e-shop"}[1h]))
```

**orders.items** (outcome: shipped), per hour  
`src/main/java/com/example/e2e/metrics/BusinessMetrics.java:114`

```promql
sum by (outcome) (increase(orders_items_total{application="e2e-shop"}[1h]))
```

**preload.export** (outcome: completed), per hour  
`src/main/java/com/example/e2e/metrics/BusinessMetrics.java:89`

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

**coupon-messages.on-batch** (kafka @KafkaListener coupon-batches): calls, failures, average and slowest duration

```sql
FROM Metric SELECT count(`coupon-messages.on-batch`), filter(count(`coupon-messages.on-batch`), WHERE error != 'none') AS 'failed', average(`coupon-messages.on-batch`), max(`coupon-messages.on-batch`) WHERE service.name = 'e2e-shop' TIMESERIES
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

**calculation** (outcome: completed), per hour

```sql
FROM Metric SELECT sum(calculation) WHERE service.name = 'e2e-shop' FACET outcome TIMESERIES 1 hour
```

**coupon** (outcome: audited, parsed, published, sent), per hour

```sql
FROM Metric SELECT sum(coupon) WHERE service.name = 'e2e-shop' FACET outcome TIMESERIES 1 hour
```

**coupon.lookup** (outcome: failed), per hour

```sql
FROM Metric SELECT sum(coupon.lookup) WHERE service.name = 'e2e-shop' FACET outcome TIMESERIES 1 hour
```

**coupon.messages** (outcome: completed), per hour

```sql
FROM Metric SELECT sum(coupon.messages) WHERE service.name = 'e2e-shop' FACET outcome TIMESERIES 1 hour
```

**coupon.redemption** (outcome: completed), per hour

```sql
FROM Metric SELECT sum(coupon.redemption) WHERE service.name = 'e2e-shop' FACET outcome TIMESERIES 1 hour
```

**coupon.resend** (outcome: failed), per hour

```sql
FROM Metric SELECT sum(coupon.resend) WHERE service.name = 'e2e-shop' FACET outcome TIMESERIES 1 hour
```

**coupon.usage** (outcome: saved), per hour

```sql
FROM Metric SELECT sum(coupon.usage) WHERE service.name = 'e2e-shop' FACET outcome TIMESERIES 1 hour
```

**coupon.usage.uses** (outcome: saved), per hour

```sql
FROM Metric SELECT sum(coupon.usage.uses) WHERE service.name = 'e2e-shop' FACET outcome TIMESERIES 1 hour
```

**coupons** (outcome: updated), per hour

```sql
FROM Metric SELECT sum(coupons) WHERE service.name = 'e2e-shop' FACET outcome TIMESERIES 1 hour
```

**coupons.rows** (outcome: updated), per run

```sql
FROM Metric SELECT average(coupons.rows), max(coupons.rows) WHERE service.name = 'e2e-shop' FACET outcome TIMESERIES
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

Logs as Spring Boot writes them in the `logstash` format, read as JSON (sourcetype `_json`, or `KV_MODE=json`; otherwise add `| spath` after the base search).

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

Everything logged for one `agreementId`, across requests and jobs:

```spl
index=shop-logs "service.name"="e2e-shop" agreementId="<agreementId>"
| sort _time
| table _time traceId level message
```

Everything logged for one `couponId`, across requests and jobs:

```spl
index=shop-logs "service.name"="e2e-shop" couponId="<couponId>"
| sort _time
| table _time traceId level message
```

Everything logged for one `orderId`, across requests and jobs:

```spl
index=shop-logs "service.name"="e2e-shop" orderId="<orderId>"
| sort _time
| table _time traceId level message
```

The `agreementId` values with the most errors:

```spl
index=shop-logs "service.name"="e2e-shop" level=ERROR agreementId=*
| stats count dc(traceId) as traces by agreementId
| sort - count
| head 20
```

The `couponId` values with the most errors:

```spl
index=shop-logs "service.name"="e2e-shop" level=ERROR couponId=*
| stats count dc(traceId) as traces by couponId
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
