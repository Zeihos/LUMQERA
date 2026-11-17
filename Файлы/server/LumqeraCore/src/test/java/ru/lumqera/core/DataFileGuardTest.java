package ru.lumqera.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DataFileGuardTest {
    @TempDir Path directory;

    @Test
    void missingOptionalFilesDoNotFailPreflight() {
        assertTrue(DataFileGuard.inspect(directory, text -> {}).isEmpty());
    }

    @Test
    void rejectsMalformedUtf8InsteadOfSilentlyDroppingData() throws Exception {
        Files.write(directory.resolve("clans.yml"), new byte[] {(byte) 0xC3, (byte) 0x28});
        List<String> issues = DataFileGuard.inspect(directory, text -> {});
        assertEquals(1, issues.size());
        assertTrue(issues.getFirst().contains("invalid UTF-8"));
    }

    @Test
    void rejectsParserFailure() throws Exception {
        Files.writeString(directory.resolve("economy.yml"), "broken: [", StandardCharsets.UTF_8);
        List<String> issues = DataFileGuard.inspect(directory, text -> {
            throw new IllegalArgumentException("invalid YAML");
        });
        assertEquals(1, issues.size());
        assertTrue(issues.getFirst().startsWith("economy.yml:"));
    }

    @Test
    void refusesAFileInsteadOfDataDirectory() throws Exception {
        Path invalid = directory.resolve("not-a-directory");
        Files.writeString(invalid, "data");
        assertFalse(DataFileGuard.inspect(invalid, text -> {}).isEmpty());
    }

    @Test
    void snapshotCopiesExistingDataAndLeavesOriginalUntouched() throws Exception {
        Path economy = directory.resolve("economy.yml");
        Files.writeString(economy, "balance: 123", StandardCharsets.UTF_8);
        Path backup = DataFileGuard.snapshot(directory);
        assertEquals("balance: 123", Files.readString(backup.resolve("economy.yml")));
        assertEquals("balance: 123", Files.readString(economy));
    }
}
