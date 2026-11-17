package ru.lumqera.core;

import java.util.*;

/** Pure server-thread state machine for item-for-item trades; no inventory is escrowed. */
final class TradeRegistry {
    enum Code { OK, INVALID, SELF, BUSY, NO_INVITE, NO_SESSION, EXPIRED, NOT_READY, BOTH_READY, COOLDOWN }
    record Invitation(UUID from, UUID to, long expiresAt) { }
    record Offer(String description, int slot, int amount) {
        Offer {
            if (description == null || description.isBlank() || description.length() > 80 ||
                    slot < 0 || slot >= 36 || amount < 1 || amount > 64)
                throw new IllegalArgumentException("invalid offer");
        }
    }
    record Session(UUID first, UUID second, Offer firstOffer, Offer secondOffer,
                   boolean firstReady, boolean secondReady, long expiresAt) {
        UUID partner(UUID player) {
            if (first.equals(player)) return second;
            if (second.equals(player)) return first;
            return null;
        }
        Offer offer(UUID player) { return first.equals(player) ? firstOffer : second.equals(player) ? secondOffer : null; }
        boolean ready(UUID player) { return first.equals(player) ? firstReady : second.equals(player) && secondReady; }
    }
    private final long invitationMs;
    private final long sessionMs;
    private final long inviteCooldownMs;
    private final Map<UUID, Long> lastInvited = new HashMap<>();
    private final Map<UUID, Invitation> invitations = new HashMap<>(); // indexed by recipient
    private final Map<UUID, Session> sessions = new HashMap<>(); // same immutable session by both players
    TradeRegistry(long invitationMs, long sessionMs) { this(invitationMs, sessionMs, 0L); }
    TradeRegistry(long invitationMs, long sessionMs, long inviteCooldownMs) {
        if (invitationMs < 1000 || sessionMs < 1000 || inviteCooldownMs < 0 || inviteCooldownMs > 3_600_000L)
            throw new IllegalArgumentException("invalid expiry or cooldown");
        this.invitationMs = invitationMs;
        this.sessionMs = sessionMs;
        this.inviteCooldownMs = inviteCooldownMs;
    }
    private static long deadline(long now, long duration) {
        if (now < 0 || now > Long.MAX_VALUE - duration) throw new IllegalArgumentException("bad time");
        return now + duration;
    }
    void prune(long now) {
        // Clear old cooldown records so long-running servers cannot accumulate unbounded entries.
        lastInvited.entrySet().removeIf(e -> now >= e.getValue() && now - e.getValue() >= inviteCooldownMs);
        invitations.values().removeIf(i -> now >= i.expiresAt());
        for (Session session : new HashSet<>(sessions.values())) {
            if (now >= session.expiresAt()) cancel(session.first());
        }
    }
    Invitation invitation(UUID to, long now) {
        prune(now); return invitations.get(to);
    }
    Session session(UUID player, long now) {
        prune(now); return sessions.get(player);
    }
    private boolean waiting(UUID player) {
        for (Invitation i : invitations.values()) if (i.from().equals(player) || i.to().equals(player)) return true;
        return false;
    }
    Code invite(UUID from, UUID to, long now) {
        if (from == null || to == null) return Code.INVALID;
        if (from.equals(to)) return Code.SELF;
        prune(now);
        if (sessions.containsKey(from) || sessions.containsKey(to) || waiting(from) || waiting(to)) return Code.BUSY;
        Long previous = lastInvited.get(from);
        if (previous != null && (now < previous || now - previous < inviteCooldownMs)) return Code.COOLDOWN;
        invitations.put(to, new Invitation(from, to, deadline(now, invitationMs)));
        lastInvited.put(from, now);
        return Code.OK;
    }
    Code accept(UUID to, UUID from, long now) {
        if (to == null || from == null) return Code.INVALID;
        prune(now);
        Invitation invite = invitations.get(to);
        if (invite == null || !invite.from().equals(from)) return Code.NO_INVITE;
        if (sessions.containsKey(to) || sessions.containsKey(from)) return Code.BUSY;
        invitations.remove(to);
        Session session = new Session(from, to, null, null, false, false, deadline(now, sessionMs));
        sessions.put(from, session); sessions.put(to, session);
        return Code.OK;
    }
    Code offer(UUID player, Offer offer, long now) {
        if (offer == null) return Code.INVALID;
        Session old = session(player, now);
        if (old == null) return Code.NO_SESSION;
        Session next = new Session(old.first(), old.second(),
                old.first().equals(player) ? offer : old.firstOffer(),
                old.second().equals(player) ? offer : old.secondOffer(),
                false, false, deadline(now, sessionMs));
        replace(next); return Code.OK;
    }
    Code confirm(UUID player, long now) {
        Session old = session(player, now);
        if (old == null) return Code.NO_SESSION;
        if (old.firstOffer() == null || old.secondOffer() == null) return Code.NOT_READY;
        Session next = new Session(old.first(), old.second(), old.firstOffer(), old.secondOffer(),
                old.firstReady() || old.first().equals(player),
                old.secondReady() || old.second().equals(player), deadline(now, sessionMs));
        replace(next);
        return next.firstReady() && next.secondReady() ? Code.BOTH_READY : Code.OK;
    }
    private void replace(Session next) { sessions.put(next.first(), next); sessions.put(next.second(), next); }
    boolean cancel(UUID player) {
        boolean removed = false;
        Session session = sessions.get(player);
        if (session != null) {
            sessions.remove(session.first()); sessions.remove(session.second()); removed = true;
        }
        removed |= invitations.values().removeIf(i -> i.from().equals(player) || i.to().equals(player));
        return removed;
    }
    Set<UUID> activePlayers(long now) { prune(now); return Set.copyOf(sessions.keySet()); }
    Set<UUID> unprunedPlayers() { return Set.copyOf(sessions.keySet()); }
}
