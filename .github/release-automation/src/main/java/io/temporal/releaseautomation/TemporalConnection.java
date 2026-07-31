package io.temporal.releaseautomation;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.util.Map;

final class TemporalConnection implements AutoCloseable {
  private final WorkflowServiceStubs service;
  final WorkflowClient client;

  private TemporalConnection(WorkflowServiceStubs service, WorkflowClient client) {
    this.service = service;
    this.client = client;
  }

  static TemporalConnection fromEnvironment(Map<String, String> env) {
    String endpoint = required(env, "TEMPORAL_ADDRESS");
    String namespace = required(env, "TEMPORAL_NAMESPACE");
    String apiKey = required(env, "TEMPORAL_API_KEY");
    WorkflowServiceStubs service =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder()
                .setTarget(endpoint)
                .setEnableHttps(true)
                .addApiKey(() -> apiKey)
                .build());
    WorkflowClient client =
        WorkflowClient.newInstance(
            service, WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
    return new TemporalConnection(service, client);
  }

  private static String required(Map<String, String> env, String name) {
    String value = env.get(name);
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException("Required Temporal Cloud setting is missing: " + name);
    }
    return value;
  }

  @Override
  public void close() {
    service.shutdown();
  }
}
