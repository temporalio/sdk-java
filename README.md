# Java framework for Cadence
[Cadence](https://github.com/uber/cadence) is a distributed, scalable, durable, and highly available orchestration engine we developed at Uber Engineering to execute asynchronous long-running business logic in a scalable and resilient way.

`cadence-client` is the framework for authoring workflows and activities in Java.

There is also [Go Cadence Client](https://github.com/uber-go/cadence-client).

## Samples

[Samples for the Java Cadence client](https://github.com/uber-java/cadence-client). 

## Running Cadence Server

Run Cadence Server using Docker Compose

    curl -O https://raw.githubusercontent.com/uber/cadence/master/docker/docker-compose.yml
    docker-compose up
     
If it does not work see instructions for running the Cadence Server at https://github.com/uber/cadence/blob/master/README.md

## Get CLI

    TODO

# Build Configuration

Add *cadence-client* dependency to your *pom.xml*:

    <dependency>
      <groupId>com.uber</groupId>
      <artifactId>cadence-client</artifactId>
      <version>0.1.0</version>
    </dependency>

# Overview

Cadence is a task orchestrator for your application’s tasks. Applications using Cadence can execute a logical flow of tasks,
especially long-running business logic, synchronously or asynchronously.

A Cadence client application consists from two main types of components:
activities and workflows.


## Cadence Terminology

- *Activity* is a business level task that implement your application logic like calling a service or transcoding a media file. 
Usually it is expected that an activity implements a single well defined action. Activity can be both short and long running. 
              It can be implemented as a synchronous method or fully asynchronously involving multiple processes. Activity is executed *at most once*.
              It means that the Cadence service never requests activity execution more than once. If for any reason activity is not completed
              within specified timeout an error is reported to the workflow and it decides how to handle it.
              There is no limit on potential activity duration. 

- *Workflow* is a program that orchestrates activities. It has a full control over which activities and in what order are executed.
             Workflow must not affect external world directly, only through activities. What makes workflow code "a workflow" is that its state
             is preserved by Cadence. So any failure of a worker process that hosts the workflow code does not affect the workflow execution.
             It continues as if these failures do not happen. At the same time activities can fail any moment for any reason.
             But as workflow code is fully fault tolerant it is guaranteed to get notification about activity failure or timeout and
             act accordingly. There is no limit on a potential workflow duration.
- *Query* is a synchronous (from the caller point of view) operation that is used to report a workflow state. Note that query is inherently
             read only and cannot affect a workflow state.
- *Signal* is an external asynchronous request to a workflow. It can be used to deliver any notification or update to a running workflow
             at any point of its existence.
- *Domain* is a namespace like concept. Any entity stored in Cadence is always stored in a specific domain. For example when
a workflow is started it is started in a specific domain. Cadence guarantees a workflow id uniqueness within a domain. Domains
are created and updated though a separate CRUD API or through CLI.
- *Task List* is essentially a queue persisted inside a Cadence service. When a workflow requests an activity execution 
Cadence service creates an *activity task* and puts it into a *Task List*. Then a client side worker that implement the activity
receives the task from the task list and invokes an activity implementation. For this to work task list name that is used to request an
activity execution and configure a worker should match.
- *Workflow ID* is a business level ID of a workflow execution (aka instance). Cadence guarantees uniqueness of an ID within a domain.
An attempt to start a workflow with a duplicated ID results in a *already started* error.
- *Run ID* is a UUID a Cadence service assigns to each workflow run. If allowed by a configured policy it might be possible that 
after workflow is closed or failed it can be executed again with the same *Workflow ID*. Each such reexecution is called a *run*.
*Run ID* is used to uniquely identify a run even if it shares a *Workflow ID* with others.
- *Client Stub* is a client side proxy used to make remote invocations to an entity it represents. For example to start a workflow
a stub object that represents this workflow is created through a special API. Then this stub is used to start, query or signal
the corresponding workflow.

# Activities

Activity is a manifestation of a particular task in the business logic.

## Activity Interface

Activities are defined as methods of a plain Java interface. Each method defines a single activity type. A single
workflow can use more than one activity interface and call more that one activity method from the same interface.
The only requirement is that activity method arguments and return values are serializable to byte array using provided
[DataConverter](src/main/java/com/uber/cadence/converter/DataConverter.java) interface. The default implementation uses
JSON serializer, but an alternative implementation can be easily configured. 

Example of an interface that defines four activities:
```Java
public interface FileProcessingActivities {
 
    void upload(String bucketName, String localName, String targetName);

    String download(String bucketName, String remoteName);

    @ActivityMethod(scheduleToCloseTimeoutSeconds = 2)
    String processFile(String localName);
    
    void deleteLocalFile(String fileName);
}

```
An optional @ActivityMethod annotation can be used to specify activity options like timeouts or task list. Required options
that are not specified through the annotation must be specified at run time.

## Activity Implementation

Activity implementation is just an implementation of an activity interface. A single instance of the activities implementation
is shared across multiple simultaneous activity invocations. So the activity implementation code must be *thread safe*.

The values passed to activities through invocation parameters or returned through a result value are recorded in the execution history. 
The entire execution history is transferred from the Cadence service to workflow workers when a workflow state needs to recover.
A large execution history can thus adversely impact the performance of your workflow. 
Therefore be mindful of the amount of data you transfer via activity invocation parameters or return values. 
Other than that no additional limitations exist on activity implementations.

```java
public class FileProcessingActivitiesImpl implements FileProcessingActivities {

    private final AmazonS3 s3Client;

    private final String localDirectory;

    void upload(String bucketName, String localName, String targetName) {
        File f = new File(localName);
        s3Client.putObject(bucket, remoteName, f);
    }

    String download(String bucketName, String remoteName, String localName) {
        // Implementation omitted for brevity
        return downloadFileFromS3(bucketName, remoteName, localDirectory + localName);
    }

    String processFile(String localName) {
        // Implementation omitted for brevity
        return compressFile(localName);
    }

    void deleteLocalFile(String fileName) {
        File f = new File(localDirectory + fileName);
        f.delete();
    }
}
```
### Accessing Activity Info

Class [Activity](src/main/java/com/uber/cadence/activity/Activity.java) provides static getters to access information about workflow that invoked it.
Note that this information is stored in a thread local variable. So calls to Activity accessors succeed only in the thread that invoked the activity function.
```java
public class FileProcessingActivitiesImpl implements FileProcessingActivities {

     @Override
     public String download(String bucketName, String remoteName, String localName) {
        log.info("domain=" +  Activity.getDomain());
        WorkflowExecution execution = Activity.getWorkflowExecution();
        log.info("workflowId=" + execution.getWorkflowId());
        log.info("runId=" + execution.getRunId());
        ActivityTask activityTask = Activity.getTask();
        log.info("activityId=" + activityTask.getActivityId());
        log.info("activityTimeout=" + activityTask.getStartToCloseTimeoutSeconds());
        return downloadFileFromS3(bucketName, remoteName, localDirectory + localName);
     }
     ...
 }
```

### Asynchronous Activity Completion

Sometimes an activity lifecycle goes beyond a synchronous method invocation. For example a request can be put in a queue
and later a reply comes and picked up by a different worker process. The whole such request-reply interaction can be modeled
as a single Cadence activity. 

To indicate that an activity should not be completed upon its method return annotate it with @DoNotCompleteOnReturn.
Then later when replies come complete it using [ActivityCompletionClient](src/main/java/com/uber/cadence/client/ActivityCompletionClient.java).
To correlate activity invocation with completion use either `TaskToken` or workflow and activity ids.
```java
public class FileProcessingActivitiesImpl implements FileProcessingActivities {

     @DoNotCompleteOnReturn
     public String download(String bucketName, String remoteName, String localName) {
         byte[] taskToken = Activity.getTaskToken(); // Used to correlate reply
         asyncDownloadFileFromS3(taskToken, bucketName, remoteName, localDirectory + localName);
         return "ignored"; // Return value is ignored when annotated with @DoNotCompleteOnReturn
     }
     ...
}
```
When download is complete the download service calls back potentially from a different process:
```java
    public <R> void completeActivity(byte[] taskToken, R result) {
        completionClient.complete(taskToken, result);
    }

    public void failActivity(byte[] taskToken, Exception failure) {
        completionClient.completeExceptionally(taskToken, failure);
    }
```

### Activity Heartbeating

Some activities are potentially long running. To be able to react to their crashes quickly heartbeat mechanism is used.
Use `Activity.heartbeat` function to let Cadence service know that it is still alive. You can piggy back 
a `details` on an activity heartbeat. If an activity times out the last value of `details` is included 
into the ActivityTimeoutException delivered to a workflow. Then workflow can pass the details to 
the next activity invocation. This acts as a periodic checkpointing mechanism of an activity progress.
```java
public class FileProcessingActivitiesImpl implements FileProcessingActivities {

     @Override
     public String download(String bucketName, String remoteName, String localName) {
        InputStream inputStream = openInputStream(file);
        try {
            byte[] bytes = new byte[MAX_BUFFER_SIZE];
            while ((read = inputStream.read(bytes)) != -1) {
                totalRead += read;
                f.write(bytes, 0, read);
                /*
                 * Let service know about the download progress.
                 */
                 Activity.heartbeat(totalRead);
            }
        } finally {
            inputStream.close();
        }
     }
     ...
}
```
# Workflows

Workflow encapsulates orchestration of activities and child workflows. 
It can also answer to synchronous queries and receive external events (aka signals).

## Workflow Interface

A workflow must define an interface class. All its methods must have one of the following annotations:
- @WorkflowMethod indicates an entry point to a workflow. It contains parameters like timeouts and task list. Required
parameters (like executionStartToCloseTimeoutSeconds) that are not specified through the annotation must be provided at runtime.
- @Signal indicates a method that reacts to external signals. Must have a `void` return type.
- @Query indicates a method that reacts to synchronous query requests.
It is possible to have more than one method with the same annotation.
```java
public interface FileProcessingWorkflow {

    @WorkflowMethod(executionStartToCloseTimeoutSeconds = 10, taskList = "file-processing")
    String processFile(Arguments args);

    @QueryMethod(name="history")
    List<String> getHistory();

    @QueryMethod(name="status")
    String getStatus();
        
    @SignalMethod
    void retryNow();    
}
```
## Starting workflow executions

Given a workflow interface executing a workflow requires initializing a `WorkflowClient` instance, creating 
a client side stub to the workflow and then calling a method annotated with @WorkflowMethod.
```java
WorkflowClient workflowClient = WorkflowClient.newClient(cadenceServiceHost, cadenceServicePort, domain);
// Create workflow stub
FileProcessingWorkflow workflow = workflowClient.newWorkflowStub(FileProcessingWorkflow.class);
```
There are two ways to start workflow execution synchronously and asynchronously. Synchronous invocation starts a workflow
and then waits for its completion. If process that started workflow crashes or stops the waiting workflow continues execution.
As workflows are potentially long running and crashes of clients happen it is not very commonly found in production use.
Asynchronous start initiates workflow execution and immediately returns to the caller. This is most common way to start 
workflows in production code.

Synchronous start:
```java             
// Start workflow and the wait for a result.
// Note that if process that waits is killed the workflow will continue execution.
String result = workflow.processFile(workflowArgs);
```
Asynchronous:
```java
// Returns as soon as workflow starts
WorkflowExecution workflowExecution = WorkflowClient.asyncStart(workflow::processFile, workflowArgs);

System.out.println("Started process file workflow with workflowId=\"" + workflowExecution.getWorkflowId()
                    + "\" and runId=\"" + workflowExecution.getRunId() + "\"");
```
If for whatever reason there is a need to wait for a workflow completion after an asynchronous start the simplest way
is to call the blocking version again. If `WorkflowOptions.WorkflowIdReusePolicy` is not `AllowDuplicate` then instead 
of throwing `DuplicateWorkflowException` it reconnects to an existing workflow and waits for its completion.
The following example shows how to do it from a different process than the one that started the workflow. All this process 
needs is a `WorkflowID`.
```java
WorkflowExecution execution = new WorkflowExecution().setWorkflowId(workflowId);
FileProcessingWorkflow workflow = workflowClient.newWorkflowStub(execution);
// Returns result potentially waiting for workflow to complete.
String result = workflow.processFile(workflowArgs);
```
## Implementing Workflows

A workflow implementation implements a workflow interface. Each time a new workflow execution is started 
a new instance of the workflow implementation object is created. Then one of the methods 
(depending on which workflow type has been started) annotated with @WorkflowMethod is invoked. As soon as this method 
returns the workflow execution is closed. While workflow execution is open it can receive calls to signal and query methods. 
No additional calls to workflow methods are allowed. The workflow object is stateful, so query and signal methods 
can communicate with the other parts of the workflow through workflow object fields.

### Calling Activities

`Workflow.newActivityStub` returns a client side stub that implements an activity interface. 
It takes activity type and activity options as arguments. Activity options are needed only if some of the required
 timeouts are not specified through @ActivityMethod annotation.

Calling a method on this interface invokes an activity that implements this method. 
An activity invocation synchronously blocks until the activity completes (or fails or times out). Even if activity 
execution takes a few months the workflow code still see it as a single synchronous invocation.
Isn't it great? I doesn't matter what happens to the processes that host the workflow. The business logic code
just sees a single method call.
```java
public class FileProcessingWorkflowImpl implements FileProcessingWorkflow {

    private final FileProcessingActivities activities;
    
    public FileProcessingWorkflowImpl() {
        this.store = Workflow.newActivityStub(FileProcessingActivities.class);
    }

    @Override
    public void processFile(Arguments args) {
        String localName = null;
        String processedName = null;
        try {
            localName = activities.download(args.getSourceBucketName(), args.getSourceFilename());
            processedName = activities.processFile(localName);
            activities.upload(args.getTargetBucketName(), args.getTargetFilename(), processedName);
        } finally {
            if (localName != null) { // File was downloaded
                activities.deleteLocalFile(localName); 
            }
            if (processedName != null) { // File was processed
                activities.deleteLocalFile(processedName);
            }
        }
    }
    ...
}
```
If different activities need different options (like timeouts or task list)  multiple client side stubs could be created 
with different options.

    public FileProcessingWorkflowImpl() {
        ActivityOptions options1 = new ActivityOptions.Builder()
                 .setTaskList("taskList1")
                 .build();
        this.store1 = Workflow.newActivityStub(FileProcessingActivities.class, options1);
        
        ActivityOptions options2 = new ActivityOptions.Builder()
                 .setTaskList("taskList2")
                 .build();
        this.store2 = Workflow.newActivityStub(FileProcessingActivities.class, options2);
    }

### Calling Activities Asynchronously

Sometimes workflows need to perform certain operations in parallel.
`Workflow.async` static method allows invoking any activity asynchronously. The call returns a `Promise` result immediately.
`Promise` is similar to both Java `Future` and `CompletionStage`. The `Promise` `get` blocks until a result is available. 
Also it exposes `thenApply` and `handle` methods. See `Promise` JavaDoc for technical details on differences with `Future`.

To convert a synchronous call
```java
String localName = activities.download(surceBucket, sourceFile);
```
to asynchronous style, the method reference is passed to the `Workflow.async` followed by activity arguments.
```java
Promise<String> localNamePromise = Workflow.async(activities::download, surceBucket, sourceFile);
```
Then to wait synchronously for result:
```java
String localName = localNamePromise.get();
```
Here is above example rewritten to call download and upload in parallel on multiple files:
```java
public void processFile(Arguments args) {
    List<Promise<String>> localNamePromises = new ArrayList<>();
    List<String> processedNames = null;
    try {
        // Download all files in parallel
        for (String sourceFilename : args.getSourceFilenames()) {
            Promise<String> localName = Workflow.async(activities::download, args.getSourceBucketName(), sourceFilename);
            localNamePromises.add(localName);
        }
        // allOf converts a list of promises to single promise that contains list of each promise value.
        Promise<List<String>> localNamesPromise = Promise.allOf(localNamePromises);

        // All code until the next line wasn't blocking.
        // The promise get is a blocking call
        List<String> localNames = localNamesPromise.get();
        processedNames = activities.processFiles(localNames);

        // Upload all results in parallel.
        List<Promise<Void>> uploadedList = new ArrayList<>();
        for (String processedName : processedNames) {
            Promise<Void> uploaded = Workflow.async(activities::upload, args.getTargetBucketName(), args.getTargetFilename(), processedName);
            uploadedList.add(uploaded);
        }
        // Wait for all uploads to complete.
        Promise<?> allUploaded = Promise.allOf(uploadedList);
        allUploaded.get(); // blocks until all promises are ready.
    } finally {
        for (Promise<Sting> localNamePromise : localNamePromises) {
            // Skip files that haven't completed download
            if (localNamePromise.isCompleted()) {
                activities.deleteLocalFile(localNamePromise.get());
            }
        }
        if (processedNames != null) {
            for (String processedName : processedNames) {
                activities.deleteLocalFile(processedName);
            }
        }
    }
}
```
### Child Workflows
Besides activities a workflow can also orchestrate other workflows. 
 
`Workflow.newChildWorkflowStub` returns a client side stub that implements a child workflow interface. 
 It takes a child workflow type and an optional child workflow options as arguments. Workflow options may be needed to override 
 the timeouts and task list if they differ from the defined in @WorkflowMethod annotation or parent workflow ones.
 
 The first call to the child workflow stub must always be to a method annotated with @WorkflowMethod. Similarly to activities a call
 can be synchronous or asynchronous using `Workflow.async`. The synchronous call blocks until a child workflow completion. The asynchronous
 returns a `Promise` that can be used to wait for the completion. After an async call returns the stub can be used to send signals to the child
 by calling methods annotated with `@SignalMethod`. Querying a child workflow by calling methods annotated with @QueryMethod 
 from within workflow code is not currently supported. If needed queries can be done from activities
 using `WorkflowClient` provided stub. 
 ```java
public interface GreetingChild {
    @WorkflowMethod
    String composeGreeting(String greeting, String name);
}

public static class GreetingWorkflowImpl implements GreetingWorkflow {

    @Override
    public String getGreeting(String name) {
        GreetingChild child = Workflow.newChildWorkflowStub(GreetingChild.class);

        // This is blocking call that returns only after child is completed.
        return child.composeGreeting("Hello", name );
    }
}
```
Running two children in parallel:
```java
public static class GreetingWorkflowImpl implements GreetingWorkflow {

    @Override
    public String getGreeting(String name) {

        // Workflows are stateful. So a new stub must be created for each new child.
        GreetingChild child1 = Workflow.newChildWorkflowStub(GreetingChild.class);
        Promise<String> greeting1 = Workflow.async(child1::composeGreeting, "Hello", name);

        // Both children will run concurrently.
        GreetingChild child2 = Workflow.newChildWorkflowStub(GreetingChild.class);
        Promise<String> greeting2 = Workflow.async(child2::composeGreeting, "Bye", name);

        // Do something else here
        ...
        return "First: " + greeting1.get() + ", second=" + greeting2.get();
    }
}
```
To send signal to a child just call a method annotated with @SignalMethod:
```java
public interface GreetingChild {
    @WorkflowMethod
    String composeGreeting(String greeting, String name);
    
    @SignalMethod
    void updateName(String name);
}

public static class GreetingWorkflowImpl implements GreetingWorkflow {

    @Override
    public String getGreeting(String name) {
        GreetingChild child = Workflow.newChildWorkflowStub(GreetingChild.class);
        Promise<String> greeting = Workflow.async(child::composeGreeting, "Hello", name);
        child.updateName("Cadence");
        return greeting.get();
    }
}
```
Calling methods annotated with @QueryMethod is not allowed from within a workflow code.
### Workflow Implementation Constraints

Cadence uses [event sourcing](https://docs.microsoft.com/en-us/azure/architecture/patterns/event-sourcing) to recover 
the state of a workflow object including its threads and local variable values.
. In essence every time a workflow state has to be restored its code is reexecuted from the beginning. When replaying side 
effects (like activity invocations) are ignored as they are already recorded in the workflow event history.
 Don't get confused, when writing workflow logic the replay is not visible,
so the code should be written as it executes only once. But this design still puts the following constraints on the workflow 
implementation:
- Do not use any mutable global variables as multiple instances of workflows are executed in parallel.
- Do not call any non deterministic functions like non seeded random or UUID.randomUUID() directly form the workflow code. 
Always do it in activities.
- Don’t perform any IO or service calls as they are not usually deterministic. Use activities for that.
- Only use `Workflow.currentTimeMillis()` to get current time inside a workflow.
- Do not use native Java `Thread` or any other multi-threaded classes like `ThreadPoolExecutor`. Use `Async.invoke` 
to execute code asynchronously.
- Don't use any synchronization, locks and other standard Java blocking concurrency related classes besides provided 
by the Workflow class. There is no need in explicit synchronization as even multi-threaded code inside a workflow is 
executed one thread at a time and under a global lock.
  - Call `WorkflowThread.sleep` instead of `Thread.sleep`
  - Use `Promise` and `CompletablePromise` instead of `Future` and `CompletableFuture`.
  - Use `WorkflowQueue` instead of `BlockingQueue`.
- Don't change workflow code when there are open workflows. The ability to do updates through visioning is TBD.
- Don’t access configuration APIs directly from a workflow as changes in the configuration might affect a workflow execution path. 
Pass it as an argument to a workflow function or use an activity to load it. 

Workflow method arguments and return values are serializable to byte array using provided
[DataConverter](src/main/java/com/uber/cadence/converter/DataConverter.java) interface. The default implementation uses
JSON serializer, but any alternative serialization mechanism is pluggable.

The values passed to workflows through invocation parameters or returned through a result value are recorded in the execution history. 
The entire execution history is transferred from the Cadence service to workflow workers with every event that the workflow logic needs to process. 
A large execution history can thus adversely impact the performance of your workflow. 
Therefore be mindful of the amount of data you transfer via activity invocation parameters or return values. 
Other than that no additional limitations exist on activity implementations.
