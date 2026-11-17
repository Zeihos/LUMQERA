package ru.lumqera.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/** Safe, read-only JSON file to copy to the public site. No server-side API or open port. */
final class PublicStatsExporter {
    private final ClansService clans;
    private final StatsService pvp;
    private final LandService land;
    private final WarService wars;
    private final OutpostService outposts;
    private final Path target;
    private final Logger logger;

    PublicStatsExporter(ClansService clans, StatsService pvp, LandService land, WarService wars, OutpostService outposts, Path folder, Logger logger) {
        this.clans = clans; this.pvp = pvp; this.land = land; this.wars=wars; this.outposts=outposts; this.target = folder.resolve("export/public-stats.json"); this.logger = logger;
    }
    static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> { if (ch < 32) result.append(String.format("\\u%04x", (int) ch)); else result.append(ch); }
            }
        }
        return result.append('"').toString();
    }
    String asJson() {
        StringBuilder json = new StringBuilder("{\n  \"schemaVersion\": 1,\n  \"status\": \"development\",\n  \"updatedAt\": ")
                .append(quote(Instant.now().toString())).append(",\n  \"clans\": [\n");
        List<ClanRegistry.Clan> sortedClans = new ArrayList<>(clans.all());
        sortedClans.sort(Comparator.comparingInt((ClanRegistry.Clan c) -> c.influence).reversed().thenComparing(c -> c.name));
        for (int i = 0; i < sortedClans.size(); i++) {
            ClanRegistry.Clan clan = sortedClans.get(i);
            if (i > 0) json.append(",\n");
            json.append("    {\"name\": ").append(quote(clan.name))
                    .append(", \"leader\": ").append(quote(clans.knownName(clan.owner)))
                    .append(", \"members\": ").append(clan.members.size())
                    .append(", \"claims\": ").append(land.state().count(clan.name))
                    .append(", \"influence\": ").append(clan.influence)
                    .append(", \"wins\": ").append(wars.state().wins(clan.name))
                    .append(", \"losses\": ").append(wars.state().losses(clan.name))
                    .append('}');
        }
        json.append("\n  ],\n  \"outposts\": [\n");
        List<OutpostRegistry.Point> points = new ArrayList<>(outposts.state().all());
        for (int i=0;i<points.size();i++) {
            var point=points.get(i);
            if (i>0) json.append(",\n");
            json.append("    {\"name\": ").append(quote(point.name))
                    .append(", \"owner\": ").append(point.owner==null?"null":quote(point.owner)).append('}');
        }
        json.append("\n  ],\n  \"players\": [\n");
        List<Map.Entry<UUID, CombatRegistry.Stats>> sortedPlayers = new ArrayList<>(pvp.state().all().entrySet());
        sortedPlayers.sort(Comparator.<Map.Entry<UUID, CombatRegistry.Stats>>comparingInt(e -> e.getValue().kills).reversed()
                .thenComparing(e -> clans.knownName(e.getKey())));
        for (int i = 0; i < sortedPlayers.size(); i++) {
            var entry = sortedPlayers.get(i);
            if (i > 0) json.append(",\n");
            json.append("    {\"name\": ").append(quote(clans.knownName(entry.getKey())))
                    .append(", \"kills\": ").append(entry.getValue().kills)
                    .append(", \"deaths\": ").append(entry.getValue().deaths).append('}');
        }
        return json.append("\n  ]\n}\n").toString();
    }
    boolean export() {
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".stats-", ".tmp");
            try {
                Files.writeString(temporary, asJson(), StandardCharsets.UTF_8);
                try { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temporary); }
            return true;
        } catch (IOException ex) { logger.severe("JSON export failed: " + ex.getMessage()); return false; }
    }
}
