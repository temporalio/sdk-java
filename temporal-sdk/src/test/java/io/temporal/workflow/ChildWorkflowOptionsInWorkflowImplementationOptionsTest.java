package io.temporal.workflow;

import static org.junit.Assert.*;

import io.temporal.worker.WorkflowImplementationOptions;
import java.time.Duration;
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

    // Override takes precedence for workflowExecutionTimeout
    assertEquals(Duration.ofSeconds(200), merged.getWorkflowExecutionTimeout());
    // Base values are preserved for fields not set in override
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
}
