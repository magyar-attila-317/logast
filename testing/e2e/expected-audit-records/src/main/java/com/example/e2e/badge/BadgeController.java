package com.example.e2e.badge;

import java.security.Principal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class BadgeController {
    private final BadgeService badges;

    public BadgeController(BadgeService badges) {
        this.badges = badges;
    }

    @PostMapping("/badges/{badgeId}/revoke")
    public void revoke(@PathVariable String badgeId, Principal principal) {
        badges.revoke(badgeId, principal.getName());
    }

    @PostMapping("/badges/{badgeId}/suspend")
    public void suspend(@PathVariable String badgeId, Principal principal) {
        badges.suspend(badgeId, principal.getName());
    }

    @PostMapping("/badges/{badgeId}/renew")
    public void renew(@PathVariable String badgeId, Principal principal) {
        log.info("Badge renew started"); // logast:wording
        badges.renew(badgeId, principal.getName());
    }

    @PostMapping("/badges/{badgeId}/lock")
    public void lock(@PathVariable String badgeId, Principal principal) {
        log.info("Badge lock started"); // logast:wording
        badges.lock(badgeId, principal.getName());
    }

    @PostMapping("/badges/{badgeId}/pin")
    public void changePin(@PathVariable String badgeId, @RequestParam String newPin, @RequestParam String tokenId,
                          @RequestBody BadgeRequest request, Principal principal) {
                              log.info("Badge pin started"); // logast:wording
        badges.changePin(badgeId, newPin, tokenId, request, principal.getName());
    }

    @PostMapping("/badges/{badgeId}/expire")
    public void expire(@PathVariable String badgeId) {
        log.info("Badge expire started"); // logast:wording
        badges.expire(badgeId);
    }

    @PostMapping("/badges/{badgeId}/archive")
    public void archive(@PathVariable String badgeId, Principal principal) {
        log.info("Badge archive started"); // logast:wording
        badges.archive(badgeId, principal.getName());
    }
}
