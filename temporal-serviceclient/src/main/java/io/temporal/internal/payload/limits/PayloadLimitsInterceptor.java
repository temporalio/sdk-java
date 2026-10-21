package io.temporal.internal.payload.limits;

import com.google.protobuf.Message;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Checks the payload and memo fields of every outbound request against the server's size limits.
 *
 * <p>Fields over a warning threshold are logged and the request is sent. When the call carries
 * {@link PayloadErrorLimits} and a field exceeds them, the request is not sent: the call fails with
 * {@code INVALID_ARGUMENT}, caused by a {@link PayloadLimitViolationException}.
 *
 * <p>Must run after any interceptor that can change the request, so that it checks what is actually
 * sent.
 */
public final class PayloadLimitsInterceptor implements ClientInterceptor {
  private final long payloadsWarnSize;
  private final long memoWarnSize;

  public PayloadLimitsInterceptor(long payloadsWarnSize, long memoWarnSize) {
    this.payloadsWarnSize = payloadsWarnSize;
    this.memoWarnSize = memoWarnSize;
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
    // Every payload-bearing Temporal RPC is unary; deferring a streaming call would change its flow
    // control.
    if (method.getType() != MethodDescriptor.MethodType.UNARY) {
      return next.newCall(method, callOptions);
    }
    PayloadErrorLimits errorLimits = callOptions.getOption(PayloadErrorLimits.CALL_OPTIONS_KEY);
    PayloadLimits limits =
        new PayloadLimits(
            payloadsWarnSize, errorLimits.getBlob(), memoWarnSize, errorLimits.getMemo());
    if (limits.warn(LimitClass.BLOB) == 0
        && limits.error(LimitClass.BLOB) == 0
        && limits.warn(LimitClass.MEMO) == 0
        && limits.error(LimitClass.MEMO) == 0) {
      return next.newCall(method, callOptions);
    }
    return new ValidatingCall<>(next, method, callOptions, limits);
  }

  /**
   * Holds back creating and starting the delegate until the request is seen. A rejected request
   * then never opens a stream to the server, and the interceptors inside this one, such as the one
   * that counts requests, never see it.
   */
  private static final class ValidatingCall<ReqT, RespT> extends ClientCall<ReqT, RespT> {
    private final Channel next;
    private final MethodDescriptor<ReqT, RespT> method;
    private final CallOptions callOptions;
    private final PayloadLimits limits;

    private ClientCall<ReqT, RespT> delegate;

    private Listener<RespT> listener;
    private Metadata headers;
    private int pendingRequests;
    private Boolean messageCompression;
    private boolean started;
    private boolean rejected;

    ValidatingCall(
        Channel next,
        MethodDescriptor<ReqT, RespT> method,
        CallOptions callOptions,
        PayloadLimits limits) {
      this.next = next;
      this.method = method;
      this.callOptions = callOptions;
      this.limits = limits;
    }

    @Override
    public void start(Listener<RespT> responseListener, Metadata headers) {
      this.listener = responseListener;
      this.headers = headers;
    }

    @Override
    public void request(int numMessages) {
      if (started) {
        delegate.request(numMessages);
      } else {
        pendingRequests += numMessages;
      }
    }

    @Override
    public void sendMessage(ReqT message) {
      if (rejected) {
        return;
      }
      if (message instanceof Message) {
        Optional<PayloadLimitViolation> violation =
            PayloadLimitValidator.validate((Message) message, limits);
        if (violation.isPresent()) {
          rejected = true;
          listener.onClose(
              Status.INVALID_ARGUMENT
                  .withDescription(violation.get().getMessage())
                  .withCause(new PayloadLimitViolationException(violation.get())),
              new Metadata());
          return;
        }
      }
      startDelegate();
      delegate.sendMessage(message);
    }

    @Override
    public void halfClose() {
      if (rejected) {
        return;
      }
      startDelegate();
      delegate.halfClose();
    }

    @Override
    public void cancel(@Nullable String message, @Nullable Throwable cause) {
      if (rejected) {
        return;
      }
      if (started) {
        delegate.cancel(message, cause);
        return;
      }
      // Nothing was sent, so close the listener here rather than create a call only to cancel it.
      rejected = true;
      if (listener != null) {
        listener.onClose(
            Status.CANCELLED.withDescription(message).withCause(cause), new Metadata());
      }
    }

    @Override
    public boolean isReady() {
      return started && delegate.isReady();
    }

    @Override
    public void setMessageCompression(boolean enabled) {
      if (started) {
        delegate.setMessageCompression(enabled);
      } else {
        messageCompression = enabled;
      }
    }

    @Override
    public Attributes getAttributes() {
      return started ? delegate.getAttributes() : Attributes.EMPTY;
    }

    private void startDelegate() {
      if (started) {
        return;
      }
      started = true;
      delegate = next.newCall(method, callOptions);
      delegate.start(listener, headers);
      if (messageCompression != null) {
        delegate.setMessageCompression(messageCompression);
      }
      if (pendingRequests > 0) {
        delegate.request(pendingRequests);
      }
    }
  }
}
