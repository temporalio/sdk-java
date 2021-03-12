package io.temporal.common.interceptors;

import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.util.HashMap;
import java.util.Map;

//This way is bad also because the header is passed when the stub is created, not when the execution is triggered.
//It can be problematic, especially if our context is thread local and stubs are created and executed in different threads
public class SampleOpenTracingLikeInterceptor extends WorkflowClientInterceptorBase {
  @Override
  public WorkflowStub newUntypedWorkflowStub(
      String workflowType, WorkflowOptions options, WorkflowStub next) {
    Map<String, Object> originalHeaders = options != null ? options.getHeaders() : null;
    Map<String, Object> newHeaders;

    if (originalHeaders == null) {
      newHeaders = new HashMap<>();
    } else {
      // we want to copy it, because right now WorkflowOptions exposes the collection itself, so if
      // we modify it -
      // we will modify a collection inside WorkflowOptions that supposes to be immutable (because
      // it has a builder)
      newHeaders = new HashMap<>(originalHeaders);
    }
    newHeaders.put("opentracing", new Object());
    WorkflowOptions modifiedOptions =
        WorkflowOptions.newBuilder(options).setHeaders(newHeaders).build();
    // it's either
    // 1. setOption on stub,
    // or
    // 2. We hack supposedly immutable WorkflowOptions instance and directly modify the header collection.
    next.setOptions(modifiedOptions);
    return next;
  }
}
