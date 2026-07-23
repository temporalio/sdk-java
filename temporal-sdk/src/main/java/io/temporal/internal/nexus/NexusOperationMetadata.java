package io.temporal.internal.nexus;

import io.temporal.common.Experimental;
import java.util.Map;

/** Container for an in-flight Nexus operation metadata. */
@Experimental
public final class NexusOperationMetadata {
  public final String requestId;
  public final String callbackUrl;
  public final Map<String, String> callbackHeaders;

  public String operationToken;
  public boolean operationCompleted;

  public NexusOperationMetadata(
      String requestId, String callbackUrl, Map<String, String> callbackHeaders) {
    this.requestId = requestId;
    this.callbackUrl = callbackUrl;
    this.callbackHeaders = callbackHeaders;
  }
}
