package io.temporal.gcp.cloudrun;

import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.Experimental;
import io.temporal.common.SimplePlugin;
import io.temporal.common.VersioningBehavior;
import io.temporal.worker.WorkerDeploymentOptions;
import io.temporal.worker.WorkerOptions;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Plugin that configures a Temporal worker for Google Cloud Run from instance metadata, for both
 * Cloud Run <b>worker pools</b> and Cloud Run <b>services</b>.
 *
 * <p>Register the plugin once on the workflow client and it propagates to every worker created from
 * that client. It reads {@link GoogleCloudRunMetadata Cloud Run instance metadata} once while the
 * client is configured, caches it, and then:
 *
 * <ul>
 *   <li>sets the workflow client <b>identity</b> to the {@linkplain
 *       GoogleCloudRunMetadata#workerIdentity() derived worker identity}, but only when the caller
 *       has not already set an identity (a user-provided identity always wins);
 *   <li>sets each worker's {@link WorkerDeploymentOptions} to the {@linkplain
 *       GoogleCloudRunMetadata#workerDeploymentVersion() derived deployment version} with worker
 *       versioning enabled and a {@link VersioningBehavior#PINNED PINNED} default behavior, so
 *       in-flight workflows stay on the Cloud Run revision that started them.
 * </ul>
 *
 * <p>The metadata is fetched lazily at client-configure time rather than in the constructor,
 * because the fetch performs a network request to the Cloud Run metadata server that belongs at
 * connect time. The metadata server is only reachable from a Cloud Run instance, so the fetch fails
 * fast with an {@link IllegalStateException} when this process is not running on Cloud Run.
 *
 * <p>Register the plugin with {@link WorkflowClientOptions.Builder#setPlugins}:
 *
 * <pre>{@code
 * WorkflowClient client =
 *     WorkflowClient.newInstance(
 *         service,
 *         WorkflowClientOptions.newBuilder()
 *             .setNamespace(namespace)
 *             .setPlugins(new WorkerIdPlugin())
 *             .build());
 *
 * WorkerFactory factory = WorkerFactory.newInstance(client);
 * Worker worker = factory.newWorker("my-task-queue");
 * }</pre>
 *
 * <p><b>Advanced / testing:</b> {@link #WorkerIdPlugin(GoogleCloudRunMetadata)} accepts an
 * already-resolved {@link GoogleCloudRunMetadata} instance, which skips the lazy fetch entirely.
 * This is useful when the application fetches the metadata itself (for example to log it) or when a
 * test injects fixed metadata.
 *
 * <p><b>Experimental:</b> Google Cloud Run support is experimental and may change without notice.
 */
@Experimental
public final class WorkerIdPlugin extends SimplePlugin {
  /** Unique plugin name, used for logging and duplicate detection. */
  public static final String NAME = "io.temporal.gcp.cloudrun.workerid";

  private final Supplier<GoogleCloudRunMetadata> metadataSupplier;
  private volatile GoogleCloudRunMetadata metadata;

  /**
   * Creates a plugin that fetches Cloud Run instance metadata from the {@linkplain
   * GoogleCloudRunMetadata#DEFAULT_METADATA_URL default metadata server} while the workflow client
   * is configured.
   */
  public WorkerIdPlugin() {
    this(GoogleCloudRunMetadata::fetch);
  }

  /**
   * Creates a plugin that uses an already-resolved {@link GoogleCloudRunMetadata} instance instead
   * of fetching it. No request is made to the Cloud Run metadata server.
   *
   * @param metadata previously fetched Cloud Run instance metadata.
   */
  public WorkerIdPlugin(GoogleCloudRunMetadata metadata) {
    this(pinnedSupplier(metadata));
  }

  /**
   * Package-private test seam that supplies the {@link GoogleCloudRunMetadata} lazily. It lets unit
   * tests point the fetch at an in-process metadata server and injected environment through the
   * {@link GoogleCloudRunMetadata#fetch(String, java.time.Duration, java.util.function.Function)}
   * seam, and to exercise the off-platform fail-fast path. It is not part of the public API; use
   * {@link #WorkerIdPlugin()} or {@link #WorkerIdPlugin(GoogleCloudRunMetadata)} instead.
   *
   * @param metadataSupplier supplier invoked once, at client-configure time, to resolve the
   *     metadata.
   */
  WorkerIdPlugin(Supplier<GoogleCloudRunMetadata> metadataSupplier) {
    super(NAME);
    this.metadataSupplier = Objects.requireNonNull(metadataSupplier, "metadataSupplier");
  }

  /**
   * Fetches (once) and caches the Cloud Run instance metadata, then sets the derived worker
   * identity on the client options when the caller has not already set an identity.
   *
   * @param builder the workflow client options builder to configure.
   * @throws IllegalStateException if the Cloud Run metadata server cannot be reached, which usually
   *     means this process is not running on Google Cloud Run.
   */
  @Override
  public void configureWorkflowClient(WorkflowClientOptions.Builder builder) {
    GoogleCloudRunMetadata resolved = metadata();
    if (isBlank(builder.build().getIdentity())) {
      builder.setIdentity(resolved.workerIdentity());
    }
  }

  /**
   * Sets the worker's {@link WorkerDeploymentOptions} from the cached Cloud Run metadata, enabling
   * worker versioning with a {@link VersioningBehavior#PINNED PINNED} default behavior.
   *
   * @param taskQueue the task queue name for the worker being created.
   * @param builder the worker options builder to configure.
   * @throws IllegalStateException if the Cloud Run name or revision is not set, which usually means
   *     this process is not running on a Cloud Run worker pool or service.
   */
  @Override
  public void configureWorker(String taskQueue, WorkerOptions.Builder builder) {
    GoogleCloudRunMetadata resolved = metadata();
    builder.setDeploymentOptions(
        WorkerDeploymentOptions.newBuilder()
            .setUseVersioning(true)
            .setVersion(resolved.workerDeploymentVersion())
            .setDefaultVersioningBehavior(VersioningBehavior.PINNED)
            .build());
  }

  private GoogleCloudRunMetadata metadata() {
    GoogleCloudRunMetadata local = metadata;
    if (local == null) {
      synchronized (this) {
        local = metadata;
        if (local == null) {
          local = Objects.requireNonNull(metadataSupplier.get(), "Cloud Run metadata");
          metadata = local;
        }
      }
    }
    return local;
  }

  private static Supplier<GoogleCloudRunMetadata> pinnedSupplier(GoogleCloudRunMetadata metadata) {
    Objects.requireNonNull(metadata, "metadata");
    return () -> metadata;
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
