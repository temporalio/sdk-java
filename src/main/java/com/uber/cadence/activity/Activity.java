/*
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

package com.uber.cadence.activity;

import com.uber.cadence.client.ActivityCompletionException;
import com.uber.cadence.internal.sync.ActivityInternal;
import com.uber.cadence.internal.sync.WorkflowInternal;
import com.uber.cadence.serviceclient.IWorkflowService;
import com.uber.cadence.workflow.ActivityException;
import com.uber.cadence.workflow.ActivityTimeoutException;
import java.lang.reflect.Type;
import java.util.Optional;

/**
 * An activity is the implementation of a particular task in the business logic.
 *
 * <h2>Activity Interface</h2>
 *
 * <p>Activities are defined as methods of a plain Java interface. Each method defines a single
 * activity type. A single workflow can use more than one activity interface and call more than one
 * activity method from the same interface. The only requirement is that activity method arguments
 * and return values are serializable to a byte array using the provided {@link
 * com.uber.cadence.converter.DataConverter} implementation. The default implementation uses JSON
 * serializer, but an alternative implementation can be easily configured.
 *
 * <p>Example of an interface that defines four activities:
 *
 * <pre><code>
 * public interface FileProcessingActivities {
 *
 *     void upload(String bucketName, String localName, String targetName);
 *
 *     String download(String bucketName, String remoteName);
 *
 *     {@literal @}ActivityMethod(scheduleToCloseTimeoutSeconds = 2)
 *     String processFile(String localName);
 *
 *     void deleteLocalFile(String fileName);
 * }
 *
 * </code></pre>
 *
 * An optional {@literal @}{@link ActivityMethod} annotation can be used to specify activity options
 * like timeouts or a task list. Required options that are not specified through the annotation must
 * be specified at run time.
 *
 * <h2>Activity Implementation</h2>
 *
 * <p>Activity implementation is an implementation of an activity interface. A single instance of
 * the activity's implementation is shared across multiple simultaneous activity invocations.
 * Therefore, the activity implementation code must be <i>thread safe</i>.
 *
 * <p>The values passed to activities through invocation parameters or returned through a result
 * value are recorded in the execution history. The entire execution history is transferred from the
 * Cadence service to workflow workers when a workflow state needs to recover. A large execution
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
 *         log.info("domain=" +  Activity.getDomain());
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
 * process. The whole request-reply interaction can be modeled as a single Cadence activity.
 *
 * <p>To indicate that an activity should not be completed upon its method return, call {@link
 * Activity#doNotCompleteOnReturn()} from the original activity thread. Then later, when replies
 * come, complete the activity using {@link com.uber.cadence.client.ActivityCompletionClient}. To
 * correlate activity invocation with completion use either {@code TaskToken} or workflow and
 * activity IDs.
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
 * mechanism. Use the {@link Activity#heartbeat(Object)} function to let the Cadence service know
 * that the activity is still alive. You can piggyback `details` on an activity heartbeat. If an
 * activity times out, the last value of `details` is included in the ActivityTimeoutException
 * delivered to a workflow. Then the workflow can pass the details to the next activity invocation.
 * This acts as a periodic checkpointing mechanism of an activity's progress.
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
 *                 Activity.heartbeat(totalRead);
 *             }
 *         }finally{
 *             inputStream.close();
 *         }
 *      }
 *      ...
 * }
 * </code></pre>
 *
 * @see com.uber.cadence.worker.Worker
 * @see com.uber.cadence.workflow.Workflow
 * @see com.uber.cadence.client.WorkflowClient
 */
public final class Activity {

  /**
   * If this method is called during an activity execution then activity is not going to complete
   * when its method returns. It is expected to be completed asynchronously using {@link
   * com.uber.cadence.client.ActivityCompletionClient}.
   */
  public static void doNotCompleteOnReturn() {
    ActivityInternal.doNotCompleteOnReturn();
  }

  /**
   * @return task token that is required to report task completion when manual activity completion
   *     is used.
   */
  public static byte[] getTaskToken() {
    return ActivityInternal.getTask().getTaskToken();
  }

  /** @return workfow execution that requested the activity execution */
  public static com.uber.cadence.WorkflowExecution getWorkflowExecution() {
    return ActivityInternal.getTask().getWorkflowExecution();
  }

  /** @return task that caused activity execution */
  public static ActivityTask getTask() {
    return ActivityInternal.getTask();
  }

  /**
   * Use to notify Cadence service that activity execution is alive.
   *
   * @param details In case of activity timeout can be accessed through {@link
   *     ActivityTimeoutException#getDetails(Class)} method.
   * @throws ActivityCompletionException Indicates that activity execution is expected to be
   *     interrupted. The reason for interruption is indicated by a type of subclass of the
   *     exception.
   */
  public static <V> void heartbeat(V details) throws ActivityCompletionException {
    ActivityInternal.recordActivityHeartbeat(details);
  }

  /**
   * Extracts heartbeat details from the last failed attempt. This is used in combination with retry
   * options. An activity could be scheduled with an optional {@link
   * com.uber.cadence.common.RetryOptions} on {@link ActivityOptions}. If an activity failed then
   * the server would attempt to dispatch another activity task to retry according to the retry
   * options. If there was heartbeat details reported by the activity from the failed attempt, the
   * details would be delivered along with the activity task for the retry attempt. The activity
   * could extract the details by {@link #getHeartbeatDetails(Class)}() and resume from the
   * progress.
   *
   * @param detailsClass type of the heartbeat details
   */
  public static <V> Optional<V> getHeartbeatDetails(Class<V> detailsClass) {
    return ActivityInternal.getHeartbeatDetails(detailsClass, detailsClass);
  }

  /**
   * Similar to {@link #getHeartbeatDetails(Class)}. Use when details is of a generic type.
   *
   * @param detailsClass type of the heartbeat details
   * @param detailsType type including generic information of the heartbeat details.
   */
  public static <V> Optional<V> getHeartbeatDetails(Class<V> detailsClass, Type detailsType) {
    return ActivityInternal.getHeartbeatDetails(detailsClass, detailsType);
  }

  /**
   * @return an instance of the Simple Workflow Java client that is the same used by the invoked
   *     activity worker. It can be useful if activity wants to use WorkflowClient for some
   *     operations like sending signal to its parent workflow. @TODO getWorkflowClient method to
   *     hide the service.
   */
  public static IWorkflowService getService() {
    return ActivityInternal.getService();
  }

  public static String getDomain() {
    return ActivityInternal.getDomain();
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
   * {@link ActivityException} from calls to an activity and subclass of {@link
   * com.uber.cadence.workflow.ChildWorkflowException} from calls to a child workflow. The original
   * exception is attached as a cause to these wrapper exceptions. So as exceptions are always
   * wrapped adding checked ones to method signature causes more pain than benefit.
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
