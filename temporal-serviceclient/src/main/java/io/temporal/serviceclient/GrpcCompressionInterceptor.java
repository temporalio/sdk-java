package io.temporal.serviceclient;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class GrpcCompressionInterceptor implements ClientInterceptor {
  private final GrpcCompression compression;
  private final Set<String> compressionUnsupportedMethods = ConcurrentHashMap.newKeySet();

  GrpcCompressionInterceptor(GrpcCompression compression) {
    this.compression = compression;
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
    String compressorName = compression.getCompressorName();
    if (compressorName == null
        || compressionUnsupportedMethods.contains(method.getFullMethodName())) {
      return next.newCall(method, callOptions.withCompression(null));
    }
    if (!MethodDescriptor.MethodType.UNARY.equals(method.getType())) {
      return next.newCall(method, callOptions.withCompression(compressorName));
    }
    return new GrpcCompressionRetryingClientCall<>(
        next, method, callOptions, compression, compressorName, compressionUnsupportedMethods);
  }

  static boolean isCompressionUnsupported(Status status, GrpcCompression compression) {
    String compressorName = compression.getCompressorName();
    if (!Status.Code.UNIMPLEMENTED.equals(status.getCode()) || compressorName == null) {
      return false;
    }
    String description = status.getDescription();
    if (description == null) {
      return false;
    }
    String lowerDescription = description.toLowerCase(Locale.ROOT);
    return lowerDescription.contains(compressorName.toLowerCase(Locale.ROOT))
        && (lowerDescription.contains("decompress")
            || lowerDescription.contains("grpc-encoding")
            || lowerDescription.contains("compressor"));
  }

  private static final class GrpcCompressionRetryingClientCall<ReqT, RespT>
      extends ClientCall<ReqT, RespT> {
    private final Channel next;
    private final MethodDescriptor<ReqT, RespT> method;
    private final CallOptions callOptions;
    private final GrpcCompression compression;
    private final Set<String> compressionUnsupportedMethods;
    private final List<ReqT> messages = new ArrayList<>(1);

    private ClientCall<ReqT, RespT> delegate;
    private Listener<RespT> responseListener;
    private Metadata headers;
    private int requestedMessages;
    private boolean halfClosed;
    private boolean cancelled;
    private Boolean messageCompressionEnabled;

    private GrpcCompressionRetryingClientCall(
        Channel next,
        MethodDescriptor<ReqT, RespT> method,
        CallOptions callOptions,
        GrpcCompression compression,
        String compressorName,
        Set<String> compressionUnsupportedMethods) {
      this.next = next;
      this.method = method;
      this.callOptions = callOptions;
      this.compression = compression;
      this.compressionUnsupportedMethods = compressionUnsupportedMethods;
      this.delegate = next.newCall(method, callOptions.withCompression(compressorName));
    }

    @Override
    public void start(Listener<RespT> responseListener, Metadata headers) {
      this.responseListener = responseListener;
      this.headers = headers;
      delegate.start(new FirstAttemptListener(responseListener), headers);
    }

    @Override
    public void request(int numMessages) {
      requestedMessages += numMessages;
      delegate.request(numMessages);
    }

    @Override
    public void cancel(String message, Throwable cause) {
      cancelled = true;
      delegate.cancel(message, cause);
    }

    @Override
    public void halfClose() {
      halfClosed = true;
      delegate.halfClose();
    }

    @Override
    public void sendMessage(ReqT message) {
      messages.add(message);
      delegate.sendMessage(message);
    }

    @Override
    public boolean isReady() {
      return delegate.isReady();
    }

    @Override
    public void setMessageCompression(boolean enabled) {
      messageCompressionEnabled = enabled;
      delegate.setMessageCompression(enabled);
    }

    private boolean retryWithoutCompression(Status status) {
      if (cancelled || !isCompressionUnsupported(status, compression)) {
        return false;
      }
      compressionUnsupportedMethods.add(method.getFullMethodName());
      Metadata retryHeaders = new Metadata();
      retryHeaders.merge(headers);
      ClientCall<ReqT, RespT> retryCall = next.newCall(method, callOptions.withCompression(null));
      delegate = retryCall;
      retryCall.start(responseListener, retryHeaders);
      if (messageCompressionEnabled != null) {
        retryCall.setMessageCompression(messageCompressionEnabled);
      }
      if (requestedMessages > 0) {
        retryCall.request(requestedMessages);
      }
      for (ReqT message : messages) {
        retryCall.sendMessage(message);
      }
      if (halfClosed) {
        retryCall.halfClose();
      }
      return true;
    }

    private final class FirstAttemptListener
        extends ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT> {
      private Metadata firstHeaders;
      private final List<RespT> firstMessages = new ArrayList<>(1);

      private FirstAttemptListener(Listener<RespT> responseListener) {
        super(responseListener);
      }

      @Override
      public void onHeaders(Metadata headers) {
        firstHeaders = headers;
      }

      @Override
      public void onMessage(RespT message) {
        firstMessages.add(message);
      }

      @Override
      public void onClose(Status status, Metadata trailers) {
        if (firstMessages.isEmpty() && retryWithoutCompression(status)) {
          return;
        }
        if (firstHeaders != null) {
          super.onHeaders(firstHeaders);
        }
        for (RespT message : firstMessages) {
          super.onMessage(message);
        }
        super.onClose(status, trailers);
      }
    }
  }
}
