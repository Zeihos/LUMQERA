package ru.lumqera.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ReportServiceTest {
    @TempDir Path folder;
    private final Logger log = Logger.getLogger("LumqeraReportTest");

    private ReportService service() {
        return new ReportService(folder.toFile(), log, 200, 60);
    }
    private void storage(String yaml) throws Exception {
        Files.writeString(folder.resolve("reports.yml"), yaml, StandardCharsets.UTF_8);
    }

    @Test void rejectsCorruptedReportsSectionWithoutOverwritingOriginal() throws Exception {
        String original = "schema-version: 2\nnext-id: 2\nreports: INVALID\n";
        storage(original);
        ReportService service = service();
        service.load();
        assertFalse(service.healthy());
        assertEquals(ReportRegistry.Code.INVALID,
                service.create(UUID.randomUUID(), UUID.randomUUID(), "test", 1000).code());
        assertEquals(original, Files.readString(folder.resolve("reports.yml")));
    }

    @Test void rejectsCorruptedReviewsSection() throws Exception {
        storage("schema-version: 2\nnext-id: 1\nreports: {}\nreviews: CORRUPTED\n");
        ReportService service = service();
        service.load();
        assertFalse(service.healthy());
    }

    @Test void rejectsReviewsOnOldSchemaToAvoidDiscardingUnknownData() throws Exception {
        storage("schema-version: 1\nnext-id: 1\nreports: {}\nreviews:\n  1: marker\n");
        ReportService service = service();
        service.load();
        assertFalse(service.healthy());
    }
}
