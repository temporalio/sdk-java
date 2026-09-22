package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkflowImplementationOptions;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;

/**
 * Verifies that the {@link ChildWorkflowOptions} configured on {@link
 * WorkflowImplementationOptions} are actually applied to child workflows created through both typed
 * and untyped stubs, and that the precedence between the per-type options ({@link
 * WorkflowImplementationOptions.Builder#setChildWorkflowOptions(Map)}), the default options ({@link
 * WorkflowImplementationOptions.Builder#setDefaultChildWorkflowOptions}) and the options passed to
 * the stub creation method is correct.
 *
 * <p>Each scenario is verified by reading the child's memo from inside the child workflow, so a
 * test only passes if the expected options object was the one that actually took effect.
 */
public class DefaultChildWorkflowOptionsSetOnWorkflowTest {

  private static final String MEMO_KEY = "optionsSource";
  private static final Duration DEFAULT_RUN_TIMEOUT = Duration.ofSeconds(30);

  /** Lowest precedence options applied to every child workflow. */
  private static final ChildWorkflowOptions defaultChildWorkflowOptions =
      ChildWorkflowOptions.newBuilder()
          .setWorkflowRunTimeout(DEFAULT_RUN_TIMEOUT)
          .setMemo(Collections.singletonMap(MEMO_KEY, "default"))
          .build();

  /** Per-type options applied only to the {@link PerTypeChild} workflow type. */
  private static final ChildWorkflowOptions perTypeChildWorkflowOptions =
      ChildWorkflowOptions.newBuilder()
          .setMemo(Collections.singletonMap(MEMO_KEY, "perType"))
          .build();

  /** Highest precedence options, passed explicitly to the stub creation methods. */
  private static final ChildWorkflowOptions explicitChildWorkflowOptions =
      ChildWorkflowOptions.newBuilder()
          .setMemo(Collections.singletonMap(MEMO_KEY, "explicit"))
          .build();

  private static final Map<String, ChildWorkflowOptions> childWorkflowOptionsMap =
      Collections.singletonMap("PerTypeChild", perTypeChildWorkflowOptions);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              WorkflowImplementationOptions.newBuilder()
                  .setDefaultChildWorkflowOptions(defaultChildWorkflowOptions)
                  .setChildWorkflowOptions(childWorkflowOptionsMap)
                  .build(),
              ParentWorkflowImpl.class,
              PerTypeChildImpl.class,
              DefaultChildImpl.class)
          .build();

  /**
   * Verifies the predefined options applied when no explicit options are passed to the stub
   * creation method: the per-type options must win over the default options, the default options
   * must be applied to types without a per-type entry, and fields the per-type options do not set
   * must fall back to the default options. Untyped stubs must behave the same as typed stubs.
   */
  @Test
  public void testPredefinedOptionsApplied() {
    Map<String, String> report = runParent();

    // Typed stubs.
    assertEquals("perType", report.get("perType.memo"));
    assertEquals("default", report.get("default.memo"));
    // The per-type options only set the memo; other fields fall back to the default options.
    assertEquals(DEFAULT_RUN_TIMEOUT.toString(), report.get("perType.runTimeout"));

    // Untyped stubs.
    assertEquals("perType", report.get("untypedPerType.memo"));
    assertEquals("default", report.get("untypedDefault.memo"));
  }

  /**
   * Verifies that the options passed to the stub creation method have the highest precedence over
   * both the per-type options and the default options, while fields they do not set still fall back
   * through the per-type options to the default options.
   */
  @Test
  public void testExplicitOptionsTakePrecedence() {
    Map<String, String> report = runParent();

    assertEquals("explicit", report.get("explicitOverDefault.memo"));
    assertEquals("explicit", report.get("explicitOverPerType.memo"));
    assertEquals("explicit", report.get("untypedExplicit.memo"));
    // The explicit options only set the memo; other fields fall back to the default options.
    assertEquals(DEFAULT_RUN_TIMEOUT.toString(), report.get("explicitOverPerType.runTimeout"));
  }

  private Map<String, String> runParent() {
    ParentWorkflow parent =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                ParentWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build());
    return parent.execute();
  }

  @WorkflowInterface
  public interface ParentWorkflow {
    @WorkflowMethod
    Map<String, String> execute();
  }

  @WorkflowInterface
  public interface PerTypeChild {
    @WorkflowMethod
    Map<String, String> execute();
  }

  @WorkflowInterface
  public interface DefaultChild {
    @WorkflowMethod
    Map<String, String> execute();
  }

  public static class ParentWorkflowImpl implements ParentWorkflow {
    @Override
    @SuppressWarnings("unchecked")
    public Map<String, String> execute() {
      Map<String, String> report = new HashMap<>();

      // No explicit options: PerTypeChild has per-type options, which must win over the default.
      PerTypeChild perTypeChild = Workflow.newChildWorkflowStub(PerTypeChild.class);
      prefix(report, "perType", perTypeChild.execute());

      // No explicit options and no per-type entry: the default options must be applied.
      DefaultChild defaultChild = Workflow.newChildWorkflowStub(DefaultChild.class);
      prefix(report, "default", defaultChild.execute());

      // Explicit options on a type without per-type options: explicit must win over the default.
      DefaultChild explicitOverDefault =
          Workflow.newChildWorkflowStub(DefaultChild.class, explicitChildWorkflowOptions);
      prefix(report, "explicitOverDefault", explicitOverDefault.execute());

      // Explicit options on a type that also has per-type options: explicit must win over both.
      PerTypeChild explicitOverPerType =
          Workflow.newChildWorkflowStub(PerTypeChild.class, explicitChildWorkflowOptions);
      prefix(report, "explicitOverPerType", explicitOverPerType.execute());

      // Untyped stubs must apply the predefined options the same way as typed stubs.
      ChildWorkflowStub untypedPerType = Workflow.newUntypedChildWorkflowStub("PerTypeChild");
      prefix(report, "untypedPerType", untypedPerType.execute(Map.class));

      ChildWorkflowStub untypedDefault = Workflow.newUntypedChildWorkflowStub("DefaultChild");
      prefix(report, "untypedDefault", untypedDefault.execute(Map.class));

      ChildWorkflowStub untypedExplicit =
          Workflow.newUntypedChildWorkflowStub("PerTypeChild", explicitChildWorkflowOptions);
      prefix(report, "untypedExplicit", untypedExplicit.execute(Map.class));

      return report;
    }

    private static void prefix(
        Map<String, String> target, String prefix, Map<String, String> childReport) {
      for (Map.Entry<String, String> entry : childReport.entrySet()) {
        target.put(prefix + "." + entry.getKey(), entry.getValue());
      }
    }
  }

  public static class PerTypeChildImpl implements PerTypeChild {
    @Override
    public Map<String, String> execute() {
      return reportAppliedOptions();
    }
  }

  public static class DefaultChildImpl implements DefaultChild {
    @Override
    public Map<String, String> execute() {
      return reportAppliedOptions();
    }
  }

  /** Reports the options that were actually applied to the running (child) workflow. */
  private static Map<String, String> reportAppliedOptions() {
    Map<String, String> report = new HashMap<>();
    Object memo = Workflow.getMemo(MEMO_KEY, String.class);
    report.put("memo", memo == null ? "none" : memo.toString());
    report.put("runTimeout", String.valueOf(Workflow.getInfo().getWorkflowRunTimeout()));
    return report;
  }
}
