package com.playcity.stats;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistributionMetadataTest {

    @Test
    void descriptorDeclaresMariaDbAsAnExternalPaperLibrary() throws IOException {
        String descriptor = resourceText("plugin.yml");

        assertTrue(descriptor.contains("version: 0.3.2"));
        assertTrue(descriptor.contains("api-version: \"26.2\""));
        assertTrue(descriptor.contains("libraries:"));
        assertTrue(descriptor.contains("org.mariadb.jdbc:mariadb-java-client:3.5.2"));
    }

    @Test
    void distributionLicenseResourcesArePackaged() {
        List<String> resources = List.of(
            "META-INF/LICENSE-Stats.txt",
            "META-INF/THIRD_PARTY_NOTICES.md",
            "META-INF/licenses/Stats/Apache-2.0.txt",
            "META-INF/licenses/Stats/BSD-2-Clause-OnGres-SCRAM-2017.txt",
            "META-INF/licenses/Stats/BSD-2-Clause-OnGres-StringPrep-2019.txt",
            "META-INF/licenses/Stats/BSD-2-Clause-pgJDBC.txt",
            "META-INF/licenses/Stats/BSD-2-Clause-Zentus.txt",
            "META-INF/licenses/Stats/MIT-Checker-Qual.txt",
            "META-INF/licenses/Stats/MIT-SLF4J.txt",
            "META-INF/licenses/Stats/NOTICE-sqlite-jdbc.txt"
        );

        for (String resource : resources) {
            assertNotNull(getClass().getClassLoader().getResource(resource), resource);
        }
    }

    private String resourceText(String name) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(input, name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
