package io.temporal.workflow.shared;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.workflowservice.v1.CountNexusOperationExecutionsRequest;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import org.junit.Assume;

/**
 * Skip-guard for tests that exercise standalone Nexus operation RPCs ({@code
 * StartNexusOperationExecution}, {@code DescribeNexusOperationExecution}, etc.). Call from
 * {@code @BeforeClass}.
 *
 * <p>{@link SDKTestWorkflowRule#useExternalService} only tells us that an external server is
 * reachable — not that it implements every RPC we want to call. The Temporal CLI's {@code
 * start-dev} server (which CI uses for the "Unit test with CLI" job) accepts connections but
 * returns {@code UNIMPLEMENTED} for the standalone Nexus RPCs. Hitting that mid-test produces a
 * confusing failure; this guard probes the server once and skips the suite cleanly if the RPCs
 * aren't wired through.
 *
 * <p>Suites are skipped via {@link Assume#assumeTrue} when:
 *
 * <ul>
 *   <li>the rule is using the in-memory test server ({@code USE_EXTERNAL_SERVICE} unset/false), or
 *   <li>the configured external server returns {@code UNIMPLEMENTED} for a standalone Nexus RPC.
 * </ul>
 */
public final class StandaloneNexusTestPrerequisites {

  private static volatile Boolean cachedServerSupportsStandaloneNexus;
  private static final Object PROBE_LOCK = new Object();

  private StandaloneNexusTestPrerequisites() {}

  /**
   * Skips the calling suite if the configured server doesn't support standalone Nexus RPCs. Probes
   * the server at most once per JVM and caches the outcome.
   */
  public static void requireServerSupport() {
    Assume.assumeTrue(
        "standalone Nexus operations require an external server (USE_EXTERNAL_SERVICE=true)",
        SDKTestWorkflowRule.useExternalService);
    Assume.assumeTrue(
        "configured external server does not implement standalone Nexus RPCs",
        probeServerSupport());
  }

  private static boolean probeServerSupport() {
    Boolean cached = cachedServerSupportsStandaloneNexus;
    if (cached != null) {
      return cached;
    }
    synchronized (PROBE_LOCK) {
      if (cachedServerSupportsStandaloneNexus != null) {
        return cachedServerSupportsStandaloneNexus;
      }
      cachedServerSupportsStandaloneNexus = probeOnce();
      return cachedServerSupportsStandaloneNexus;
    }
  }

  private static boolean probeOnce() {
    String address = System.getenv("TEMPORAL_SERVICE_ADDRESS");
    if (address == null || address.isEmpty()) {
      address = "127.0.0.1:7233";
    }
    WorkflowServiceStubs stubs =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder().setTarget(address).build());
    try {
      stubs
          .blockingStub()
          .countNexusOperationExecutions(
              CountNexusOperationExecutionsRequest.newBuilder().setNamespace("default").build());
      return true;
    } catch (StatusRuntimeException e) {
      // UNIMPLEMENTED is the only status that tells us the RPC method isn't wired through. Every
      // other status (NOT_FOUND for an absent namespace, INVALID_ARGUMENT, PERMISSION_DENIED, etc.)
      // proves the method exists on the server even if this particular call was rejected.
      return e.getStatus().getCode() != Status.Code.UNIMPLEMENTED;
    } finally {
      stubs.shutdownNow();
    }
  }
}
