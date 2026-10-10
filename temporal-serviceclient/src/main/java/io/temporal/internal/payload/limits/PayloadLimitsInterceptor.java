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
    ClientCall<ReqT, RespT> call = next.newCall(method, callOptions);
    // Every payload-bearing Temporal RPC is unary; deferring the start of a streaming call would
    // change its flow control.
    if (method.getType() != MethodDescriptor.MethodType.UNARY) {
      return call;
    }
    PayloadErrorLimits errorLimits = callOptions.getOption(PayloadErrorLimits.CALL_OPTIONS_KEY);
    PayloadLimits limits =
        new PayloadLimits(
            payloadsWarnSize,
            errorLimits == null ? 0 : errorLimits.getBlob(),
            memoWarnSize,
            errorLimits == null ? 0 : errorLimits.getMemo());
    if (limits.warn(LimitClass.BLOB) == 0
        && limits.error(LimitClass.BLOB) == 0
        && limits.warn(LimitClass.MEMO) == 0
        && limits.error(LimitClass.MEMO) == 0) {
      return call;
    }
    return new ValidatingCall<>(call, limits);
  }

  /**
   * Holds back starting the delegate until the request is seen, so that a rejected request never
   * opens a stream to the server.
   */
  private static final class ValidatingCall<ReqT, RespT> extends ClientCall<ReqT, RespT> {
    private final ClientCall<ReqT, RespT> delegate;
    private final PayloadLimits limits;

    private Listener<RespT> listener;
    private Metadata headers;
    private int pendingRequests;
    private Boolean messageCompression;
    private boolean started;
    private boolean rejected;

    ValidatingCall(ClientCall<ReqT, RespT> delegate, PayloadLimits limits) {
      this.delegate = delegate;
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
      // Starting first lets the delegate close the listener with the cancellation.
      startDelegate();
      delegate.cancel(message, cause);
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
