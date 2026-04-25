package io.temporal.payload.context;

import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ActivitySerializationContextTest {

  @Test
  public void standaloneActivityContextAllowsNullWorkflowFields() {
    // Standalone activities have no enclosing workflow, so workflowId and workflowType
    // must be nullable. This currently throws NullPointerException.
    ActivitySerializationContext ctx =
        new ActivitySerializationContext(
            "my-namespace",
            null, // workflowId — absent for standalone activities
            null, // workflowType — absent for standalone activities
            "MyActivity",
            "my-task-queue",
            false);

    assertNull(ctx.getWorkflowId());
    assertNull(ctx.getWorkflowType());
  }
}
