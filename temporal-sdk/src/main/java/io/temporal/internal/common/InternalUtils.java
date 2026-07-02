package io.temporal.internal.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Defaults;
import com.google.common.base.Strings;
import io.nexusrpc.Header;
import io.nexusrpc.handler.HandlerException;
import io.nexusrpc.handler.ServiceImplInstance;
import io.temporal.api.common.v1.Callback;
import io.temporal.api.common.v1.Link;
import io.temporal.api.enums.v1.TaskQueueKind;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.client.OnConflictOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.metadata.POJOActivityMethodMetadata;
import io.temporal.common.metadata.POJOWorkflowMethodMetadata;
import io.temporal.common.metadata.WorkflowMethodType;
import io.temporal.internal.client.NexusStartWorkflowRequest;
import io.temporal.internal.nexus.CurrentNexusOperationContext;
import io.temporal.internal.nexus.InternalNexusOperationContext;
import io.temporal.internal.nexus.OperationTokenUtil;
import java.util.*;
import java.util.stream.Collectors;

/** Utility functions shared by the implementation code. */
public final class InternalUtils {
  public static String TEMPORAL_RESERVED_PREFIX = "__temporal_";

  private static String QUERY_TYPE_STACK_TRACE = "__stack_trace";
  private static String ENHANCED_QUERY_TYPE_STACK_TRACE = "__enhanced_stack_trace";

  public static TaskQueue createStickyTaskQueue(
      String stickyTaskQueueName, String normalTaskQueueName) {
    return TaskQueue.newBuilder()
        .setName(stickyTaskQueueName)
        .setKind(TaskQueueKind.TASK_QUEUE_KIND_STICKY)
        .setNormalName(normalTaskQueueName)
        .build();
  }

  public static TaskQueue createNormalTaskQueue(String taskQueueName) {
    return TaskQueue.newBuilder()
        .setName(taskQueueName)
        .setKind(TaskQueueKind.TASK_QUEUE_KIND_NORMAL)
        .build();
  }

  public static Object getValueOrDefault(Object value, Class<?> valueClass) {
    if (value != null) {
      return value;
    }
    return Defaults.defaultValue(valueClass);
  }

  /**
   * Creates a new stub that is bound to the same workflow as the given stub, but with the Nexus
   * callback URL and headers set.
   *
   * @param stub the stub to create a new stub from
   * @param request the request containing the Nexus callback URL and headers
   * @return a new stub bound to the same workflow as the given stub, but with the Nexus callback
   *     URL and headers set
   */
  @SuppressWarnings("deprecation") // Check the OPERATION_ID header for backwards compatibility
  public static NexusWorkflowStarter createNexusBoundStub(
      WorkflowStub stub, NexusStartWorkflowRequest request) {
    if (!stub.getOptions().isPresent()) {
      throw new IllegalArgumentException("Options are expected to be set on the stub");
    }
    WorkflowOptions options = stub.getOptions().get();
    if (options.getWorkflowId() == null) {
      throw new IllegalArgumentException(
          "WorkflowId is expected to be set on WorkflowOptions when used with Nexus");
    }
    InternalNexusOperationContext nexusContext = CurrentNexusOperationContext.get();
    // Generate the operation token for the new workflow.
    String operationToken;
    try {
      operationToken =
          OperationTokenUtil.generateWorkflowRunOperationToken(
              options.getWorkflowId(), nexusContext.getNamespace());
    } catch (JsonProcessingException e) {
      // Not expected as the link is constructed by the SDK.
      throw new HandlerException(
          HandlerException.ErrorType.BAD_REQUEST, "failed to generate workflow operation token", e);
    }
    List<Link> links =
        request.getLinks() == null
            ? null
            : request.getLinks().stream()
                .map(
                    (link) ->
                        LinkConverter.nexusLinkToLink(
                            io.temporal.api.nexus.v1.Link.newBuilder()
                                .setType(link.getType())
                                .setUrl(link.getUri().toString())
                                .build()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    WorkflowOptions.Builder nexusWorkflowOptions =
        WorkflowOptions.newBuilder(options).setRequestId(request.getRequestId()).setLinks(links);

    // If a callback URL is provided, pass it as a completion callback.
    if (!Strings.isNullOrEmpty(request.getCallbackUrl())) {
      Callback cb =
          buildNexusCallback(
              request.getCallbackUrl(), request.getCallbackHeaders(), operationToken, links);
      nexusWorkflowOptions.setCompletionCallbacks(Collections.singletonList(cb));
    }

    if (options.getTaskQueue() == null) {
      nexusWorkflowOptions.setTaskQueue(request.getTaskQueue());
    }
    nexusWorkflowOptions.setOnConflictOptions(
        OnConflictOptions.newBuilder()
            .setAttachRequestId(true)
            .setAttachLinks(true)
            .setAttachCompletionCallbacks(true)
            .build());

    return new NexusWorkflowStarter(stub.newInstance(nexusWorkflowOptions.build()), operationToken);
  }

  /**
   * Builds a {@link Callback} for use as a Nexus completion callback. Injects both the legacy
   * {@code Nexus-Operation-Id} and the newer {@code Nexus-Operation-Token} headers
   * (case-insensitive lookup) when not already present so the server can fabricate
   * operation-started events if the completion is received before the response to a StartOperation
   * request.
   *
   * <p>Shared by the workflow start path ({@link #createNexusBoundStub}) and the activity start
   * path ({@code RootActivityClientInvoker.startActivity}). The dual {@code OPERATION_ID} + {@code
   * OPERATION_TOKEN} headers must be injected before the start RPC is issued.
   */
  @SuppressWarnings("deprecation") // Check the OPERATION_ID header for backwards compatibility
  public static Callback buildNexusCallback(
      String callbackUrl,
      Map<String, String> callbackHeaders,
      String operationToken,
      List<Link> protoLinks) {
    Map<String, String> headers =
        callbackHeaders.entrySet().stream()
            .collect(
                Collectors.toMap(
                    (k) -> k.getKey().toLowerCase(),
                    Map.Entry::getValue,
                    (a, b) -> a,
                    () -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER)));
    if (!headers.containsKey(Header.OPERATION_ID)) {
      headers.put(Header.OPERATION_ID.toLowerCase(), operationToken);
    }
    if (!headers.containsKey(Header.OPERATION_TOKEN)) {
      headers.put(Header.OPERATION_TOKEN.toLowerCase(), operationToken);
    }
    Callback.Builder cbBuilder =
        Callback.newBuilder()
            .setNexus(
                Callback.Nexus.newBuilder().setUrl(callbackUrl).putAllHeader(headers).build());
    if (protoLinks != null) {
      cbBuilder.addAllLinks(protoLinks);
    }
    return cbBuilder.build();
  }

  /** Check the method name for reserved prefixes or names. */
  public static void checkMethodName(POJOWorkflowMethodMetadata methodMetadata) {
    if (methodMetadata.getName().startsWith(TEMPORAL_RESERVED_PREFIX)) {
      throw new IllegalArgumentException(
          methodMetadata.getType().toString().toLowerCase()
              + " name \""
              + methodMetadata.getName()
              + "\" must not start with \""
              + TEMPORAL_RESERVED_PREFIX
              + "\"");
    }
    if (methodMetadata.getType().equals(WorkflowMethodType.QUERY)
        && (methodMetadata.getName().equals(QUERY_TYPE_STACK_TRACE)
            || methodMetadata.getName().equals(ENHANCED_QUERY_TYPE_STACK_TRACE))) {
      throw new IllegalArgumentException(
          "Query method name \"" + methodMetadata.getName() + "\" is reserved for internal use");
    }
  }

  public static void checkMethodName(POJOActivityMethodMetadata methodMetadata) {
    if (methodMetadata.getActivityTypeName().startsWith(TEMPORAL_RESERVED_PREFIX)) {
      throw new IllegalArgumentException(
          "Activity name \""
              + methodMetadata.getActivityTypeName()
              + "\" must not start with \""
              + TEMPORAL_RESERVED_PREFIX
              + "\"");
    }
  }

  /** Prohibit instantiation */
  private InternalUtils() {}

  public static void checkMethodName(ServiceImplInstance instance) {
    if (instance.getDefinition().getName().startsWith(TEMPORAL_RESERVED_PREFIX)) {
      throw new IllegalArgumentException(
          "Service name \""
              + instance.getDefinition().getName()
              + "\" must not start with \""
              + TEMPORAL_RESERVED_PREFIX
              + "\"");
    }
    for (String operationName : instance.getDefinition().getOperations().keySet()) {
      if (operationName.startsWith(TEMPORAL_RESERVED_PREFIX)) {
        throw new IllegalArgumentException(
            "Operation name \""
                + operationName
                + "\" must not start with \""
                + TEMPORAL_RESERVED_PREFIX
                + "\"");
      }
    }
  }
}
