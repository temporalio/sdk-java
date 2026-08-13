package io.temporal.testing;

import io.temporal.common.Experimental;
import io.temporal.testing.internal.devserver.TemporalDevServerLauncher;
import javax.annotation.Nonnull;

/**
 * A local Temporal dev server owned by the calling process.
 *
 * <pre>{@code
 * try (TemporalDevServer server = TemporalDevServer.start()) {
 *   WorkflowServiceStubs stubs =
 *       WorkflowServiceStubs.newServiceStubs(
 *           WorkflowServiceStubsOptions.newBuilder().setTarget(server.getTarget()).build());
 *   // Use stubs against server.getNamespace().
 * }
 * }</pre>
 */
@Experimental
public final class TemporalDevServer implements AutoCloseable {
  private final String target;
  private final String namespace;
  private final AutoCloseable owner;

  private TemporalDevServer(
      @Nonnull String target, @Nonnull String namespace, @Nonnull AutoCloseable owner) {
    this.target = target;
    this.namespace = namespace;
    this.owner = owner;
  }

  /** Starts a dev server in namespace {@code default} with default options. */
  public static TemporalDevServer start() {
    return start("default", TemporalDevServerOptions.getDefaultInstance());
  }

  /** Starts a dev server in namespace {@code default} with the supplied options. */
  public static TemporalDevServer start(@Nonnull TemporalDevServerOptions options) {
    return start("default", options);
  }

  /** Starts a dev server for the supplied namespace and options. */
  public static TemporalDevServer start(
      @Nonnull String namespace, @Nonnull TemporalDevServerOptions options) {
    if (namespace == null || namespace.trim().isEmpty()) {
      throw new IllegalArgumentException("namespace cannot be blank");
    }
    if (options == null) {
      throw new NullPointerException("options");
    }
    TemporalDevServerLauncher.RunningServer running =
        TemporalDevServerLauncher.start(namespace, options);
    return new TemporalDevServer(running.getTarget(), namespace, running);
  }

  /** Returns the usable {@code host:port} gRPC target. */
  public String getTarget() {
    return target;
  }

  /** Returns the namespace created by the dev server. */
  public String getNamespace() {
    return namespace;
  }

  /** Stops the owned process. This method is idempotent. */
  @Override
  public void close() {
    try {
      owner.close();
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Failed stopping Temporal dev server at " + target, e);
    }
  }
}
