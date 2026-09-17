package io.temporal.payload.storage.s3driver.awssdkv2;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

public class S3AsyncClientAdapterTest {

  /**
   * Cancelling the future the adapter returns must abort the underlying AWS request. The adapter
   * wraps the AWS future with {@code thenApply}, which does not propagate cancellation upstream, so
   * this verifies the explicit forwarding does its job.
   */
  @Test
  public void cancellingReturnedFutureAbortsTheUnderlyingRequest() {
    S3AsyncClient s3 = mock(S3AsyncClient.class);
    CompletableFuture<PutObjectResponse> awsRequest = new CompletableFuture<>();
    when(s3.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
        .thenReturn(awsRequest);

    CompletableFuture<Void> result =
        new S3AsyncClientAdapter(s3).putObject("bucket", "key", new byte[] {1, 2, 3});

    assertFalse(awsRequest.isCancelled());
    result.cancel(true);
    assertTrue(
        "cancelling the adapter's future should abort the AWS request", awsRequest.isCancelled());
  }
}
