/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.activity;

import io.temporal.common.converter.DataConverter;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.internal.sync.ActivityExecutionContext;
import io.temporal.internal.sync.ActivityInternal;
import io.temporal.internal.sync.WorkflowInternal;

/**
 * An activity is the implementation of a particular task in the business logic.
 *
 * <h2>Activity Interface</h2>
 *
 * <p>Activities are defined as methods of a plain Java interface annotated with {@literal @}{@link
 * ActivityInterface}. Each method defines a single activity type. A single workflow can use more
 * than one activity interface and call more than one activity method from the same interface. The
 * only requirement is that activity method arguments and return values are serializable to a byte
 * array using the provided {@link io.temporal.common.converter.DataConverter} implementation. The
 * default implementation uses JSON serializer, but an alternative implementation can be configured
 * through {@link io.temporal.client.WorkflowClientOptions.Builder#setDataConverter(DataConverter)}.
 *
 * <p>Example of an interface that defines four activities:
 *
 * <pre><code>
 * {@literal @}ActivityInterface
 * public interface FileProcessingActivities {
 *
 *     void upload(String bucketName, String localName, String targetName);
 *
 *     String download(String bucketName, String remoteName);
 *
 *     {@literal @}ActivityMethod(name = "transcode")
 *     String processFile(String localName);
 *
 *     void deleteLocalFile(String fileName);
 * }
 *
 * </code></pre>
 *
 * An optional {@literal @}{@link ActivityMethod} annotation can be used to specify activity name.
 * By default the method name is used as an activity name.
 *
 * <h2>Activity Implementation</h2>
 *
 * <p>Activity implementation is an implementation of an activity interface. A single instance of
 * the activity's implementation is shared across multiple simultaneous activity invocations.
 * Therefore, the activity implementation code must be <i>thread safe</i>.
 *
 * <p>The values passed to activities through invocation parameters or returned through a result
 * value are recorded in the execution history. The entire execution history is transferred from the
 * Temporal service to workflow workers when a workflow state needs to recover. A large execution
 * history can thus adversely impact the performance of your workflow. Therefore, be mindful of the
 * amount of data you transfer via activity invocation parameters or return values. Other than that,
 * no additional limitations exist on activity implementations.
 *
 * <pre><code>
 * public class FileProcessingActivitiesImpl implements FileProcessingActivities {
 *
 *     private final AmazonS3 s3Client;
 *
 *     private final String localDirectory;
 *
 *     void upload(String bucketName, String localName, String targetName) {
 *         File f = new File(localName);
 *         s3Client.putObject(bucket, remoteName, f);
 *     }
 *
 *     String download(String bucketName, String remoteName, String localName) {
 *         // Implementation omitted for brevity.
 *         return downloadFileFromS3(bucketName, remoteName, localDirectory + localName);
 *     }
 *
 *     String processFile(String localName) {
 *         // Implementation omitted for brevity.
 *         return compressFile(localName);
 *     }
 *
 *     void deleteLocalFile(String fileName) {
 *         File f = new File(localDirectory + fileName);
 *         f.delete();
 *     }
 * }
 * </code></pre>
 *
 * <h3>Accessing Activity Info</h3>
 *
 * <p>The {@link Activity} class provides static getters to access information about the workflow
 * that invoked it. Note that this information is stored in a thread-local variable. Therefore,
 * calls to Activity accessors succeed only in the thread that invoked the activity function.
 *
 * <pre><code>
 * public class FileProcessingActivitiesImpl implements FileProcessingActivities {
 *
 *      {@literal @}Override
 *      public String download(String bucketName, String remoteName, String localName) {
 *         log.info("namespace=" +  Activity.getNamespace());
 *         WorkflowExecution execution = Activity.getWorkflowExecution();
 *         log.info("workflowId=" + execution.getWorkflowId());
 *         log.info("runId=" + execution.getRunId());
 *         ActivityTask activityTask = Activity.getTask();
 *         log.info("activityId=" + activityTask.getActivityId());
 *         log.info("activityTimeout=" + activityTask.getStartToCloseTimeoutSeconds());
 *         return downloadFileFromS3(bucketName, remoteName, localDirectory + localName);
 *      }
 *      ...
 *  }
 * </code></pre>
 *
 * <h3>Asynchronous Activity Completion</h3>
 *
 * <p>Sometimes an activity lifecycle goes beyond a synchronous method invocation. For example, a
 * request can be put in a queue and later a reply comes and is picked up by a different worker
 * process. The whole request-reply interaction can be modeled as a single Temporal activity.
 *
 * <p>To indicate that an activity should not be completed upon its method return, call {@link
 * ActivityExecutionContext#doNotCompleteOnReturn()} from the original activity thread. Then later,
 * when replies come, complete the activity using {@link
 * io.temporal.client.ActivityCompletionClient}. To correlate activity invocation with completion
 * use either {@code TaskToken} or workflow and activity IDs.
 *
 * <pre><code>
 * public class FileProcessingActivitiesImpl implements FileProcessingActivities {
 *
 *      public String download(String bucketName, String remoteName, String localName) {
 *          byte[] taskToken = Activity.getTaskToken(); // Used to correlate reply
 *          asyncDownloadFileFromS3(taskToken, bucketName, remoteName, localDirectory + localName);
 *          Activity.doNotCompleteOnReturn();
 *          return "ignored"; // Return value is ignored when doNotCompleteOnReturn was called.
 *      }
 *      ...
 * }
 * </code></pre>
 *
 * When the download is complete, the download service potentially calls back from a different
 * process:
 *
 * <pre><code>
 *     public <R> void completeActivity(byte[] taskToken, R result) {
 *         completionClient.complete(taskToken, result);
 *     }
 *
 *     public void failActivity(byte[] taskToken, Exception failure) {
 *         completionClient.completeExceptionally(taskToken, failure);
 *     }
 * </code></pre>
 *
 * <h3>Activity Heartbeating</h3>
 *
 * <p>Some activities are long running. To react to their crashes quickly, use a heartbeat
 * mechanism. Use the {@link ActivityExecutionContext#heartbeat(Object)} function to let the
 * Temporal service know that the activity is still alive. You can piggyback `details` on an
 * activity heartbeat. If an activity times out, the last value of `details` is included in the
 * ActivityTimeoutException delivered to a workflow. Then the workflow can pass the details to the
 * next activity invocation. This acts as a periodic checkpointing mechanism of an activity's
 * progress.
 *
 * <pre><code>
 * public class FileProcessingActivitiesImpl implements FileProcessingActivities {
 *
 *      {@literal @}Override
 *      public String download(String bucketName, String remoteName, String localName) {
 *         InputStream inputStream = openInputStream(file);
 *         try {
 *             byte[] bytes = new byte[MAX_BUFFER_SIZE];
 *             while ((read = inputStream.read(bytes)) != -1) {
 *                 totalRead += read;
 *                 f.write(bytes, 0, read);
 *                 // Let the service know about the download progress.
 *                 Activity.getExecutionContext().heartbeat(totalRead);
 *             }
 *         }finally{
 *             inputStream.close();
 *         }
 *      }
 *      ...
 * }
 * </code></pre>
 *
 * @see io.temporal.worker.Worker
 * @see io.temporal.workflow.Workflow
 * @see io.temporal.client.WorkflowClient
 */
public final class Activity {

  /**
   * Activity execution context. It can be used to get information about activity invocation as well
   * for heartbeating.
   *
   * <p>Note: This static method relies on a thread local and works only in the original activity
   * thread.
   */
  public static ActivityExecutionContext getExecutionContext() {
    return ActivityInternal.getExecutionContext();
  }

  /**
   * If there is a need to return a checked exception from an activity do not add the exception to a
   * method signature but rethrow it using this method. The library code will unwrap it
   * automatically when propagating exception to the caller. There is no need to wrap unchecked
   * exceptions, but it is safe to call this method on them.
   *
   * <p>The reason for such design is that returning originally thrown exception from a remote call
   * (which child workflow and activity invocations are ) would not allow adding context information
   * about a failure, like activity and child workflow id. So stubs always throw a subclass of
   * {@link ActivityFailure} from calls to an activity and subclass of {@link ChildWorkflowFailure}
   * from calls to a child workflow. The original exception is attached as a cause to these wrapper
   * exceptions. So as exceptions are always wrapped adding checked ones to method signature causes
   * more pain than benefit.
   *
   * <p>Throws original exception if e is {@link RuntimeException} or {@link Error}. Never returns.
   * But return type is not empty to be able to use it as:
   *
   * <pre>
   * try {
   *     return someCall();
   * } catch (Exception e) {
   *     throw CheckedExceptionWrapper.throwWrapped(e);
   * }
   * </pre>
   *
   * If throwWrapped returned void it wouldn't be possible to write <code>
   * throw CheckedExceptionWrapper.throwWrapped</code> and compiler would complain about missing
   * return.
   *
   * @return never returns as always throws.
   */
  public static RuntimeException wrap(Exception e) {
    return WorkflowInternal.wrap(e);
  }

  /** Prohibit instantiation */
  private Activity() {}
}
