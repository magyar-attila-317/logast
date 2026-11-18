package com.example.e2e.badge;

/** What the client sends to issue a badge, its PIN included. */
public record BadgeRequest(String badgeId, String holder, String pin) {
}
