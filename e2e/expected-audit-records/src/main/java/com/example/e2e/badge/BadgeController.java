package com.example.e2e.badge;

import java.security.Principal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
        badges.renew(badgeId, principal.getName());
    }

    @PostMapping("/badges/{badgeId}/lock")
    public void lock(@PathVariable String badgeId, Principal principal) {
        badges.lock(badgeId, principal.getName());
    }

    @PostMapping("/badges/{badgeId}/pin")
    public void changePin(@PathVariable String badgeId, @RequestParam String newPin, @RequestParam String tokenId,
                          @RequestBody BadgeRequest request, Principal principal) {
        badges.changePin(badgeId, newPin, tokenId, request, principal.getName());
    }

    @PostMapping("/badges/{badgeId}/expire")
    public void expire(@PathVariable String badgeId) {
        badges.expire(badgeId);
    }

    @PostMapping("/badges/{badgeId}/archive")
    public void archive(@PathVariable String badgeId, Principal principal) {
        badges.archive(badgeId, principal.getName());
    }
}
