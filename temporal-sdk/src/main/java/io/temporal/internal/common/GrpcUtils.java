package io.temporal.internal.common;

import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;

public class GrpcUtils {
  /**
   * @return true if {@code ex} is a gRPC exception about a channel shutdown
   */
  public static boolean isChannelShutdownException(StatusRuntimeException ex) {
    String description = ex.getStatus().getDescription();
    return (Status.Code.UNAVAILABLE.equals(ex.getStatus().getCode())
        && description != null
        && (description.startsWith("Channel shutdown")
            || description.startsWith("Subchannel shutdown")));
  }

  public static <T> CompletableFuture<T> toCompletableFuture(ListenableFuture<T> listenableFuture) {
    CompletableFuture<T> result = new CompletableFuture<>();
    listenableFuture.addListener(
        () -> {
          try {
            result.complete(listenableFuture.get());
          } catch (ExecutionException e) {
            result.completeExceptionally(e.getCause());
          } catch (Exception e) {
            result.completeExceptionally(e);
          }
        },
        ForkJoinPool.commonPool());
    return result;
  }
}
