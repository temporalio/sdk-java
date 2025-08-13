package io.temporal.common.interceptors;

import io.nexusrpc.OperationException;
import io.nexusrpc.OperationStillRunningException;
import io.nexusrpc.client.transport.Transport;
import java.util.concurrent.CompletableFuture;

/** Root interceptor that delegates calls to the provided {@link Transport}. */
public final class NexusServiceClientInterceptorRoot extends NexusServiceClientInterceptorBase {

  public NexusServiceClientInterceptorRoot(Transport transport) {
    super(
        new NexusServiceClientInterceptor() {
          @Override
          public StartOperationOutput startOperation(StartOperationInput input)
              throws OperationException {
            return new StartOperationOutput(
                transport.startOperation(
                    input.getOperationName(),
                    input.getServiceName(),
                    input.getInput(),
                    input.getOptions()));
          }

          @Override
          public FetchOperationResultOutput fetchOperationResult(FetchOperationResultInput input)
              throws OperationException, OperationStillRunningException {
            return new FetchOperationResultOutput(
                transport.fetchOperationResult(
                    input.getOperationName(),
                    input.getServiceName(),
                    input.getOperationToken(),
                    input.getOptions()));
          }

          @Override
          public FetchOperationInfoOutput fetchOperationInfo(FetchOperationInfoInput input) {
            return new FetchOperationInfoOutput(
                transport.fetchOperationInfo(
                    input.getOperationName(),
                    input.getServiceName(),
                    input.getOperationToken(),
                    input.getOptions()));
          }

          @Override
          public CancelOperationOutput cancelOperation(CancelOperationInput input) {
            return new CancelOperationOutput(
                transport.cancelOperation(
                    input.getOperationName(),
                    input.getServiceName(),
                    input.getOperationToken(),
                    input.getOptions()));
          }

          @Override
          public CompleteOperationOutput completeOperation(CompleteOperationInput input) {
            return new CompleteOperationOutput(
                transport.completeOperation(input.getUrl(), input.getOptions()));
          }

          @Override
          public CompletableFuture<StartOperationOutput> startOperationAsync(
              StartOperationInput input) {
            return transport
                .startOperationAsync(
                    input.getOperationName(),
                    input.getServiceName(),
                    input.getInput(),
                    input.getOptions())
                .thenApply(StartOperationOutput::new);
          }

          @Override
          public CompletableFuture<FetchOperationResultOutput> fetchOperationResultAsync(
              FetchOperationResultInput input) {
            return transport
                .fetchOperationResultAsync(
                    input.getOperationName(),
                    input.getServiceName(),
                    input.getOperationToken(),
                    input.getOptions())
                .thenApply(FetchOperationResultOutput::new);
          }

          @Override
          public CompletableFuture<FetchOperationInfoOutput> fetchOperationInfoAsync(
              FetchOperationInfoInput input) {
            return transport
                .fetchOperationInfoAsync(
                    input.getOperationName(),
                    input.getServiceName(),
                    input.getOperationToken(),
                    input.getOptions())
                .thenApply(FetchOperationInfoOutput::new);
          }

          @Override
          public CompletableFuture<CancelOperationOutput> cancelOperationAsync(
              CancelOperationInput input) {
            return transport
                .cancelOperationAsync(
                    input.getOperationName(),
                    input.getServiceName(),
                    input.getOperationToken(),
                    input.getOptions())
                .thenApply(CancelOperationOutput::new);
          }

          @Override
          public CompletableFuture<CompleteOperationOutput> completeOperationAsync(
              CompleteOperationAsyncInput input) {
            return transport
                .completeOperationAsync(input.getOperationToken(), input.getOptions())
                .thenApply(CompleteOperationOutput::new);
          }
        });
  }
}
