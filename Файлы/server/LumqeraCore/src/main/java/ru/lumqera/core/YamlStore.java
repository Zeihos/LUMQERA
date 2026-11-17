package ru.lumqera.core;

import org.bukkit.configuration.file.YamlConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.logging.Logger;

/** Write snapshots to a temporary file first, so a crash cannot truncate the active YAML. */
final class YamlStore {
    private YamlStore() {}
    static boolean save(YamlConfiguration config, Path destination, Logger logger) {
        try {
            Files.createDirectories(destination.getParent());
            Path temp = Files.createTempFile(destination.getParent(), ".lumqera-", ".tmp");
            try {
                Files.writeString(temp, config.saveToString(), StandardCharsets.UTF_8);
                try { Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temp); }
            return true;
        } catch (IOException exception) {
            logger.severe("Could not save " + destination.getFileName() + ": " + exception.getMessage());
            return false;
        }
    }
}
