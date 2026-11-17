package ru.lumqera.core;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Fail-closed startup preflight for LUMQERA YAML, before any subsystem loads or saves data.
 * Pure JDK implementation so it can be regression-tested without a Paper server.
 * The caller supplies the server's YAML parser; NEVER use Bukkit's forgiving loadConfiguration here.
 */
final class DataFileGuard {
    private DataFileGuard() {}
    static final List<String> FILES = List.of("config.yml", "clans.yml", "pvp.yml", "land.yml",
            "travel.yml", "wars.yml", "outposts.yml", "economy.yml", "warnings.yml", "reports.yml");
    static final long MAX_FILE_BYTES = 32L * 1024L * 1024L;

    @FunctionalInterface interface Parser { void parse(String contents) throws Exception; }

    static List<String> inspect(Path directory, Parser yamlParser) {
        List<String> issues = new ArrayList<>();
        if (Files.exists(directory) && (!Files.isDirectory(directory) || Files.isSymbolicLink(directory))) {
            issues.add("Plugin data directory is not a normal directory");
            return issues;
        }
        for (String filename : FILES) {
            Path file = directory.resolve(filename);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) continue;
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                issues.add(filename + ": not a regular file");
                continue;
            }
            try {
                long size = Files.size(file);
                if (size > MAX_FILE_BYTES) { issues.add(filename + ": too large (over 32 MiB)"); continue; }
                byte[] bytes = Files.readAllBytes(file);
                String data = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
                if (data.indexOf('\0') >= 0) { issues.add(filename + ": contains NUL bytes"); continue; }
                yamlParser.parse(data);
            } catch (CharacterCodingException e) {
                issues.add(filename + ": invalid UTF-8");
            } catch (Exception e) {
                issues.add(filename + ": invalid/unreadable YAML (" + e.getClass().getSimpleName() + ")");
            }
        }
        return issues;
    }

    /** One dated snapshot for *all* existing files, or no startup; never overwrite a previous backup. */
    static Path snapshot(Path directory) throws IOException {
        Path backupRoot = directory.resolve("startup-backups");
        Files.createDirectories(backupRoot);
        String date = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        Path snapshot = Files.createTempDirectory(backupRoot, date + "-");
        try {
            for (String filename : FILES) {
                Path source = directory.resolve(filename);
                if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
                    Files.copy(source, snapshot.resolve(filename));
            }
            return snapshot;
        } catch (IOException | RuntimeException e) {
            // Leave a partial directory for forensic review, but refuse to start.
            throw new IOException("Could not finish pre-start backup at " + snapshot, e);
        }
    }
}
