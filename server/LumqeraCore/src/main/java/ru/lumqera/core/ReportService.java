package ru.lumqera.core;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import java.util.function.Supplier;

/** Private ticket storage; writes atomically and rolls back the in-memory change on disk errors. */
final class ReportService {
    private final Path file;
    private final Logger logger;
    private final ReportRegistry registry;
    private boolean healthy = true;

    ReportService(File folder, Logger logger, int capacity, long cooldownSeconds) {
        file = new File(folder, "reports.yml").toPath();
        this.logger = logger;
        registry = new ReportRegistry(Math.max(1, Math.min(10000, capacity)),
                Math.max(0, Math.min(3600, cooldownSeconds)) * 1000);
    }
    boolean healthy() { return healthy; }
    ReportRegistry.Ticket byId(long id) { return registry.byId(id); }
    List<ReportRegistry.Ticket> latestOpen(int count) { return registry.latestOpen(count); }
    long openCount() { return registry.openCount(); }
    ReportRegistry.Review review(long id) { return registry.review(id); }
    void load() {
        if (!file.toFile().exists()) return;
        try {
            YamlConfiguration data = YamlConfiguration.loadConfiguration(file.toFile());
            int schema = data.getInt("schema-version", -1);
            if (schema != 1 && schema != 2) throw new IllegalArgumentException("wrong schema version");
            long next = data.getLong("next-id", -1);
            List<ReportRegistry.Ticket> tickets = new ArrayList<>();
            ConfigurationSection section = data.getConfigurationSection("reports");
            if (section == null && data.contains("reports"))
                throw new IllegalArgumentException("reports must be a YAML section");
            if (section != null) for (String idKey : section.getKeys(false)) {
                String key = "reports." + idKey;
                boolean closed = data.getBoolean(key + ".closed", false);
                tickets.add(new ReportRegistry.Ticket(Long.parseLong(idKey),
                        UUID.fromString(Objects.requireNonNull(data.getString(key + ".reporter"))),
                        UUID.fromString(Objects.requireNonNull(data.getString(key + ".target"))),
                        Objects.requireNonNull(data.getString(key + ".reason")),
                        data.getLong(key + ".created-at"), closed,
                        data.getString(key + ".closed-by", ""), data.getLong(key + ".closed-at", 0)));
            }
            Map<Long, ReportRegistry.Review> reviews = new HashMap<>();
            if (schema == 1 && data.contains("reviews"))
                throw new IllegalArgumentException("schema v1 must not contain reviews");
            if (schema == 2) {
                ConfigurationSection section2 = data.getConfigurationSection("reviews");
                if (section2 == null && data.contains("reviews"))
                    throw new IllegalArgumentException("reviews must be a YAML section");
                if (section2 != null) for (String key : section2.getKeys(false)) {
                    String path = "reviews." + key;
                    if (!data.isString(path + ".assigned-to") || !data.isSet(path + ".assigned-at") ||
                            !data.isList(path + ".notes"))
                        throw new IllegalArgumentException("incomplete review record");
                    String assigned = data.getString(path + ".assigned-to", "");
                    long assignedAt = data.getLong(path + ".assigned-at", 0);
                    List<ReportRegistry.StaffNote> notes = new ArrayList<>();
                    for (Map<?, ?> entry : data.getMapList(path + ".notes")) {
                        Object author = entry.get("author"), time = entry.get("created-at"), text = entry.get("text");
                        if (!(author instanceof String) || !(time instanceof Number) || !(text instanceof String))
                            throw new IllegalArgumentException("invalid note entry");
                        notes.add(new ReportRegistry.StaffNote((String) author, ((Number) time).longValue(), (String) text));
                    }
                    reviews.put(Long.parseLong(key), new ReportRegistry.Review(assigned, assignedAt, notes));
                }
            }
            registry.restore(tickets, next, reviews);
        } catch (RuntimeException ex) {
            healthy = false;
            logger.severe("reports.yml invalid; reporting disabled until data restored from backup: " + ex.getMessage());
        }
    }
    private boolean save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", 2);
        yaml.set("next-id", registry.nextId());
        for (ReportRegistry.Ticket t : registry.all()) {
            String key = "reports." + t.id();
            yaml.set(key + ".reporter", t.reporter().toString());
            yaml.set(key + ".target", t.target().toString());
            yaml.set(key + ".reason", t.reason());
            yaml.set(key + ".created-at", t.createdAt());
            yaml.set(key + ".closed", t.closed());
            yaml.set(key + ".closed-by", t.closedBy());
            yaml.set(key + ".closed-at", t.closedAt());
        }
        for (var item : registry.allReviews().entrySet()) {
            String key = "reviews." + item.getKey();
            var review = item.getValue();
            yaml.set(key + ".assigned-to", review.assignedTo());
            yaml.set(key + ".assigned-at", review.assignedAt());
            List<Map<String, Object>> notes = new ArrayList<>();
            for (var note : review.notes()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("author", note.author());
                row.put("created-at", note.createdAt());
                row.put("text", note.text());
                notes.add(row);
            }
            yaml.set(key + ".notes", notes);
        }
        return YamlStore.save(yaml, file, logger);
    }
    private ReportRegistry.Result change(Supplier<ReportRegistry.Result> edit) {
        if (!healthy) return new ReportRegistry.Result(ReportRegistry.Code.INVALID, null);
        List<ReportRegistry.Ticket> before = List.copyOf(registry.all());
        Map<Long, ReportRegistry.Review> priorReviews = registry.allReviews();
        long previousId = registry.nextId();
        ReportRegistry.Result result = edit.get();
        if (result.code() != ReportRegistry.Code.OK) return result;
        if (save()) return result;
        registry.restore(before, previousId, priorReviews);
        healthy = false; // fail closed: avoid accepting more actions after persistence failure
        logger.severe("Report storage failed; actions disabled until restart and disk repair");
        return new ReportRegistry.Result(ReportRegistry.Code.INVALID, null);
    }
    ReportRegistry.Result create(UUID reporter, UUID target, String reason, long timestamp) {
        return change(() -> registry.create(reporter, target, reason, timestamp));
    }
    ReportRegistry.Result close(long id, String staff, long timestamp) {
        return change(() -> registry.close(id, staff, timestamp));
    }
    ReportRegistry.Result claim(long id, String staff, long timestamp) {
        return change(() -> registry.claim(id, staff, timestamp));
    }
    ReportRegistry.Result release(long id, String staff) {
        return change(() -> registry.release(id, staff));
    }
    ReportRegistry.Result note(long id, String staff, String text, long timestamp) {
        return change(() -> registry.note(id, staff, text, timestamp));
    }
}
