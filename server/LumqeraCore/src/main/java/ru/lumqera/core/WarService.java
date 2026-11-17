package ru.lumqera.core;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.util.*;
import java.util.logging.Logger;

/** Disk snapshot for active wars and lifetime records; pending challenges intentionally do not persist. */
final class WarService {
    private final File file;
    private final Logger logger;
    private final WarRegistry state = new WarRegistry();
    WarService(File folder,Logger logger) { file=new File(folder,"wars.yml"); this.logger=logger; }
    WarRegistry state() { return state; }
    /** A partial war restore would erase dropped matches on the next save. Refuse to start. */
    void load(ClansService clans) {
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection records = yaml.getConfigurationSection("records");
        if (records == null && yaml.contains("records"))
            throw new IllegalStateException("wars.yml: records must be a section");
        if (records != null) for (String name : records.getKeys(false)) {
            if (clans.byName(name) == null) throw new IllegalStateException("wars.yml: unknown clan record " + name);
            String path = "records." + name;
            if (!state.restoreRecord(name, nonNegative(yaml.get(path + ".wins"), "wins"),
                    nonNegative(yaml.get(path + ".losses"), "losses"),
                    nonNegative(yaml.get(path + ".draws"), "draws")))
                throw new IllegalStateException("wars.yml: invalid/duplicate record " + name);
        }
        ConfigurationSection wars = yaml.getConfigurationSection("active");
        if (wars == null && yaml.contains("active"))
            throw new IllegalStateException("wars.yml: active must be a section");
        if (wars != null) for (String name : wars.getKeys(false)) {
            String path = "active." + name;
            String a = yaml.getString(path + ".a"), b = yaml.getString(path + ".b");
            if (clans.byName(a) == null || clans.byName(b) == null)
                throw new IllegalStateException("wars.yml: missing clan in war " + name);
            Object rawEnd = yaml.get(path + ".ends-at");
            if (!(rawEnd instanceof Number n) || n.longValue() <= 0 ||
                    n.doubleValue() != (double)n.longValue())
                throw new IllegalStateException("wars.yml: invalid end time for war " + name);
            if (!state.restoreWar(a, b, n.longValue(), nonNegative(yaml.get(path + ".goal"), "goal"),
                    nonNegative(yaml.get(path + ".a-score"), "a-score"),
                    nonNegative(yaml.get(path + ".b-score"), "b-score")))
                throw new IllegalStateException("wars.yml: invalid/duplicate war " + name);
        }
    }
    private static int nonNegative(Object raw, String name) {
        if (!(raw instanceof Number number) || number.doubleValue() != (double)number.longValue() ||
                number.longValue() < 0 || number.longValue() > Integer.MAX_VALUE)
            throw new IllegalStateException("wars.yml: invalid " + name);
        return number.intValue();
    }
    boolean save() {
        YamlConfiguration yaml=new YamlConfiguration();
        int i=0;
        for (WarRegistry.War w:state.active()) {
            String key="active."+(++i);
            yaml.set(key+".a",w.a); yaml.set(key+".b",w.b); yaml.set(key+".ends-at",w.endsAt);
            yaml.set(key+".goal",w.goal); yaml.set(key+".a-score",w.aScore); yaml.set(key+".b-score",w.bScore);
        }
        Set<String> recorded=new TreeSet<>();
        recorded.addAll(state.wins().keySet());
        recorded.addAll(state.losses().keySet());
        recorded.addAll(state.draws().keySet());
        for (String clan:recorded) {
            yaml.set("records."+clan+".wins",state.wins(clan));
            yaml.set("records."+clan+".losses",state.losses(clan));
            yaml.set("records."+clan+".draws",state.draws(clan));
        }
        return YamlStore.save(yaml,file.toPath(),logger);
    }
}
