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
 * WorkflowImplementationOptions} are actually applied to child workflows, and that the precedence
 * between the per-type options ({@link
 * WorkflowImplementationOptions.Builder#setChildWorkflowOptions(Map)}) and the default options
 * ({@link WorkflowImplementationOptions.Builder#setDefaultChildWorkflowOptions}) is correct.
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

  /** Highest precedence options, passed explicitly to {@link Workflow#newChildWorkflowStub}. */
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
   * The {@link PerTypeChild} type has per-type options configured, so they must win over the
   * default options. With the buggy implementation the default options override the per-type
   * options, so the memo would be {@code "default"} instead of {@code "perType"}.
   */
  @Test
  public void testPerTypeChildWorkflowOptionsOverrideDefault() {
    Map<String, String> report = runParent();
    assertEquals("perType", report.get("perType.memo"));
  }

  /**
   * The {@link DefaultChild} type has no per-type entry, so the default options must be applied to
   * it.
   */
  @Test
  public void testDefaultChildWorkflowOptionsAppliedWhenNoPerTypeMatch() {
    Map<String, String> report = runParent();
    assertEquals("default", report.get("default.memo"));
  }

  /**
   * Per-type options only set the memo; every other field must fall back to the default options.
   * This proves the per-type options are merged on top of the default options field-by-field rather
   * than replacing them wholesale.
   */
  @Test
  public void testPerTypeOptionsMergedWithDefaultForUnsetFields() {
    Map<String, String> report = runParent();
    assertEquals("perType", report.get("perType.memo"));
    assertEquals(DEFAULT_RUN_TIMEOUT.toString(), report.get("perType.runTimeout"));
  }

  /** Explicit options passed to the stub must win over the default options. */
  @Test
  public void testExplicitOptionsOverrideDefault() {
    Map<String, String> report = runParent();
    assertEquals("explicit", report.get("explicitOverDefault.memo"));
  }

  /** Explicit options passed to the stub must win even over the per-type options. */
  @Test
  public void testExplicitOptionsOverridePerType() {
    Map<String, String> report = runParent();
    assertEquals("explicit", report.get("explicitOverPerType.memo"));
  }

  /**
   * Explicit options only set the memo; every other field must still fall back through the per-type
   * options to the default options.
   */
  @Test
  public void testExplicitOptionsMergedWithLowerPrecedenceForUnsetFields() {
    Map<String, String> report = runParent();
    assertEquals("explicit", report.get("explicitOverPerType.memo"));
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
