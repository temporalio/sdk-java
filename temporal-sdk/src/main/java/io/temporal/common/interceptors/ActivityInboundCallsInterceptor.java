package io.temporal.common.interceptors;

import io.temporal.activity.ActivityExecutionContext;
import io.temporal.common.Experimental;

/**
 * Intercepts inbound calls to the activity execution on the worker side.
 *
 * <p>Prefer extending {@link ActivityInboundCallsInterceptorBase} and overriding only the methods
 * you need instead of implementing this interface directly. {@link
 * ActivityInboundCallsInterceptorBase} provides correct default implementations to all the methods
 * of this interface.
 */
@Experimental
public interface ActivityInboundCallsInterceptor {
  final class ActivityInput {
    private final Header header;
    private final Object[] arguments;

    public ActivityInput(Header header, Object[] arguments) {
      this.header = header;
      this.arguments = arguments;
    }

    public Header getHeader() {
      return header;
    }

    public Object[] getArguments() {
      return arguments;
    }
  }

  final class ActivityOutput {
    private final Object result;

    public ActivityOutput(Object result) {
      this.result = result;
    }

    public Object getResult() {
      return result;
    }
  }

  void init(ActivityExecutionContext context);

  /**
   * Intercepts a call to the main activity entry method.
   *
   * @return result of the activity execution.
   */
  ActivityOutput execute(ActivityInput input);
}
