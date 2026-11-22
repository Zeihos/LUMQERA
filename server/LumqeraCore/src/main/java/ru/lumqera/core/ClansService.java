package ru.lumqera.core;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.util.*;
import java.util.logging.Logger;

/** Persistence and invitations around the Bukkit-independent ClanRegistry. Main thread only. */
final class ClansService {
    record Invitation(String clanKey, long expiresAt) {}
    private final File file;
    private final Logger logger;
    private final ClanRegistry registry = new ClanRegistry();
    private final Map<UUID, Invitation> invitations = new HashMap<>();
    private final Map<UUID, Long> lastInvitedBy = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();

    ClansService(File dataFolder, Logger logger) { file = new File(dataFolder, "clans.yml"); this.logger = logger; }
    ClanRegistry state() { return registry; }
    Collection<ClanRegistry.Clan> all() { return registry.all(); }
    ClanRegistry.Clan of(UUID id) { return registry.of(id); }
    ClanRegistry.Clan byName(String name) { return registry.byName(name); }
    boolean sameClan(UUID a, UUID b) { return registry.sameClan(a, b); }
    private boolean commit(java.util.function.BooleanSupplier operation, String description) {
        ClanRegistry.Snapshot before = registry.snapshot();
        if (!operation.getAsBoolean()) return false;
        if (save()) return true;
        registry.restore(before);
        logger.severe("CLANS " + description + " was rolled back: clans.yml could not be saved");
        return false;
    }
    boolean create(String name, UUID owner) {
        return commit(() -> registry.create(name, owner), "create");
    }
    boolean remove(ClanRegistry.Clan clan, UUID player) {
        return commit(() -> registry.removeMember(clan, player), "leave/kick");
    }
    boolean promote(ClanRegistry.Clan clan, UUID actor, UUID target) {
        return commit(() -> registry.promote(clan, actor, target), "promote");
    }
    boolean demote(ClanRegistry.Clan clan, UUID actor, UUID target) {
        return commit(() -> registry.demote(clan, actor, target), "demote");
    }
    boolean transfer(ClanRegistry.Clan clan, UUID actor, UUID target) {
        return commit(() -> registry.transfer(clan, actor, target), "transfer");
    }
    boolean reward(UUID player, int points) {
        if (points <= 0 || of(player) == null) return false;
        return commit(() -> {
            ClanRegistry.Clan clan = of(player);
            int old = clan.influence;
            registry.addInfluence(player, points);
            return clan.influence != old;
        }, "reward");
    }
    boolean rewardClan(String name, int points) {
        ClanRegistry.Clan clan = byName(name);
        if (clan == null || points <= 0) return false;
        return commit(() -> {
            int old = clan.influence;
            clan.influence = (int) Math.min(Integer.MAX_VALUE, (long) old + points);
            return clan.influence != old;
        }, "reward-clan");
    }
    boolean disband(ClanRegistry.Clan clan) {
        if (!commit(() -> registry.disband(clan), "disband")) return false;
        // Only clear pending invitations after a *durable* disband.
        invitations.entrySet().removeIf(e -> e.getValue().clanKey().equals(ClanRegistry.key(clan.name)));
        return true;
    }
    void remember(UUID id, String name) {
        if (id == null || name == null || !name.matches("[A-Za-z0-9_]{3,16}")) return;
        String previous = names.put(id, name);
        if (Objects.equals(name, previous)) return;
        if (!save()) {
            if (previous == null) names.remove(id);
            else names.put(id, previous);
            logger.severe("CLANS saved nickname rolled back after failed clans.yml write");
        }
    }
    String knownName(UUID id) { return names.getOrDefault(id, id.toString().substring(0, 8)); }
    UUID memberByName(ClanRegistry.Clan clan, String nickname) {
        if (clan == null || nickname == null) return null;
        for (UUID id : clan.members) if (knownName(id).equalsIgnoreCase(nickname)) return id;
        return null;
    }
    boolean invite(ClanRegistry.Clan clan, UUID actor, UUID target, long ttlMs, long cooldownMs, long now) {
        if (clan == null || actor == null || target == null || now < 0 || ttlMs <= 0 || cooldownMs < 0
                || registry.byName(clan.name) != clan || !clan.isOfficerOrOwner(actor) || of(target) != null) return false;
        Long last = lastInvitedBy.get(actor);
        if (last != null && now >= last && now - last < cooldownMs) return false;
        lastInvitedBy.put(actor, now);
        long duration = Math.max(1000L, ttlMs);
        long expiresAt = now > Long.MAX_VALUE - duration ? Long.MAX_VALUE : now + duration;
        invitations.put(target, new Invitation(ClanRegistry.key(clan.name), expiresAt));
        return true;
    }
    ClanRegistry.Clan accept(UUID player, int maxMembers, long now) {
        Invitation invitation = invitations.get(player);
        if (invitation == null) return null;
        if (invitation.expiresAt() < now) {
            invitations.remove(player);
            return null;
        }
        ClanRegistry.Clan clan = byName(invitation.clanKey());
        if (clan == null) {
            invitations.remove(player);
            return null;
        }
        if (!commit(() -> registry.addMember(clan, player, maxMembers), "accept-invitation"))
            return null;
        invitations.remove(player);
        return clan;
    }
    void load() {
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection storedNames = yaml.getConfigurationSection("players");
        if (storedNames == null && yaml.contains("players")) throw new IllegalStateException("clans.yml has an invalid players section");
        if (storedNames != null) for (String id : storedNames.getKeys(false)) {
            try {
                String value = yaml.getString("players." + id);
                if (value == null || !value.matches("[A-Za-z0-9_]{3,16}"))
                    throw new IllegalStateException("Invalid saved player nickname for " + id);
                UUID playerId = canonicalUuid(id);
                if (names.putIfAbsent(playerId, value) != null)
                    throw new IllegalStateException("Duplicate saved nickname UUID: " + id);
            } catch (IllegalArgumentException ex) { throw new IllegalStateException("Invalid stored player name ID: " + id, ex); }
        }
        ConfigurationSection section = yaml.getConfigurationSection("clans");
        if (section == null) {
            if (yaml.contains("clans")) throw new IllegalStateException("clans.yml has an invalid clans section");
            return;
        }
        for (String name : section.getKeys(false)) {
            try {
                String prefix = "clans." + name;
                Object storedOwner = yaml.get(prefix + ".owner");
                if (!(storedOwner instanceof String ownerId))
                    throw new IllegalStateException("Missing/invalid clan owner: " + name);
                UUID owner = canonicalUuid(ownerId);
                // getStringList() silently drops invalid elements and converts a wrong type to [].
                // Never turn a corrupted membership or officer list into an apparently valid clan.
                List<UUID> members = parseUUIDs(yaml.get(prefix + ".members"), name + ".members");
                List<UUID> officers = parseUUIDs(yaml.get(prefix + ".officers"), name + ".officers");
                Object rawInfluence = yaml.get(prefix + ".influence");
                if (!(rawInfluence instanceof Integer || rawInfluence instanceof Long))
                    throw new IllegalStateException("Invalid clan influence type: " + name);
                long count = ((Number) rawInfluence).longValue();
                if (count < 0 || count > Integer.MAX_VALUE)
                    throw new IllegalStateException("Clan influence outside valid range: " + name);
                int influence = (int) count;
                if (!ClanRegistry.validName(name) || !members.contains(owner) ||
                    members.size() != new HashSet<>(members).size() ||
                    officers.size() != new HashSet<>(officers).size() ||
                    !members.containsAll(officers) || officers.contains(owner) || influence < 0 ||
                    members.stream().anyMatch(id -> registry.of(id) != null)) {
                    throw new IllegalStateException("Invalid clan membership or influence: " + name);
                }
                if (!registry.register(name, owner, members, officers, influence))
                    throw new IllegalStateException("Duplicate or invalid clan: " + name);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("Malformed stored clan: " + name, exception);
            }
        }
    }
    /** Reject malformed/shortened Java UUID aliases and types that Bukkit otherwise coerces. */
    private static UUID canonicalUuid(String text) {
        if (text == null || !text.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalArgumentException("Invalid UUID: " + text);
        UUID id = UUID.fromString(text);
        if (!id.toString().equalsIgnoreCase(text))
            throw new IllegalArgumentException("Non-canonical UUID: " + text);
        return id;
    }

    private static List<UUID> parseUUIDs(Object raw, String field) {
        if (!(raw instanceof List<?> stored))
            throw new IllegalStateException("Missing or invalid YAML list: " + field);
        List<UUID> result = new ArrayList<>();
        for (Object item : stored) {
            if (!(item instanceof String value))
                throw new IllegalStateException("Non-string clan member or officer in " + field);
            result.add(canonicalUuid(value));
        }
        return result;
    }
    boolean save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (ClanRegistry.Clan clan : all()) {
            String prefix = "clans." + clan.name;
            yaml.set(prefix + ".owner", clan.owner.toString());
            yaml.set(prefix + ".members", clan.members.stream().map(UUID::toString).toList());
            yaml.set(prefix + ".officers", clan.officers.stream().map(UUID::toString).toList());
            yaml.set(prefix + ".influence", clan.influence);
        }
        for (var entry : names.entrySet()) yaml.set("players." + entry.getKey(), entry.getValue());
        return YamlStore.save(yaml, file.toPath(), logger);
    }
}
