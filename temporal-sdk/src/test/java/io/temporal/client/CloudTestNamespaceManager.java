package io.temporal.client;

import com.google.protobuf.ByteString;
import com.google.protobuf.util.Durations;
import io.temporal.api.cloud.cloudservice.v1.CloudServiceGrpc;
import io.temporal.api.cloud.cloudservice.v1.CreateNamespaceRequest;
import io.temporal.api.cloud.cloudservice.v1.CreateNamespaceResponse;
import io.temporal.api.cloud.cloudservice.v1.DeleteNamespaceRequest;
import io.temporal.api.cloud.cloudservice.v1.DeleteNamespaceResponse;
import io.temporal.api.cloud.cloudservice.v1.GetAsyncOperationRequest;
import io.temporal.api.cloud.cloudservice.v1.GetAsyncOperationResponse;
import io.temporal.api.cloud.cloudservice.v1.GetNamespaceRequest;
import io.temporal.api.cloud.cloudservice.v1.GetNamespaceResponse;
import io.temporal.api.cloud.namespace.v1.MtlsAuthSpec;
import io.temporal.api.cloud.namespace.v1.NamespaceSpec;
import io.temporal.api.cloud.namespace.v1.ReplicaSpec;
import io.temporal.api.cloud.operation.v1.AsyncOperation;
import io.temporal.serviceclient.CloudServiceStubs;
import io.temporal.serviceclient.CloudServiceStubsOptions;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Creates and deletes an isolated Temporal Cloud namespace for SDK CI. */
public final class CloudTestNamespaceManager {
  static final String CLOUD_REGION = "aws-ca-central-1";
  static final Duration OPERATION_TIMEOUT = Duration.ofMinutes(10);
  static final Duration DEFAULT_POLL_DELAY = Duration.ofSeconds(10);
  static final Duration MIN_POLL_DELAY = Duration.ofSeconds(1);

  private CloudTestNamespaceManager() {}

  public static void main(String[] args) throws Exception {
    boolean createRequested = args.length == 1 && "create".equals(args[0]);
    boolean deleteRequested = args.length == 2 && "delete".equals(args[0]);
    if (!createRequested && !deleteRequested) {
      throw new IllegalArgumentException(
          "Usage: CloudTestNamespaceManager create | delete <namespace>");
    }

    CloudServiceStubs serviceStubs = connect();
    try {
      CloudServiceGrpc.CloudServiceBlockingStub cloudService =
          CloudOperationsClient.newInstance(serviceStubs).getCloudServiceStubs().blockingStub();
      if (createRequested) {
        create(cloudService);
      } else {
        delete(cloudService, args[1]);
      }
    } finally {
      serviceStubs.shutdownNow();
    }
  }

  private static void create(CloudServiceGrpc.CloudServiceBlockingStub cloudService)
      throws Exception {
    String namespaceName =
        "sdk-java-ci-"
            + requiredEnvironmentVariable("GITHUB_RUN_ID")
            + "-"
            + requiredEnvironmentVariable("GITHUB_RUN_ATTEMPT");
    byte[] clientCa =
        Files.readAllBytes(Paths.get(requiredEnvironmentVariable("TEMPORAL_CLOUD_CLIENT_CA_PATH")));

    CreateNamespaceResponse response =
        cloudService.createNamespace(createNamespaceRequest(namespaceName, clientCa));
    if (response.getNamespace().isEmpty()) {
      throw new IllegalStateException("Create namespace response did not include a namespace.");
    }

    // Persist the namespace before polling so cleanup can run if provisioning later fails.
    Files.write(
        Paths.get(requiredEnvironmentVariable("GITHUB_OUTPUT")),
        ("namespace=" + response.getNamespace() + System.lineSeparator())
            .getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
    waitForOperation(cloudService, response.getAsyncOperation());
  }

  private static void delete(
      CloudServiceGrpc.CloudServiceBlockingStub cloudService, String namespace) throws Exception {
    if (namespace == null || namespace.isEmpty()) {
      throw new IllegalArgumentException("Namespace to delete must not be empty.");
    }
    GetNamespaceResponse existing =
        cloudService.getNamespace(GetNamespaceRequest.newBuilder().setNamespace(namespace).build());
    String resourceVersion = existing.getNamespace().getResourceVersion();
    if (resourceVersion.isEmpty()) {
      throw new IllegalStateException(
          "Cloud namespace " + namespace + " did not include a resource version.");
    }

    DeleteNamespaceResponse response =
        cloudService.deleteNamespace(deleteNamespaceRequest(namespace, resourceVersion));
    waitForOperation(cloudService, response.getAsyncOperation());
  }

  static CreateNamespaceRequest createNamespaceRequest(String namespaceName, byte[] clientCa) {
    return CreateNamespaceRequest.newBuilder()
        .setSpec(
            NamespaceSpec.newBuilder()
                .setName(namespaceName)
                .setRetentionDays(1)
                .addReplicas(ReplicaSpec.newBuilder().setRegion(CLOUD_REGION))
                .setMtlsAuth(
                    MtlsAuthSpec.newBuilder()
                        .setAcceptedClientCa(ByteString.copyFrom(clientCa))
                        .setEnabled(true)))
        .build();
  }

  static DeleteNamespaceRequest deleteNamespaceRequest(String namespace, String resourceVersion) {
    return DeleteNamespaceRequest.newBuilder()
        .setNamespace(namespace)
        .setResourceVersion(resourceVersion)
        .build();
  }

  static void waitForOperation(
      CloudServiceGrpc.CloudServiceBlockingStub cloudService, AsyncOperation initialOperation)
      throws InterruptedException {
    String operationId = initialOperation.getId();
    if (operationId.isEmpty()) {
      throw new IllegalStateException("Cloud operation response did not include an ID.");
    }

    long deadline = System.nanoTime() + OPERATION_TIMEOUT.toNanos();
    while (true) {
      if (System.nanoTime() >= deadline) {
        throw new IllegalStateException(
            "Timed out waiting for Cloud operation " + operationId + ".");
      }

      GetAsyncOperationResponse response =
          cloudService.getAsyncOperation(
              GetAsyncOperationRequest.newBuilder().setAsyncOperationId(operationId).build());
      if (!response.hasAsyncOperation()) {
        throw new IllegalStateException("Cloud operation " + operationId + " could not be read.");
      }
      AsyncOperation operation = response.getAsyncOperation();
      if (operationComplete(operation)) {
        return;
      }

      long remainingNanos = deadline - System.nanoTime();
      if (remainingNanos <= 0) {
        throw new IllegalStateException(
            "Timed out waiting for Cloud operation " + operationId + ".");
      }
      long remainingMillis = Math.max(TimeUnit.NANOSECONDS.toMillis(remainingNanos), 1);
      try {
        Thread.sleep(Math.min(pollDelayMillis(operation), remainingMillis));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw e;
      }
    }
  }

  static boolean operationComplete(AsyncOperation operation) {
    switch (operation.getState()) {
      case STATE_FULFILLED:
        return true;
      case STATE_FAILED:
      case STATE_CANCELLED:
      case STATE_REJECTED:
        throw new IllegalStateException(
            "Cloud operation "
                + operation.getId()
                + " "
                + operation.getState()
                + ": "
                + operation.getFailureReason());
      default:
        return false;
    }
  }

  static long pollDelayMillis(AsyncOperation operation) {
    long delayMillis =
        operation.hasCheckDuration()
            ? Durations.toMillis(operation.getCheckDuration())
            : DEFAULT_POLL_DELAY.toMillis();
    return Math.max(delayMillis, MIN_POLL_DELAY.toMillis());
  }

  private static CloudServiceStubs connect() {
    String apiKey = requiredEnvironmentVariable("TEMPORAL_CLIENT_CLOUD_API_KEY");
    String apiVersion = requiredEnvironmentVariable("TEMPORAL_CLIENT_CLOUD_API_VERSION");
    return CloudServiceStubs.newServiceStubs(
        CloudServiceStubsOptions.newBuilder()
            .addApiKey(() -> apiKey)
            .setVersion(apiVersion)
            .setRpcTimeout(Duration.ofSeconds(30))
            .build());
  }

  private static String requiredEnvironmentVariable(String name) {
    String value = System.getenv(name);
    if (value == null || value.isEmpty()) {
      throw new IllegalStateException("Missing required environment variable " + name + ".");
    }
    return value;
  }
}
