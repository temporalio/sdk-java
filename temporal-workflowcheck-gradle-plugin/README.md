# Temporal WorkflowCheck Gradle Plugin

A Gradle plugin that runs the [`temporal-workflowcheck`](../temporal-workflowcheck) bytecode analyzer to detect non-deterministic calls in Temporal Workflow code.

## Usage

Apply the plugin to a Java project that depends on the Temporal Java SDK:

```groovy
plugins {
    id 'java'
    id 'io.temporal.workflowcheck'
}

dependencies {
    implementation 'io.temporal:temporal-sdk:<version>'
}
```

The plugin automatically:

- Detects the Temporal SDK version from `runtimeClasspath`.
- Downloads the matching `io.temporal:temporal-workflowcheck` analyzer.
- Registers a `workflowCheck` verification task.
- Wires `workflowCheck` into the `check` lifecycle.
- Forks a dedicated JVM to run the analysis, isolated from the Gradle daemon.

## Configuration

All options are configured through the `workflowCheck` extension:

```groovy
workflowCheck {
    // Source sets to analyze. Default: ['main']
    sourceSets = ['main']

    // Fail the build on violations. Default: true
    failOnViolation = true

    // Show valid workflow methods alongside invalid ones. Default: false
    showValid = false

    // Skip the default workflowcheck configuration. Default: false
    noDefaultConfig = false

    // Additional .properties config files merged on top of defaults
    configFiles.from(file('workflowcheck-overrides.properties'))

    // Override the ASM version used by temporal-workflowcheck, for example for newer JDK support
    asmVersion = '9.9.1'

    // Max heap size for the forked JVM. Default: unset (JVM ergonomics)
    maxHeapSize = '1g'
}
```

## Command-line options

```bash
# Show valid methods in the output
./gradlew workflowCheck --show-valid

# Skip the default workflowcheck config
./gradlew workflowCheck --no-default-config
```

## Report

The check report is written to `build/reports/workflowcheck/report.txt`.
