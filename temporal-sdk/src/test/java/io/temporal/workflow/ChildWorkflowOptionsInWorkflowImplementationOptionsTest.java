package io.temporal.workflow;

import static org.junit.Assert.*;

import io.temporal.api.common.v1.Payload;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.common.Priority;
import io.temporal.common.RetryOptions;
import io.temporal.common.SearchAttributeKey;
import io.temporal.common.SearchAttributes;
import io.temporal.common.context.ContextPropagator;
import io.temporal.worker.WorkflowImplementationOptions;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;

public class ChildWorkflowOptionsInWorkflowImplementationOptionsTest {

  @Test
  public void testBuilderSetAndGet() {
    ChildWorkflowOptions defaultOpts =
        ChildWorkflowOptions.newBuilder()
            .setWorkflowExecutionTimeout(Duration.ofSeconds(100))
            .setTaskQueue("default-queue")
            .build();

    ChildWorkflowOptions perTypeOpts =
        ChildWorkflowOptions.newBuilder()
            .setWorkflowExecutionTimeout(Duration.ofSeconds(200))
            .setTaskQueue("per-type-queue")
            .build();

    Map<String, ChildWorkflowOptions> optionsMap =
        Collections.singletonMap("MyWorkflow", perTypeOpts);

    WorkflowImplementationOptions options =
        WorkflowImplementationOptions.newBuilder()
            .setDefaultChildWorkflowOptions(defaultOpts)
            .setChildWorkflowOptions(optionsMap)
            .build();

    assertEquals(defaultOpts, options.getDefaultChildWorkflowOptions());
    assertEquals(1, options.getChildWorkflowOptions().size());
    assertEquals(perTypeOpts, options.getChildWorkflowOptions().get("MyWorkflow"));
  }

  @Test
  public void testDefaultInstanceHasEmptyChildWorkflowOptions() {
    WorkflowImplementationOptions options = WorkflowImplementationOptions.getDefaultInstance();
    assertNull(options.getDefaultChildWorkflowOptions());
    assertNotNull(options.getChildWorkflowOptions());
    assertTrue(options.getChildWorkflowOptions().isEmpty());
  }

  @Test
  public void testToBuilder() {
    ChildWorkflowOptions defaultOpts =
        ChildWorkflowOptions.newBuilder()
            .setWorkflowExecutionTimeout(Duration.ofSeconds(100))
            .build();

    Map<String, ChildWorkflowOptions> optionsMap =
        Collections.singletonMap("MyWorkflow", defaultOpts);

    WorkflowImplementationOptions original =
        WorkflowImplementationOptions.newBuilder()
            .setDefaultChildWorkflowOptions(defaultOpts)
            .setChildWorkflowOptions(optionsMap)
            .build();

    WorkflowImplementationOptions copy = original.toBuilder().build();
    assertEquals(original.getDefaultChildWorkflowOptions(), copy.getDefaultChildWorkflowOptions());
    assertEquals(original.getChildWorkflowOptions(), copy.getChildWorkflowOptions());
  }

  @Test
  public void testMergeChildWorkflowOptionsOverridesNonNull() {
    ChildWorkflowOptions base =
        ChildWorkflowOptions.newBuilder()
            .setWorkflowExecutionTimeout(Duration.ofSeconds(100))
            .setTaskQueue("base-queue")
            .setWorkflowRunTimeout(Duration.ofSeconds(50))
            .build();

    ChildWorkflowOptions override =
        ChildWorkflowOptions.newBuilder()
            .setWorkflowExecutionTimeout(Duration.ofSeconds(200))
            .build();

    ChildWorkflowOptions merged =
        ChildWorkflowOptions.newBuilder(base).mergeChildWorkflowOptions(override).build();

    // The override takes precedence for workflowExecutionTimeout.
    assertEquals(Duration.ofSeconds(200), merged.getWorkflowExecutionTimeout());
    // Base values are preserved for fields not set in the override.
    assertEquals("base-queue", merged.getTaskQueue());
    assertEquals(Duration.ofSeconds(50), merged.getWorkflowRunTimeout());
  }

  @Test
  public void testMergeChildWorkflowOptionsWithNull() {
    ChildWorkflowOptions base =
        ChildWorkflowOptions.newBuilder()
            .setWorkflowExecutionTimeout(Duration.ofSeconds(100))
            .setTaskQueue("base-queue")
            .build();

    ChildWorkflowOptions merged =
        ChildWorkflowOptions.newBuilder(base).mergeChildWorkflowOptions(null).build();

    assertEquals(Duration.ofSeconds(100), merged.getWorkflowExecutionTimeout());
    assertEquals("base-queue", merged.getTaskQueue());
  }

  /**
   * Exhaustively verifies the merge for every field. Both options have every field set to distinct
   * values, so the merge result can only match the expectation if each field is merged from the
   * correct getter, and {@code merge(base, empty)} can only equal {@code base} if no field is
   * dropped. The only field the override does not replace is {@code contextPropagators}, which is
   * concatenated.
   */
  @Test
  public void testMergeChildWorkflowOptionsOverridesEveryField() {
    ContextPropagator propagatorA = new TestContextPropagator("A");
    ContextPropagator propagatorB = new TestContextPropagator("B");
    ChildWorkflowOptions optionsA = allFieldsSet(1, propagatorA);
    ChildWorkflowOptions optionsB = allFieldsSet(2, propagatorB);

    // A fully populated override replaces every field of the base, except the context propagator
    // lists, which are concatenated.
    ChildWorkflowOptions merged =
        ChildWorkflowOptions.newBuilder(optionsA).mergeChildWorkflowOptions(optionsB).build();
    ChildWorkflowOptions expected =
        optionsB.toBuilder().setContextPropagators(Arrays.asList(propagatorA, propagatorB)).build();
    assertEquals(expected, merged);

    // An override that sets no fields leaves every field of the base untouched.
    ChildWorkflowOptions mergedWithEmpty =
        ChildWorkflowOptions.newBuilder(optionsA)
            .mergeChildWorkflowOptions(ChildWorkflowOptions.newBuilder().build())
            .build();
    assertEquals(optionsA, mergedWithEmpty);
  }

  /**
   * The two search attribute flavors are mutually exclusive, so an override that specifies either
   * flavor must replace both fields; otherwise the merge could produce options carrying both
   * flavors, which fail at child-scheduling time.
   */
  @Test
  @SuppressWarnings("deprecation")
  public void testMergeChildWorkflowOptionsReplacesSearchAttributeFlavorsAsOneField() {
    Map<String, Object> legacyAttributes = Collections.singletonMap("Field", "legacy");
    ChildWorkflowOptions deprecatedFlavor =
        ChildWorkflowOptions.newBuilder().setSearchAttributes(legacyAttributes).build();
    SearchAttributes typedAttributes =
        SearchAttributes.newBuilder()
            .set(SearchAttributeKey.forText("CustomTextField"), "typed")
            .build();
    ChildWorkflowOptions typedFlavor =
        ChildWorkflowOptions.newBuilder().setTypedSearchAttributes(typedAttributes).build();

    // A typed override replaces a deprecated base.
    ChildWorkflowOptions typedWins =
        ChildWorkflowOptions.newBuilder(deprecatedFlavor)
            .mergeChildWorkflowOptions(typedFlavor)
            .build();
    assertNull(typedWins.getSearchAttributes());
    assertEquals(typedAttributes, typedWins.getTypedSearchAttributes());

    // A deprecated override replaces a typed base.
    ChildWorkflowOptions deprecatedWins =
        ChildWorkflowOptions.newBuilder(typedFlavor)
            .mergeChildWorkflowOptions(deprecatedFlavor)
            .build();
    assertEquals(legacyAttributes, deprecatedWins.getSearchAttributes());
    assertNull(deprecatedWins.getTypedSearchAttributes());

    // An override that specifies neither flavor keeps the base flavor.
    ChildWorkflowOptions baseKept =
        ChildWorkflowOptions.newBuilder(typedFlavor)
            .mergeChildWorkflowOptions(ChildWorkflowOptions.newBuilder().build())
            .build();
    assertNull(baseKept.getSearchAttributes());
    assertEquals(typedAttributes, baseKept.getTypedSearchAttributes());
  }

  /**
   * {@code VERSIONING_INTENT_UNSPECIFIED} means "not set" and must not override a specific
   * versioning intent, matching {@code ActivityOptions.Builder#mergeActivityOptions}.
   */
  @Test
  @SuppressWarnings("deprecation")
  public void testMergeChildWorkflowOptionsIgnoresUnspecifiedVersioningIntent() {
    ChildWorkflowOptions base =
        ChildWorkflowOptions.newBuilder()
            .setVersioningIntent(io.temporal.common.VersioningIntent.VERSIONING_INTENT_COMPATIBLE)
            .build();
    ChildWorkflowOptions override =
        ChildWorkflowOptions.newBuilder()
            .setVersioningIntent(io.temporal.common.VersioningIntent.VERSIONING_INTENT_UNSPECIFIED)
            .build();

    ChildWorkflowOptions merged =
        ChildWorkflowOptions.newBuilder(base).mergeChildWorkflowOptions(override).build();
    assertEquals(
        io.temporal.common.VersioningIntent.VERSIONING_INTENT_COMPATIBLE,
        merged.getVersioningIntent());
  }

  /**
   * Context propagator lists are concatenated instead of replaced, matching {@code
   * ActivityOptions.Builder#mergeActivityOptions}.
   */
  @Test
  public void testMergeChildWorkflowOptionsConcatenatesContextPropagators() {
    ContextPropagator propagatorA = new TestContextPropagator("A");
    ContextPropagator propagatorB = new TestContextPropagator("B");
    ChildWorkflowOptions base =
        ChildWorkflowOptions.newBuilder()
            .setContextPropagators(Collections.singletonList(propagatorA))
            .build();
    ChildWorkflowOptions override =
        ChildWorkflowOptions.newBuilder()
            .setContextPropagators(Collections.singletonList(propagatorB))
            .build();

    ChildWorkflowOptions merged =
        ChildWorkflowOptions.newBuilder(base).mergeChildWorkflowOptions(override).build();
    assertEquals(Arrays.asList(propagatorA, propagatorB), merged.getContextPropagators());

    ChildWorkflowOptions mergedWithEmptyOverride =
        ChildWorkflowOptions.newBuilder(base)
            .mergeChildWorkflowOptions(ChildWorkflowOptions.newBuilder().build())
            .build();
    assertEquals(
        Collections.singletonList(propagatorA), mergedWithEmptyOverride.getContextPropagators());
  }

  /** The deprecated {@code searchAttributes} field is mutually exclusive with the typed variant. */
  @Test
  @SuppressWarnings("deprecation")
  public void testMergeChildWorkflowOptionsMergesDeprecatedSearchAttributes() {
    ChildWorkflowOptions base =
        ChildWorkflowOptions.newBuilder()
            .setSearchAttributes(Collections.singletonMap("Field", "base"))
            .build();
    ChildWorkflowOptions override =
        ChildWorkflowOptions.newBuilder()
            .setSearchAttributes(Collections.singletonMap("Field", "override"))
            .build();

    ChildWorkflowOptions merged =
        ChildWorkflowOptions.newBuilder(base).mergeChildWorkflowOptions(override).build();
    assertEquals(Collections.singletonMap("Field", "override"), merged.getSearchAttributes());

    ChildWorkflowOptions mergedKeepsBase =
        ChildWorkflowOptions.newBuilder(base)
            .mergeChildWorkflowOptions(ChildWorkflowOptions.newBuilder().build())
            .build();
    assertEquals(Collections.singletonMap("Field", "base"), mergedKeepsBase.getSearchAttributes());
  }

  /**
   * Builds a {@link ChildWorkflowOptions} with every field set to a value derived from {@code v}.
   */
  @SuppressWarnings("deprecation")
  private static ChildWorkflowOptions allFieldsSet(int v, ContextPropagator propagator) {
    return ChildWorkflowOptions.newBuilder()
        .setNamespace("namespace-" + v)
        .setWorkflowId("workflow-id-" + v)
        .setWorkflowIdReusePolicy(
            v == 1
                ? WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE
                : WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
        .setWorkflowRunTimeout(Duration.ofSeconds(10 + v))
        .setWorkflowExecutionTimeout(Duration.ofSeconds(20 + v))
        .setWorkflowTaskTimeout(Duration.ofSeconds(30 + v))
        .setTaskQueue("task-queue-" + v)
        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(v).build())
        .setCronSchedule(v + " 0 * * *")
        .setParentClosePolicy(
            v == 1
                ? ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON
                : ParentClosePolicy.PARENT_CLOSE_POLICY_TERMINATE)
        .setMemo(Collections.singletonMap("memoKey", "memo-" + v))
        .setTypedSearchAttributes(
            SearchAttributes.newBuilder()
                .set(SearchAttributeKey.forText("CustomTextField"), "search-attribute-" + v)
                .build())
        .setContextPropagators(Collections.singletonList(propagator))
        .setCancellationType(
            v == 1
                ? ChildWorkflowCancellationType.TRY_CANCEL
                : ChildWorkflowCancellationType.WAIT_CANCELLATION_COMPLETED)
        .setVersioningIntent(
            v == 1
                ? io.temporal.common.VersioningIntent.VERSIONING_INTENT_COMPATIBLE
                : io.temporal.common.VersioningIntent.VERSIONING_INTENT_DEFAULT)
        .setStaticSummary("summary-" + v)
        .setStaticDetails("details-" + v)
        .setPriority(Priority.newBuilder().setPriorityKey(v).build())
        .build();
  }

  private static class TestContextPropagator implements ContextPropagator {
    private final String name;

    TestContextPropagator(String name) {
      this.name = name;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public Map<String, Payload> serializeContext(Object context) {
      return Collections.emptyMap();
    }

    @Override
    public Object deserializeContext(Map<String, Payload> context) {
      return null;
    }

    @Override
    public Object getCurrentContext() {
      return null;
    }

    @Override
    public void setCurrentContext(Object context) {}
  }
}
