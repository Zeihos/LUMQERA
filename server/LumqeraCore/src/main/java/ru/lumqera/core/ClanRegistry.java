package ru.lumqera.core;

import java.util.*;

/** Pure Java in-memory clan state, without Minecraft dependencies. All methods called from the main thread. */
final class ClanRegistry {
    static final class Clan {
        final String name;
        UUID owner;
        final Set<UUID> members = new LinkedHashSet<>();
        final Set<UUID> officers = new LinkedHashSet<>();
        int influence;

        Clan(String name, UUID owner) {
            this.name = name;
            this.owner = owner;
            members.add(owner);
        }
        boolean isOfficerOrOwner(UUID player) { return owner.equals(player) || officers.contains(player); }
    }

    private final Map<String, Clan> clans = new LinkedHashMap<>();
    private final Map<UUID, String> membership = new HashMap<>();

    static String key(String name) { return name.toLowerCase(Locale.ROOT); }
    static boolean validName(String name) { return name != null && name.matches("[A-Za-z0-9_]{3,12}"); }
    Collection<Clan> all() { return Collections.unmodifiableCollection(clans.values()); }
    Clan byName(String name) { return name == null ? null : clans.get(key(name)); }
    Clan of(UUID player) { String id = membership.get(player); return id == null ? null : clans.get(id); }
    boolean sameClan(UUID first, UUID second) {
        String one = membership.get(first);
        return one != null && one.equals(membership.get(second));
    }
    boolean create(String name, UUID owner) {
        if (!validName(name) || owner == null || byName(name) != null || of(owner) != null) return false;
        return register(name, owner, List.of(owner), List.of(), 0);
    }
    /** Also used for restoring stored clans; ignores duplicate / untrusted membership entries. */
    boolean register(String name, UUID owner, Collection<UUID> members, Collection<UUID> officers, int influence) {
        if (!validName(name) || owner == null || byName(name) != null || of(owner) != null) return false;
        Clan clan = new Clan(name, owner);
        if (members != null) {
            for (UUID id : members) {
                if (id != null && !membership.containsKey(id)) clan.members.add(id);
            }
        }
        if (officers != null) {
            for (UUID id : officers) if (id != null && !id.equals(owner) && clan.members.contains(id)) clan.officers.add(id);
        }
        clan.influence = Math.max(0, influence);
        clans.put(key(name), clan);
        for (UUID id : clan.members) membership.put(id, key(name));
        return true;
    }
    boolean addMember(Clan clan, UUID id, int maximum) {
        if (!exists(clan) || id == null || of(id) != null || clan.members.size() >= maximum) return false;
        clan.members.add(id);
        membership.put(id, key(clan.name));
        return true;
    }
    boolean removeMember(Clan clan, UUID id) {
        if (!exists(clan) || clan.owner.equals(id) || !clan.members.remove(id)) return false;
        clan.officers.remove(id);
        membership.remove(id);
        return true;
    }
    boolean promote(Clan clan, UUID actor, UUID target) {
        if (!exists(clan) || !clan.owner.equals(actor) || !clan.members.contains(target) || clan.owner.equals(target)) return false;
        return clan.officers.add(target);
    }
    boolean demote(Clan clan, UUID actor, UUID target) {
        if (!exists(clan) || !clan.owner.equals(actor)) return false;
        return clan.officers.remove(target);
    }
    boolean transfer(Clan clan, UUID actor, UUID target) {
        if (!exists(clan) || !clan.owner.equals(actor) || clan.owner.equals(target) || !clan.members.contains(target)) return false;
        clan.officers.remove(target);
        clan.officers.add(actor);
        clan.owner = target;
        return true;
    }
    boolean disband(Clan clan) {
        if (!exists(clan)) return false;
        clans.remove(key(clan.name));
        for (UUID member : clan.members) membership.remove(member);
        return true;
    }
    void addInfluence(UUID player, int amount) {
        Clan clan = of(player);
        if (clan != null && amount > 0) clan.influence = (int) Math.min(Integer.MAX_VALUE, (long) clan.influence + amount);
    }
    private boolean exists(Clan clan) { return clan != null && clans.get(key(clan.name)) == clan; }
}
