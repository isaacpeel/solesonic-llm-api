package com.solesonic.pom;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PomConfigurationTest {

    @Test
    void testFlywayVersionsAreAligned() throws IOException {
        String pomContent = Files.readString(Paths.get("pom.xml"));
        
        String coreVersion = extractVersion(pomContent, "flyway-core");
        String postgresVersion = extractVersion(pomContent, "flyway-database-postgresql");
        
        assertNotNull(coreVersion, "Could not find flyway-core version in pom.xml");
        assertNotNull(postgresVersion, "Could not find flyway-database-postgresql version in pom.xml");
        
        assertEquals(coreVersion, postgresVersion, 
            String.format("Flyway version mismatch! core: %s, postgres: %s", coreVersion, postgresVersion));
    }

    private String extractVersion(String content, String artifactId) {
        // This regex is a bit simplistic but should work for the current pom.xml structure
        Pattern pattern = Pattern.compile("<artifactId>" + artifactId + "</artifactId>\\s*<version>(.*?)</version>");
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
