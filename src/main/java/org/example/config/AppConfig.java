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
    private List<String> packageFilters  = new ArrayList<>();
    private List<String> excludedPackages = new ArrayList<>();
    private boolean strictMode = false;

    // Project path
    private String projectPath = "";

    // Output settings
    private String  outputDir       = ".";
    private boolean markdownEnabled = true;
    private boolean htmlEnabled     = true;
    private boolean nebulaEnabled   = false;

    // Graph rendering settings
    private int maxComponentsToRender = 0;

    private static AppConfig instance;

    private AppConfig() {
        loadConfiguration();
    }

    public static synchronized AppConfig getInstance() {
        if (instance == null) {
            instance = new AppConfig();
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = null;
    }

    private void loadConfiguration() {
        Properties props = new Properties();

        String configPath = System.getProperty("config.path");
        if (configPath != null && !configPath.isEmpty()) {
            try (InputStream is = Files.newInputStream(Paths.get(configPath))) {
                props.load(is);
                System.out.println("Loaded configuration from: " + configPath);
            } catch (IOException e) {
                System.err.println("Warning: Failed to load config from " + configPath + ": " + e.getMessage());
            }
        }

        if (props.isEmpty()) {
            try (InputStream is = Files.newInputStream(Paths.get(CONFIG_FILE))) {
                props.load(is);
                System.out.println("Loaded configuration from: " + Paths.get(CONFIG_FILE).toAbsolutePath());
            } catch (IOException e) {
                // ignore, try classpath next
            }
        }

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

        parseProperties(props);
    }

    private void parseProperties(Properties props) {
        // Package filters
        String packages = props.getProperty("package.filters", "");
        if (!packages.isEmpty()) {
            for (String part : packages.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) packageFilters.add(trimmed);
            }
        }

        // Excluded packages
        String excluded = props.getProperty("package.excludes", "");
        if (!excluded.isEmpty()) {
            for (String part : excluded.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) excludedPackages.add(trimmed);
            }
        }

        strictMode = Boolean.parseBoolean(props.getProperty("package.strict.mode", "false"));

        // Project path: prefer project.path, fall back to sample.code.path for compatibility
        String path = props.getProperty("project.path", "").trim();
        if (path.isEmpty()) {
            path = props.getProperty("sample.code.path", "").trim();
        }
        projectPath = path;

        // Output settings
        outputDir       = props.getProperty("output.dir", ".").trim();
        markdownEnabled = Boolean.parseBoolean(props.getProperty("output.markdown.enabled", "true"));
        htmlEnabled     = Boolean.parseBoolean(props.getProperty("output.html.enabled",     "true"));
        nebulaEnabled   = Boolean.parseBoolean(props.getProperty("output.nebula.enabled",   "false"));

        // Graph rendering
        try {
            maxComponentsToRender = Integer.parseInt(
                    props.getProperty("graph.max.components.to.render", "0"));
        } catch (NumberFormatException e) {
            System.err.println("Warning: Invalid value for graph.max.components.to.render, using default (0)");
            maxComponentsToRender = 0;
        }
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public List<String> getPackageFilters()  { return packageFilters; }
    public boolean hasPackageFilters()        { return !packageFilters.isEmpty(); }

    public List<String> getExcludedPackages() { return excludedPackages; }
    public boolean hasExcludedPackages()      { return !excludedPackages.isEmpty(); }

    public boolean isStrictMode()             { return strictMode; }

    public String  getProjectPath()           { return projectPath; }

    public String  getOutputDir()             { return outputDir; }
    public boolean isMarkdownEnabled()        { return markdownEnabled; }
    public boolean isHtmlEnabled()            { return htmlEnabled; }
    public boolean isNebulaEnabled()          { return nebulaEnabled; }

    public int getMaxComponentsToRender()     { return maxComponentsToRender; }
}
