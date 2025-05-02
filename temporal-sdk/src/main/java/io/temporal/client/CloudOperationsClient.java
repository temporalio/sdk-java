package io.temporal.client;

import io.temporal.common.Experimental;
import io.temporal.serviceclient.CloudServiceStubs;

/** Client to the Temporal Cloud operations service for performing cloud operations. */
@Experimental
public interface CloudOperationsClient {
  @Experimental
  static CloudOperationsClient newInstance(CloudServiceStubs service) {
    return new CloudOperationsClientImpl(service);
  }

  /** Get the raw cloud service stubs. */
  @Experimental
  CloudServiceStubs getCloudServiceStubs();
}
