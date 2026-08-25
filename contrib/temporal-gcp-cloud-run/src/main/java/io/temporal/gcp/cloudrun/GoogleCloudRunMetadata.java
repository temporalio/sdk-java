package io.temporal.gcp.cloudrun;

import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.Experimental;
import io.temporal.common.VersioningBehavior;
import io.temporal.common.WorkerDeploymentVersion;
import io.temporal.worker.WorkerDeploymentOptions;
import io.temporal.worker.WorkerOptions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Reads Google Cloud Run instance metadata and derives a Temporal worker identity and a {@link
 * WorkerDeploymentVersion} from it.
 *
 * <p>Cloud Run runs a long-lived container rather than a per-request handler, so this class is a
 * metadata helper rather than a worker wrapper. Fetch the metadata once while a worker starts up,
 * then apply it to your client and worker option builders with {@link
 * #applyTo(WorkflowClientOptions.Builder)} and {@link #applyTo(WorkerOptions.Builder)}.
 *
 * <p>The deployment name and revision are resolved from environment variables Cloud Run injects
 * into every instance. Cloud Run <b>worker pools</b> set {@code CLOUD_RUN_WORKER_POOL} and {@code
 * CLOUD_RUN_REVISION}; Cloud Run <b>services</b> set {@code K_SERVICE} and {@code K_REVISION}. The
 * name is the first non-empty of {@code CLOUD_RUN_WORKER_POOL} then {@code K_SERVICE}, and the
 * revision is the first non-empty of {@code CLOUD_RUN_REVISION} then {@code K_REVISION}. The unique
 * instance id is only available from the Cloud Run metadata server, so {@link #fetch()} performs a
 * single HTTP request against it.
 *
 * <p><b>Experimental:</b> Google Cloud Run support is experimental and may change without notice.
 */
@Experimental
public final class GoogleCloudRunMetadata {
  /** Name of the environment variable Cloud Run worker pools set to the worker pool name. */
  public static final String CLOUD_RUN_WORKER_POOL = "CLOUD_RUN_WORKER_POOL";

  /** Name of the environment variable Cloud Run worker pools set to the revision name. */
  public static final String CLOUD_RUN_REVISION = "CLOUD_RUN_REVISION";

  /** Name of the environment variable Cloud Run services set to the deployed service name. */
  public static final String K_SERVICE = "K_SERVICE";

  /** Name of the environment variable Cloud Run services set to the deployed revision name. */
  public static final String K_REVISION = "K_REVISION";

  /** Default Cloud Run metadata server URL that returns the unique instance id. */
  public static final String DEFAULT_METADATA_URL =
      "http://metadata.google.internal/computeMetadata/v1/instance/id";

  /** Default connect and read timeout used when contacting the metadata server. */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);

  private static final String METADATA_FLAVOR_HEADER = "Metadata-Flavor";
  private static final String METADATA_FLAVOR_VALUE = "Google";

  private final String instanceId;
  private final String name;
  private final String revision;

  private GoogleCloudRunMetadata(String instanceId, String name, String revision) {
    this.instanceId = instanceId;
    this.name = name;
    this.revision = revision;
  }

  /**
   * Fetches Cloud Run instance metadata using the {@linkplain #DEFAULT_METADATA_URL default
   * metadata URL} and the {@linkplain #DEFAULT_TIMEOUT default timeout}.
   *
   * @return metadata describing the current Cloud Run instance.
   * @throws IllegalStateException if the metadata server cannot be reached, which usually means the
   *     process is not running on Google Cloud Run.
   */
  public static GoogleCloudRunMetadata fetch() {
    return fetch(DEFAULT_METADATA_URL, DEFAULT_TIMEOUT);
  }

  /**
   * Fetches Cloud Run instance metadata from the supplied metadata server URL.
   *
   * <p>The deployment name is read from {@code CLOUD_RUN_WORKER_POOL} then {@code K_SERVICE}, and
   * the revision from {@code CLOUD_RUN_REVISION} then {@code K_REVISION}. The unique instance id is
   * read from {@code metadataUrl} with the required {@code Metadata-Flavor: Google} request header.
   *
   * @param metadataUrl URL of the Cloud Run metadata endpoint that returns the instance id.
   * @param timeout connect and read timeout applied to the metadata request.
   * @return metadata describing the current Cloud Run instance.
   * @throws IllegalStateException if the metadata server cannot be reached, which usually means the
   *     process is not running on Google Cloud Run.
   */
  public static GoogleCloudRunMetadata fetch(String metadataUrl, Duration timeout) {
    Objects.requireNonNull(metadataUrl, "metadataUrl");
    Objects.requireNonNull(timeout, "timeout");

    String name = firstNonBlank(System.getenv(CLOUD_RUN_WORKER_POOL), System.getenv(K_SERVICE));
    String revision = firstNonBlank(System.getenv(CLOUD_RUN_REVISION), System.getenv(K_REVISION));

    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) new URL(metadataUrl).openConnection();
      connection.setRequestMethod("GET");
      connection.setRequestProperty(METADATA_FLAVOR_HEADER, METADATA_FLAVOR_VALUE);
      int timeoutMillis = timeoutMillis(timeout);
      connection.setConnectTimeout(timeoutMillis);
      connection.setReadTimeout(timeoutMillis);

      String instanceId = readBody(connection).trim();
      return new GoogleCloudRunMetadata(instanceId, name, revision);
    } catch (IOException e) {
      throw new IllegalStateException(
          "Unable to read the Cloud Run instance id from the metadata server at "
              + metadataUrl
              + "; this process may not be running on Google Cloud Run",
          e);
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }

  /**
   * @return the unique Cloud Run instance id read from the metadata server.
   */
  public String getInstanceId() {
    return instanceId;
  }

  /**
   * @return the Cloud Run deployment name, resolved from {@code CLOUD_RUN_WORKER_POOL} then {@code
   *     K_SERVICE}, or {@code null} when neither was set.
   */
  public String getName() {
    return name;
  }

  /**
   * @return the Cloud Run revision name, resolved from {@code CLOUD_RUN_REVISION} then {@code
   *     K_REVISION}, or {@code null} when neither was set.
   */
  public String getRevision() {
    return revision;
  }

  /**
   * Builds a Temporal worker identity for this Cloud Run instance.
   *
   * <p>The identity is {@code instanceId@revision}. When the revision is blank the name is used
   * instead, and when both are blank the bare instance id is returned.
   *
   * @return a worker identity string suitable for {@code WorkflowClientOptions} and {@code
   *     WorkerOptions}.
   */
  public String workerIdentity() {
    if (!isBlank(revision)) {
      return instanceId + "@" + revision;
    }
    if (!isBlank(name)) {
      return instanceId + "@" + name;
    }
    return instanceId;
  }

  /**
   * Builds a {@link WorkerDeploymentVersion} from the Cloud Run name and revision.
   *
   * <p>The name becomes the deployment name and the revision becomes the build id, so each Cloud
   * Run revision maps to a distinct worker deployment version.
   *
   * @return a worker deployment version derived from the resolved name and revision.
   * @throws IllegalStateException if the name or revision is blank, which usually means the process
   *     is not running on a Cloud Run worker pool or service.
   */
  public WorkerDeploymentVersion workerDeploymentVersion() {
    if (isBlank(name) || isBlank(revision)) {
      throw new IllegalStateException(
          "A Cloud Run name and revision are required to build a WorkerDeploymentVersion; "
              + "this process may not be running on a Cloud Run worker pool or service");
    }
    return new WorkerDeploymentVersion(name, revision);
  }

  /**
   * Applies the derived {@linkplain #workerIdentity() worker identity} to a workflow client options
   * builder.
   *
   * @param builder the workflow client options builder to configure.
   * @return the same builder, for chaining.
   */
  public WorkflowClientOptions.Builder applyTo(WorkflowClientOptions.Builder builder) {
    Objects.requireNonNull(builder, "builder");
    builder.setIdentity(workerIdentity());
    return builder;
  }

  /**
   * Applies the derived {@linkplain #workerDeploymentVersion() worker deployment version} to a
   * worker options builder, enabling worker versioning with a PINNED default behavior.
   *
   * @param builder the worker options builder to configure.
   * @return the same builder, for chaining.
   * @throws IllegalStateException if the name or revision is blank, which usually means the process
   *     is not running on a Cloud Run worker pool or service.
   */
  public WorkerOptions.Builder applyTo(WorkerOptions.Builder builder) {
    Objects.requireNonNull(builder, "builder");
    builder.setDeploymentOptions(
        WorkerDeploymentOptions.newBuilder()
            .setUseVersioning(true)
            .setVersion(workerDeploymentVersion())
            .setDefaultVersioningBehavior(VersioningBehavior.PINNED)
            .build());
    return builder;
  }

  private static String readBody(HttpURLConnection connection) throws IOException {
    try (InputStream in = connection.getInputStream()) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] chunk = new byte[512];
      int read;
      while ((read = in.read(chunk)) != -1) {
        out.write(chunk, 0, read);
      }
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  private static int timeoutMillis(Duration timeout) {
    long millis = timeout.toMillis();
    if (millis < 0) {
      throw new IllegalArgumentException("timeout must not be negative");
    }
    return (int) Math.min(millis, Integer.MAX_VALUE);
  }

  private static String firstNonBlank(String first, String second) {
    if (!isBlank(first)) {
      return first;
    }
    return isBlank(second) ? null : second;
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
