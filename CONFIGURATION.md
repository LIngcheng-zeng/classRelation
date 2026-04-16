# Configuration Guide

## Overview

ClassRelation supports configuration through a properties file, allowing you to customize package filtering, graph rendering, and sample code paths without command-line arguments.

## Configuration File Location

The application searches for `classrelation.properties` in the following order:

1. **System property**: `-Dconfig.path=/path/to/classrelation.properties`
2. **Current directory**: `./classrelation.properties`
3. **Classpath**: `src/main/resources/classrelation.properties` (bundled with JAR)

## Configuration Options

### Package Filter Settings

Control which packages are included in the analysis.

```properties
# Comma-separated list of package patterns
package.filters=org.example.model,org.example.service

# Strict mode: false = OR logic (at least one side matches), true = AND logic (both sides match)
package.strict.mode=false
```

**Package Pattern Support:**
- **Exact match**: `com.example.model` - matches only this exact package
- **One-level wildcard**: `com.example.*` - matches `com.example.model`, `com.example.dto`, etc.
- **Sub-package match**: `com.example.**` - matches all sub-packages recursively

### Graph Rendering Settings

Control how connected components are rendered in the HTML visualization.

```properties
# Maximum number of connected components to render (0 = render all)
# Components are selected by edge count (most edges first)
graph.max.components.to.render=5
```

**Use Cases:**
- Set to `0` (default) to render all components
- Set to a positive number (e.g., `5`) to show only the top N most connected components
- Useful for large projects with many small isolated components

### Sample Code Repository Path

Set a default path for the sample code repository.

```properties
# Default path to analyze when no project root is provided on command line
sample.code.path=/home/linux_zeng/projects/classRelationTestCode/src/main/java/org/example
```

**Behavior:**
- If specified, you can run the tool without providing a project path
- Command-line argument overrides this setting
- Can be absolute or relative path

## Usage Examples

### Example 1: Using Configuration File Only

Create `classrelation.properties`:
```properties
package.filters=org.example.model,org.example.service
graph.max.components.to.render=3
sample.code.path=/path/to/sample/code
```

Run without arguments:
```bash
java -jar classRelation.jar
```

### Example 2: Override Config with Command-Line Arguments

Configuration file sets defaults, but CLI arguments take precedence:
```bash
# Uses config's sample.code.path but overrides package filter
java -jar classRelation.jar --package com.myapp.api

# Overrides everything
java -jar classRelation.jar /path/to/project --package com.test --strict
```

### Example 3: Custom Config File Location

```bash
java -Dconfig.path=/etc/classrelation/config.properties -jar classRelation.jar
```

### Example 4: Analyze Specific Packages with Graph Filtering

```properties
# classrelation.properties
package.filters=com.myapp.service,com.myapp.controller
package.strict.mode=true
graph.max.components.to.render=10
```

This will:
- Only include relations where BOTH source and target are in the specified packages
- Render only the top 10 most connected components in the HTML graph

## Configuration Priority

Settings are applied in this order (highest priority first):

1. **Command-line arguments** (override everything)
2. **Configuration file** (provides defaults)
3. **Built-in defaults** (used if not configured)

## Complete Example Configuration

```properties
# ============================================================================
# Package Filter Settings
# ============================================================================
package.filters=org.example.model,org.example.service,org.example.controller
package.strict.mode=false

# ============================================================================
# Graph Rendering Settings
# ============================================================================
graph.max.components.to.render=5

# ============================================================================
# Sample Code Repository Path
# ============================================================================
sample.code.path=/home/user/projects/my-sample-project/src/main/java
```

## Troubleshooting

### Configuration Not Loading

Check the console output for messages like:
```
Loaded configuration from: /path/to/classrelation.properties
```

If you see:
```
No configuration file found, using defaults
```

Verify:
1. File name is exactly `classrelation.properties`
2. File is in one of the search locations
3. File has correct read permissions

### Invalid Configuration Values

Invalid numeric values fall back to defaults with a warning:
```
Warning: Invalid value for graph.max.components.to.render, using default (0)
```

## Best Practices

1. **Use configuration file for team settings**: Commit `classrelation.properties` to version control for consistent team analysis
2. **Use CLI for ad-hoc queries**: Override config temporarily with command-line arguments
3. **Start with small component count**: For large projects, set `graph.max.components.to.render=5` initially, then increase as needed
4. **Combine filters strategically**: Use `package.filters` to focus on relevant packages and `graph.max.components.to.render` to reduce visual clutter
