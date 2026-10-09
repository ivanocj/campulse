package com.ivanocj.cameras.availability.integration;

import com.ivanocj.cameras.availability.bootstrap.AvailabilityApplication;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PublicResourcesTest {
    @Test
    void runtimeResourcesContainOnlyReviewedConfigurationAndNeverBatchInputs() throws Exception {
        var classes = Path.of(AvailabilityApplication.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        try (var files = Files.walk(classes)) {
            var resources = files.filter(Files::isRegularFile)
                    .map(classes::relativize)
                    .map(Path::toString)
                    .filter(name -> !name.endsWith(".class"))
                    .sorted()
                    .toList();
            assertEquals(java.util.List.of("logback.xml"), resources,
                    "Runtime resources must not include device inventories, credentials, or test fixtures");
        }
    }
}
