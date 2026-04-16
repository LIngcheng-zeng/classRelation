package org.example.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AppConfig class.
 */
class AppConfigTest {

    private Path tempConfigFile;

    @BeforeEach
    void setUp() throws IOException {
        // Reset singleton before each test
        AppConfig.reset();
        tempConfigFile = Files.createTempFile("test-config", ".properties");
    }

    @AfterEach
    void tearDown() throws IOException {
        AppConfig.reset();
        if (tempConfigFile != null && Files.exists(tempConfigFile)) {
            Files.delete(tempConfigFile);
        }
    }

    @Test
    void testDefaultValues() {
        AppConfig config = AppConfig.getInstance();
        
        assertTrue(config.getPackageFilters().isEmpty(), "Default package filters should be empty");
        assertFalse(config.isStrictMode(), "Default strict mode should be false");
        assertEquals(0, config.getMaxComponentsToRender(), "Default max components should be 0");
        assertTrue(config.getSampleCodePath().isEmpty(), "Default sample code path should be empty");
        assertFalse(config.hasPackageFilters(), "Should not have package filters by default");
    }

    @Test
    void testLoadFromCustomPath() throws IOException {
        // Write test configuration
        try (FileWriter writer = new FileWriter(tempConfigFile.toFile())) {
            writer.write("package.filters=com.test.model,com.test.service\n");
            writer.write("package.strict.mode=true\n");
            writer.write("graph.max.components.to.render=5\n");
            writer.write("sample.code.path=/test/path\n");
        }

        // Set system property and reload
        System.setProperty("config.path", tempConfigFile.toString());
        AppConfig.reset();
        AppConfig config = AppConfig.getInstance();

        // Verify loaded values
        assertEquals(2, config.getPackageFilters().size());
        assertTrue(config.getPackageFilters().contains("com.test.model"));
        assertTrue(config.getPackageFilters().contains("com.test.service"));
        assertTrue(config.isStrictMode());
        assertEquals(5, config.getMaxComponentsToRender());
        assertEquals("/test/path", config.getSampleCodePath());
        assertTrue(config.hasPackageFilters());

        // Clean up system property
        System.clearProperty("config.path");
    }

    @Test
    void testInvalidMaxComponentsValue() throws IOException {
        // Write invalid value
        try (FileWriter writer = new FileWriter(tempConfigFile.toFile())) {
            writer.write("graph.max.components.to.render=invalid\n");
        }

        System.setProperty("config.path", tempConfigFile.toString());
        AppConfig.reset();
        AppConfig config = AppConfig.getInstance();

        // Should fall back to default
        assertEquals(0, config.getMaxComponentsToRender());

        System.clearProperty("config.path");
    }

    @Test
    void testEmptyPackageFilters() throws IOException {
        try (FileWriter writer = new FileWriter(tempConfigFile.toFile())) {
            writer.write("package.filters=\n");
        }

        System.setProperty("config.path", tempConfigFile.toString());
        AppConfig.reset();
        AppConfig config = AppConfig.getInstance();

        assertTrue(config.getPackageFilters().isEmpty());
        assertFalse(config.hasPackageFilters());

        System.clearProperty("config.path");
    }

    @Test
    void testWhitespaceInPackageFilters() throws IOException {
        try (FileWriter writer = new FileWriter(tempConfigFile.toFile())) {
            writer.write("package.filters= com.test.model , com.test.service , \n");
        }

        System.setProperty("config.path", tempConfigFile.toString());
        AppConfig.reset();
        AppConfig config = AppConfig.getInstance();

        List<String> filters = config.getPackageFilters();
        assertEquals(2, filters.size());
        assertEquals("com.test.model", filters.get(0));
        assertEquals("com.test.service", filters.get(1));

        System.clearProperty("config.path");
    }
}
