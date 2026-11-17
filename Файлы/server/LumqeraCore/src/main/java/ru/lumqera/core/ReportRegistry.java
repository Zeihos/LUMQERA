package ru.lumqera.core;

import java.util.*;

/** Bukkit-independent moderation ticket queue. All methods are called on the server thread. */
final class ReportRegistry {
    enum Code { OK, INVALID, SELF, COOLDOWN, DUPLICATE, CAPACITY, NOT_FOUND, ALREADY_CLOSED, CLAIMED, NOT_CLAIMED, NOT_OWNER, NOTE_LIMIT }
    record Ticket(long id, UUID reporter, UUID target, String reason, long createdAt,
                  boolean closed, String closedBy, long closedAt) { }
    record Result(Code code, Ticket ticket) { }
    record StaffNote(String author, long createdAt, String text) { }
    record Review(String assignedTo, long assignedAt, List<StaffNote> notes) {
        Review { notes = List.copyOf(notes); }
    }
    private final Map<Long, Review> reviews = new HashMap<>();
    private static final Review EMPTY_REVIEW = new Review("", 0L, List.of());
    static final int MAX_NOTES = 20;
    static final int MAX_NOTE_LENGTH = 240;
    private final NavigableMap<Long, Ticket> records = new TreeMap<>();
    private final int maxRecords;
    private final long cooldownMs;
    private long nextId = 1;

    ReportRegistry(int maxRecords, long cooldownMs) {
        if (maxRecords < 1 || maxRecords > 10000 || cooldownMs < 0 || cooldownMs > 3_600_000)
            throw new IllegalArgumentException("invalid ticket limits");
        this.maxRecords = maxRecords;
        this.cooldownMs = cooldownMs;
    }
    static String normalizeReason(String raw) {
        if (raw == null) throw new IllegalArgumentException("reason required");
        String cleaned = raw.strip();
        if (cleaned.length() < 8 || cleaned.length() > 180 || cleaned.indexOf('§') >= 0 ||
                cleaned.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("reason must be 8-180 printable characters");
        return cleaned;
    }
    static boolean validStaff(String name) { return name != null && name.matches("(?:[A-Za-z0-9_]{3,16}|CONSOLE)"); }
    long nextId() { return nextId; }
    Collection<Ticket> all() { return List.copyOf(records.values()); }
    Ticket byId(long id) { return records.get(id); }
    Review review(long id) { return reviews.getOrDefault(id, EMPTY_REVIEW); }
    Map<Long, Review> allReviews() { return Map.copyOf(reviews); }
    static String normalizeNote(String input) {
        if (input == null) throw new IllegalArgumentException("note missing");
        String cleaned = input.strip();
        if (cleaned.length() < 3 || cleaned.length() > MAX_NOTE_LENGTH ||
                cleaned.indexOf('§') >= 0 || cleaned.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("invalid note");
        return cleaned;
    }
    private static boolean canEdit(Review review, String staff) {
        return review.assignedTo().isEmpty() || review.assignedTo().equals(staff) || "CONSOLE".equals(staff);
    }
    Result claim(long id, String staff, long now) {
        Ticket ticket = records.get(id);
        if (ticket == null) return new Result(Code.NOT_FOUND, null);
        if (ticket.closed()) return new Result(Code.ALREADY_CLOSED, ticket);
        if (!validStaff(staff) || now < ticket.createdAt()) return new Result(Code.INVALID, null);
        Review old = review(id);
        if (!old.assignedTo().isEmpty()) return new Result(Code.CLAIMED, ticket);
        reviews.put(id, new Review(staff, now, old.notes()));
        return new Result(Code.OK, ticket);
    }
    Result release(long id, String staff) {
        Ticket ticket = records.get(id);
        if (ticket == null) return new Result(Code.NOT_FOUND, null);
        if (ticket.closed()) return new Result(Code.ALREADY_CLOSED, ticket);
        if (!validStaff(staff)) return new Result(Code.INVALID, null);
        Review old = review(id);
        if (old.assignedTo().isEmpty()) return new Result(Code.NOT_CLAIMED, ticket);
        if (!canEdit(old, staff)) return new Result(Code.NOT_OWNER, ticket);
        reviews.put(id, new Review("", 0, old.notes()));
        return new Result(Code.OK, ticket);
    }
    Result note(long id, String staff, String raw, long now) {
        Ticket ticket = records.get(id);
        if (ticket == null) return new Result(Code.NOT_FOUND, null);
        if (ticket.closed()) return new Result(Code.ALREADY_CLOSED, ticket);
        if (!validStaff(staff) || now < ticket.createdAt()) return new Result(Code.INVALID, null);
        Review old = review(id);
        if (!canEdit(old, staff)) return new Result(Code.NOT_OWNER, ticket);
        if (old.notes().size() >= MAX_NOTES) return new Result(Code.NOTE_LIMIT, ticket);
        final String clean;
        try { clean = normalizeNote(raw); } catch (IllegalArgumentException e) { return new Result(Code.INVALID, null); }
        if (!old.notes().isEmpty() && now < old.notes().get(old.notes().size() - 1).createdAt())
            return new Result(Code.INVALID, null);
        List<StaffNote> updated = new ArrayList<>(old.notes());
        updated.add(new StaffNote(staff, now, clean));
        reviews.put(id, new Review(old.assignedTo(), old.assignedAt(), updated));
        return new Result(Code.OK, ticket);
    }
    List<Ticket> latestOpen(int limit) {
        if (limit <= 0) return List.of();
        return records.descendingMap().values().stream().filter(r -> !r.closed()).limit(Math.min(limit, 100)).toList();
    }
    long openCount() { return records.values().stream().filter(t -> !t.closed()).count(); }
    Result create(UUID reporter, UUID target, String text, long now) {
        if (reporter == null || target == null || now <= 0 || nextId == Long.MAX_VALUE) return new Result(Code.INVALID, null);
        if (reporter.equals(target)) return new Result(Code.SELF, null);
        final String reason;
        try { reason = normalizeReason(text); } catch (IllegalArgumentException ex) { return new Result(Code.INVALID, null); }
        if (records.size() >= maxRecords) return new Result(Code.CAPACITY, null);
        for (Ticket item : records.descendingMap().values()) {
            if (!item.reporter().equals(reporter)) continue;
            if (!item.closed() && item.target().equals(target)) return new Result(Code.DUPLICATE, null);
            // Do not allow time moving backward to bypass the spam limit.
            if (now < item.createdAt() || now - item.createdAt() < cooldownMs) return new Result(Code.COOLDOWN, null);
            break; // latest ticket by ID is most recent for each reporter
        }
        Ticket ticket = new Ticket(nextId++, reporter, target, reason, now, false, "", 0);
        records.put(ticket.id(), ticket);
        return new Result(Code.OK, ticket);
    }
    Result close(long id, String staff, long now) {
        Ticket old = records.get(id);
        if (old == null) return new Result(Code.NOT_FOUND, null);
        if (old.closed()) return new Result(Code.ALREADY_CLOSED, old);
        if (!validStaff(staff) || now < old.createdAt() || now <= 0) return new Result(Code.INVALID, null);
        Review current = review(id);
        if (!canEdit(current, staff)) return new Result(Code.NOT_OWNER, old);
        if (!current.notes().isEmpty() && now < current.notes().get(current.notes().size() - 1).createdAt())
            return new Result(Code.INVALID, null);
        Ticket updated = new Ticket(old.id(), old.reporter(), old.target(), old.reason(), old.createdAt(), true, staff, now);
        records.put(id, updated);
        Review previous = review(id);
        if (!previous.assignedTo().isEmpty()) reviews.put(id, new Review("", 0L, previous.notes()));
        return new Result(Code.OK, updated);
    }
    void restore(Collection<Ticket> imported, long next) { restore(imported, next, Map.of()); }
    void restore(Collection<Ticket> imported, long next, Map<Long, Review> importedReviews) {
        if (imported == null || next < 1 || importedReviews == null) throw new IllegalArgumentException("bad ticket restore");
        TreeMap<Long, Ticket> rebuilt = new TreeMap<>();
        Set<String> openPairs = new HashSet<>();
        for (Ticket t : imported) {
            if (t == null || t.id() < 1 || t.id() >= next || t.reporter() == null || t.target() == null ||
                    t.reporter().equals(t.target()) || t.createdAt() <= 0 ||
                    !normalizeReason(t.reason()).equals(t.reason()) ||
                    (t.closed() ? (!validStaff(t.closedBy()) || t.closedAt() < t.createdAt()) :
                            (!"".equals(t.closedBy()) || t.closedAt() != 0)) ||
                    rebuilt.putIfAbsent(t.id(), t) != null)
                throw new IllegalArgumentException("invalid report record");
            if (!t.closed() && !openPairs.add(t.reporter() + "/" + t.target()))
                throw new IllegalArgumentException("duplicate open report");
        }
        if (rebuilt.size() > maxRecords) throw new IllegalArgumentException("too many reports");
        Map<Long, Review> checked = new HashMap<>();
        for (var entry : importedReviews.entrySet()) {
            Ticket ticket = rebuilt.get(entry.getKey());
            Review r = entry.getValue();
            if (ticket == null || r == null || r.assignedTo() == null || r.notes() == null ||
                    (!r.assignedTo().isEmpty() && (!validStaff(r.assignedTo()) || ticket.closed() || r.assignedAt() < ticket.createdAt())) ||
                    (r.assignedTo().isEmpty() && r.assignedAt() != 0) || r.notes().size() > MAX_NOTES)
                throw new IllegalArgumentException("bad review state");
            long last = ticket.createdAt();
            for (StaffNote note : r.notes()) {
                if (note == null || !validStaff(note.author()) || note.createdAt() < last ||
                        !normalizeNote(note.text()).equals(note.text()) ||
                        (ticket.closed() && note.createdAt() > ticket.closedAt()))
                    throw new IllegalArgumentException("bad review note");
                last = note.createdAt();
            }
            checked.put(entry.getKey(), r);
        }
        records.clear(); records.putAll(rebuilt); nextId = next;
        reviews.clear(); reviews.putAll(checked);
    }
}
