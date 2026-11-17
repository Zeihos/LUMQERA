package ru.lumqera.core;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

/** Fail closed on a malformed ledger, atomic snapshot on every change. */
final class ModerationService {
    private final Path file;
    private final Logger logger;
    private final WarningRegistry ledger;
    private boolean healthy = true;
    ModerationService(File folder, Logger logger, int maxPerPlayer) {
        this.file = new File(folder, "warnings.yml").toPath();
        this.logger = logger;
        this.ledger = new WarningRegistry(Math.max(1, Math.min(10_000, maxPerPlayer)));
    }
    boolean healthy() { return healthy; }
    List<WarningRegistry.Warning> history(UUID player) { return ledger.forPlayer(player); }
    WarningRegistry.Warning find(long id) { return ledger.get(id); }
    void load() {
        if (!file.toFile().exists()) return;
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
            if (yaml.getInt("schema-version", -1) != 1) throw new IllegalArgumentException("bad schema");
            long next = yaml.getLong("next-id", 0);
            List<WarningRegistry.Warning> items = new ArrayList<>();
            ConfigurationSection section = yaml.getConfigurationSection("warnings");
            if (section != null) for (String key : section.getKeys(false)) {
                String base = "warnings." + key;
                long id = Long.parseLong(key);
                UUID player = UUID.fromString(Objects.requireNonNull(yaml.getString(base + ".player")));
                String moderator = Objects.requireNonNull(yaml.getString(base + ".moderator"));
                String reason = Objects.requireNonNull(yaml.getString(base + ".reason"));
                long issued = yaml.getLong(base + ".issued-at");
                items.add(new WarningRegistry.Warning(id, player, moderator, reason, issued));
            }
            ledger.restore(items, next);
        } catch (RuntimeException ex) {
            healthy = false;
            logger.severe("warnings.yml malformed; staff warnings disabled until backup restored: " + ex.getMessage());
        }
    }
    private boolean save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", 1);
        yaml.set("next-id", ledger.nextId());
        for (WarningRegistry.Warning w : ledger.all()) {
            String base = "warnings." + w.id();
            yaml.set(base + ".player", w.player().toString());
            yaml.set(base + ".moderator", w.moderator());
            yaml.set(base + ".reason", w.reason());
            yaml.set(base + ".issued-at", w.issuedAt());
        }
        return YamlStore.save(yaml, file, logger);
    }
    WarningRegistry.Warning warn(UUID player, String moderator, String reason, long now) {
        if (!healthy) return null;
        List<WarningRegistry.Warning> before = List.copyOf(ledger.all());
        long previous = ledger.nextId();
        WarningRegistry.Warning result = ledger.add(player, moderator, reason, now);
        if (save()) return result;
        ledger.restore(before, previous);
        return null;
    }
    boolean remove(long id) {
        if (!healthy || ledger.get(id) == null) return false;
        List<WarningRegistry.Warning> before = List.copyOf(ledger.all());
        long previous = ledger.nextId();
        ledger.remove(id);
        if (save()) return true;
        ledger.restore(before, previous);
        return false;
    }
}
