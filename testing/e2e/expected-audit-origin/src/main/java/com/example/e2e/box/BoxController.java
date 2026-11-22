package com.example.e2e.box;

import java.security.Principal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class BoxController {
    private final BoxStore store;
    private final BoxAudit audit;

    public BoxController(BoxStore store, BoxAudit audit) {
        this.store = store;
        this.audit = audit;
    }

    /** Saves an entity whose superclass is named like audit: no audit record. */
    @DeleteMapping("/boxes/{boxId}")
    public void remove(@PathVariable String boxId) {
        log.info("Box deletion started"); // logast:wording
        store.save(new Box(boxId));
    }

    /** Records the attempt first, the actor through a getter: failures are recorded too. */
    @DeleteMapping("/boxes/{boxId}/contents")
    public void empty(@PathVariable String boxId, Principal principal) {
        log.info("Box contents started"); // logast:wording
        audit.record("box.empty", boxId, principal.getName());
        store.clear(boxId);
    }

    /** The box's keeper, a domain object named like the actor: not who did it. */
    @PostMapping("/boxes/{boxId}/handover")
    public void handover(@PathVariable String boxId) {
        log.info("Box handover started"); // logast:wording
        BoxKeeper user = store.keeper(boxId);
        audit.record("box.handover", boxId, user);
    }

    /** The acting user as the client sends it: anyone can claim to be anyone. */
    @PostMapping("/boxes/{boxId}/lock")
    public void lock(@PathVariable String boxId, @RequestHeader("X-User") String user) {
        log.info("Box lock started"); // logast:wording
        audit.record("box.lock", boxId, user);
    }

    /** A flag named like an id (paid): not the record acted on. */
    @PostMapping("/boxes/{boxId}/pay")
    public void pay(@PathVariable String boxId, Principal principal) {
        log.info("Box pay started"); // logast:wording
        boolean paid = store.pay(boxId);
        audit.record("box.pay", paid, principal);
    }

    /** The actor through a local read from the principal. */
    @PostMapping("/boxes/{boxId}/label")
    public void label(@PathVariable String boxId, Principal principal) {
        log.info("Box label started"); // logast:wording
        String who = principal.getName();
        audit.record("box.label", boxId, who);
    }

    /** One record built by a constructor. */
    @PostMapping("/boxes/{boxId}/open")
    public void open(@PathVariable String boxId, Principal principal) {
        log.info("Box open started"); // logast:wording
        audit.record(new BoxAuditEntry("box.open", boxId, principal.getName()));
    }

    /** One record built by a builder chain. */
    @PostMapping("/boxes/{boxId}/close")
    public void close(@PathVariable String boxId, Principal principal) {
        log.info("Box close started"); // logast:wording
        audit.record(BoxAuditEntry.builder().action("box.close").box(boxId).by(principal.getName()).build());
    }
}
