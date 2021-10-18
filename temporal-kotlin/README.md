# Temporal Kotlin support module

This module added to classpath provides some support for Kotlin specific language features:

   - Support for Kotlin method references for Temporal client stubs passed into Async
   - Kotlin DSL-like extensions 

## Usage

Add `temporal-kotlin` as a dependency to your `pom.xml`:
```xml
<dependency>
  <groupId>io.temporal</groupId>
  <artifactId>temporal-kotlin</artifactId>
  <version>N.N.N</version>
</dependency>
```

or to build.gradle:
```
compile group: 'io.temporal', name: 'temporal-kotlin', version: 'N.N.N'
```

## Kotlin extensions

This module adds several Kotlin extensions to make Kotlin code that uses Temporal Java SDK a bit
more idiomatic.

### Options classes DSL

Various Options classes from the SDK can be fluently instantiated with a constructor-like DSL.
There's also a `copy` extension method that creates a new copy of an Options instance with some
overridden attributes. Overall, the DSL should make Options classes feel somewhat like Kotlin data
classes.

```kotlin
val sourceRetryOptions = RetryOptions {
    setInitialInterval(Duration.ofMillis(100))
    setMaximumInterval(Duration.ofSeconds(1))
    setBackoffCoefficient(1.5)
    setMaximumAttempts(5)
}

val overriddenRetryOptions = sourceRetryOptions.copy {
  setInitialInterval(Duration.ofMillis(10))
  setMaximumAttempts(10)
  setDoNotRetry("some-error")
}
```

The Options types that nest other Options can use nested DSL for configuraion, e.g.
```kotlin
val activityOptions = ActivityOptions {
    // ActivityOptions DSL
    setTaskQueue("TestQueue")
    setStartToCloseTimeout(Duration.ofMinutes(1))
    setScheduleToCloseTimeout(Duration.ofHours(1))
    setRetryOptions {
        // Nested RetryOptions DSL
        setInitialInterval(Duration.ofMillis(100))
        setMaximumInterval(Duration.ofSeconds(1))
        setBackoffCoefficient(1.5)
    }
}
```

In addition to that, methods that accept a single Options class have an extension that accepts
Options DSL as the last parameter instead.

```kotlin
val workerFactory = WorkerFactory(workflowClient) {
  // WorkerFactoryOptions DSL
  setMaxWorkflowThreadCount(800)
  setWorkflowCacheSize(800)
}

val worker = workerFactory.newWorker("taskQueue") {
  // WorkerOptions DSL
  setDefaultDeadlockDetectionTimeout(5000)
}
```

### Reified type extensions

Java DSL methods that accept `java.lang.Class` and `java.lang.reflect.Type` as parameters have
extensions that accept reified generic parameter instead, e.g.

```kotlin
val workflowResult = workflowStub.getResult<List<Long>>()
```

### Workflow and activity metadata extensions

Several extensions help with resolving workflow names, signal/query names and activity names from
Kotlin class and method references:

```kotlin
val activityName = activityName(ActivityInterface::activityMethod)
val workflowName = workflowName<WorkflowInterface>()
val workflowSignalName = workflowSignalName(WorkflowInterface::signalMethod)
val workflowQueryType = workflowQueryType(WorkflowInterface::queryMethod)
```
