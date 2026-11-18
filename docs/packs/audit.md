# audit pack

Actions that must leave an audit record, not just a log line: who did what, to which record, with which outcome,
written where the log pipeline can keep it apart.

- **Opt-in:** `packs: [logging, tracing, audit]` in `logast.yaml`, or `logast scan . --pack audit`.
- **Settings:** `audit.emitters`, `audit.actions`, `audit.logger` in
  [configuration.md](../configuration.md#audit-pack-audit).

Legend: [rules.md](../rules.md#how-to-read-the-rule-tables).

## Rules

| rule | severity | finds | fix |
|---|---|---|---|
| AUDIT_MISSING | medium | an entrypoint matching `audit.actions` from which no audit emitter is reachable | report |
| AUDIT_FAILURES_UNRECORDED | medium | an auditable action that records its audit event only after its work succeeded (no audit call in a catch or finally): a rejected or failed attempt leaves no record | report (record the outcome either way, or the attempt before the work) |
| AUDIT_RECORD_INCOMPLETE | medium | an audit call that does not say who did it (an argument from the security context, or named like `userId`/`actor`/`principal`; or the audit method reads the security context itself) or to which record (a vocabulary id, or named like `…Id`); or an audit method in the code whose records cannot say the outcome (no outcome parameter or record field, nor every call passing one) or the request (no trace / correlation id passed, read from MDC or the tracer, or carried by a log line): both are attributes of every security event in the [OWASP Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html) | report (the outcome/correlation finding once, at the audit method) |
| AUDIT_LOG_MIXED | low | an audit class (named `*Audit*`, or a configured emitter) logs through a logger named after the class: its lines mix with the application log | AUTO: logs through the logger `audit` (policy `audit.logger`): `LoggerFactory.getLogger("audit")` / `@Slf4j(topic = "audit")`, so the log pipeline can route audit lines apart (own index, longer retention) |
| DATA_CHANGES_UNAUDITED | low | an entity changed through a Spring Data repository with no `@LastModifiedBy` field; or `@CreatedBy`/`@LastModifiedBy` fields with no `AuditorAware` bean to fill them; or a bulk statement on an audited entity (`@Modifying @Query("update …")`, `JdbcTemplate.update`/`batchUpdate`, `JdbcClient.sql("insert into …")` on its table): Spring Data auditing only runs when the entity is saved, so `@LastModifiedBy` keeps the previous editor | report (add the fields, `@EnableJpaAuditing`/`@EnableJdbcAuditing`, an `AuditorAware` reading the current user; for a bulk statement: go through `save()`, or set the editor column in the statement). Deletes are not reported |
| AUDIT_SECURITY_EVENTS | medium | Spring Security in use, but failed/successful logins and denied access are not recorded durably: no listener of its authentication events and no `AuditEventRepository` bean; or only Boot's `InMemoryAuditEventRepository` outside a dev/test `@Profile` (Boot recommends it [only for development](https://docs.spring.io/spring-boot/reference/actuator/auditing.html): bounded, lost on restart); or a listener of authorization events with no `AuthorizationEventPublisher` bean, which Spring Security [requires](https://docs.spring.io/spring-security/reference/servlet/authorization/events.html) to publish them | report |
| AUDIT_LOGGER_UNROUTED | medium | audit records go to the logger `audit` (or AUDIT_LOG_MIXED moves them there), but the service's `logback(-spring).xml` has no `<logger name="audit">` (they reach the root appenders, mixed with the application log), or drops them (`additivity="false"` without an appender-ref, or a level above theirs); or `logging.level.audit` is above the records' level. Without a logback file Boot's structured logging carries the logger name as a field the pipeline can route on: not reported | report |
| AUDIT_BEFORE_COMMIT | medium | an audit call inside a transaction (`@Transactional`, or reached from one on the same thread) whose audit method writes outside it (a log line, a Kafka/Rabbit/JMS message, an HTTP call, `REQUIRES_NEW`, another thread): a rolled-back action is recorded as done. Calls in a catch block and records written through the database are not reported | report (record it in a `@TransactionalEventListener` AFTER_COMMIT / AFTER_ROLLBACK, a `TransactionSynchronization`, or through the transaction's database) |
| AUDIT_RECORD_SECRET | high | a password, token, secret, PIN, full card number or IBAN passed to an audit call (an argument, a part of the record built in it, followed back through locals and callers), or an object whose class holds such a field; PII_IN_LOG's whole-word name matching with the secret terms; derived values (`tokenId`, `maskedIban`, `passwordHash`) are not secrets | report |
| AUDIT_ACTOR_LOST_ASYNC | high | an audit record that reads the acting user from the security context on another thread (an `@Async` method, an executor task): Spring Security keeps the context [per thread](https://docs.spring.io/spring-security/reference/servlet/authentication/architecture.html) (`MODE_THREADLOCAL` by default) and it is [lost on a new thread](https://docs.spring.io/spring-security/reference/features/integrations/concurrency.html). Not reported when a `DelegatingSecurityContext*` executor, a TaskDecorator copying the context, or Micrometer context propagation with Spring Security 6.5+'s `SecurityContextHolderThreadLocalAccessor` carries it | report (read the user before the hand-over and pass it in, or a `DelegatingSecurityContextAsyncTaskExecutor`) |

## Configuration

```yaml
audit:
  emitters: [com.example.audit.AuditService, com.example.audit.ActivityLogger#log]
  actions:                       # defaults: DELETE endpoints, /admin/**, export/delete/grant/revoke/refund/approve… methods
    - http: "* /api/payments/**"
    - term: refund               # a vocabulary term in the endpoint or method name
```
With no emitters listed, any call on a type named `*Audit*`, or passing an `*Audit*` argument (an `AuditAction`
value, an event implementing `AuditableDomainEvent`), counts as recording an audit event.
