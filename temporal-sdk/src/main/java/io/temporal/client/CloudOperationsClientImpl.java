package io.temporal.client;

import io.temporal.serviceclient.CloudServiceStubs;

class CloudOperationsClientImpl implements CloudOperationsClient {
  private final CloudServiceStubs cloudServiceStubs;

  CloudOperationsClientImpl(CloudServiceStubs cloudServiceStubs) {
    this.cloudServiceStubs = cloudServiceStubs;
  }

  @Override
  public CloudServiceStubs getCloudServiceStubs() {
    return cloudServiceStubs;
  }
}
