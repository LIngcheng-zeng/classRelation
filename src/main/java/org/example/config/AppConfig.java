package org.example.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Application configuration loaded from classrelation.properties file.
 * 
 * Configuration file location (searched in order):
 * 1. System property: -Dconfig.path=/path/to/classrelation.properties
 * 2. Current directory: ./classrelation.properties
 * 3. Classpath: /classrelation.properties (src/main/resources)
 */
public class AppConfig {

    private static final String CONFIG_FILE = "classrelation.properties";
    
    // Package filter settings
    private List<String> packageFilters = new ArrayList<>();
    private boolean strictMode = false;
    
    // Graph rendering settings
    private int maxComponentsToRender = 0; // 0 means render all
    
    // Sample code repository path
    private String sampleCodePath = "";
    
    private static AppConfig instance;

    private AppConfig() {
        loadConfiguration();
    }

    /**
     * Gets the singleton instance of AppConfig.
     */
    public static synchronized AppConfig getInstance() {
        if (instance == null) {
            instance = new AppConfig();
        }
        return instance;
    }

    /**
     * Resets the singleton instance (useful for testing).
     */
    public static synchronized void reset() {
        instance = null;
    }

    private void loadConfiguration() {
        Properties props = new Properties();
        
        // Try to load from system property first
        String configPath = System.getProperty("config.path");
        if (configPath != null && !configPath.isEmpty()) {
            try (InputStream is = Files.newInputStream(Paths.get(configPath))) {
                props.load(is);
                System.out.println("Loaded configuration from: " + configPath);
            } catch (IOException e) {
                System.err.println("Warning: Failed to load config from " + configPath + ": " + e.getMessage());
            }
        }
        
        // Try current directory
        if (props.isEmpty()) {
            try (InputStream is = Files.newInputStream(Paths.get(CONFIG_FILE))) {
                props.load(is);
                System.out.println("Loaded configuration from: " + Paths.get(CONFIG_FILE).toAbsolutePath());
            } catch (IOException e) {
                // Ignore, try classpath next
            }
        }
        
        // Try classpath
        if (props.isEmpty()) {
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE)) {
                if (is != null) {
                    props.load(is);
                    System.out.println("Loaded configuration from classpath: " + CONFIG_FILE);
                }
            } catch (IOException e) {
                System.err.println("Warning: Failed to load config from classpath: " + e.getMessage());
            }
        }
        
        if (props.isEmpty()) {
            System.out.println("No configuration file found, using defaults");
        }
        
        // Parse properties
        parseProperties(props);
    }

    private void parseProperties(Properties props) {
        // Package filters
        String packages = props.getProperty("package.filters", "");
        if (!packages.isEmpty()) {
            String[] parts = packages.split(",");
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    packageFilters.add(trimmed);
                }
            }
        }
        
        // Strict mode
        strictMode = Boolean.parseBoolean(props.getProperty("package.strict.mode", "false"));
        
        // Max components to render
        try {
            maxComponentsToRender = Integer.parseInt(props.getProperty("graph.max.components.to.render", "0"));
        } catch (NumberFormatException e) {
            System.err.println("Warning: Invalid value for graph.max.components.to.render, using default (0)");
            maxComponentsToRender = 0;
        }
        
        // Sample code path
        sampleCodePath = props.getProperty("sample.code.path", "").trim();
    }

    // Getters
    
    public List<String> getPackageFilters() {
        return packageFilters;
    }

    public boolean isStrictMode() {
        return strictMode;
    }

    public int getMaxComponentsToRender() {
        return maxComponentsToRender;
    }

    public String getSampleCodePath() {
        return sampleCodePath;
    }

    /**
     * Checks if package filtering is configured.
     */
    public boolean hasPackageFilters() {
        return !packageFilters.isEmpty();
    }
}
