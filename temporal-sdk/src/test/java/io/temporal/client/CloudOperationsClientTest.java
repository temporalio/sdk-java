package io.temporal.client;

import io.temporal.api.cloud.cloudservice.v1.GetNamespaceRequest;
import io.temporal.api.cloud.cloudservice.v1.GetNamespaceResponse;
import io.temporal.serviceclient.CloudServiceStubs;
import io.temporal.serviceclient.CloudServiceStubsOptions;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

public class CloudOperationsClientTest {
  private String namespace;
  private String apiKey;
  private String apiVersion;

  @Before
  public void checkCloudEnvVars() {
    namespace = System.getenv("TEMPORAL_CLIENT_CLOUD_NAMESPACE");
    apiKey = System.getenv("TEMPORAL_CLIENT_CLOUD_API_KEY");
    apiVersion = System.getenv("TEMPORAL_CLIENT_CLOUD_API_VERSION");
    Assume.assumeTrue(
        "Cloud environment variables not present", namespace != null && apiKey != null);
  }

  @Test
  public void simpleCall() {
    CloudOperationsClient client =
        CloudOperationsClient.newInstance(
            CloudServiceStubs.newServiceStubs(
                CloudServiceStubsOptions.newBuilder()
                    .addApiKey(() -> apiKey)
                    .setVersion(apiVersion)
                    .build()));
    // Do simple get namespace call
    GetNamespaceResponse resp =
        client
            .getCloudServiceStubs()
            .blockingStub()
            .getNamespace(GetNamespaceRequest.newBuilder().setNamespace(namespace).build());
    Assert.assertEquals(namespace, resp.getNamespace().getNamespace());
  }
}
