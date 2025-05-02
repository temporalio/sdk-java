
package io.temporal.client

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkflowClientOptionsExtTest {

  @Test
  fun `WorkflowClientOptions DSL should be equivalent to builder`() {
    val dslOptions = WorkflowClientOptions {
      setNamespace("test")
      setBinaryChecksum("binary1")
    }

    val builderOptions = WorkflowClientOptions.newBuilder()
      .setNamespace("test")
      .setBinaryChecksum("binary1")
      .build()

    assertEquals(builderOptions, dslOptions)
  }

  @Test
  fun `WorkflowClientOptions copy() DSL should merge override options`() {
    val sourceOptions = WorkflowClientOptions {
      setNamespace("test")
      setBinaryChecksum("binary1")
    }

    val overriddenOptions = sourceOptions.copy {
      setIdentity("id")
      setBinaryChecksum("binary2")
    }

    val expectedOptions = WorkflowClientOptions {
      setNamespace("test")
      setIdentity("id")
      setBinaryChecksum("binary2")
    }

    assertEquals(expectedOptions, overriddenOptions)
  }
}
