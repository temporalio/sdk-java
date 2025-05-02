
package io.temporal.nexus

import io.temporal.workflow.NexusOperationOptions
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration

class NexusOperationOptionsExtTest {

  @Test
  fun `OperationOptions DSL should be equivalent to builder`() {
    val dslOperationOptions = NexusOperationOptions {
      setScheduleToCloseTimeout(Duration.ofMinutes(1))
    }

    val builderOperationOptions = NexusOperationOptions.newBuilder()
      .setScheduleToCloseTimeout(Duration.ofMinutes(1))
      .build()

    assertEquals(builderOperationOptions, dslOperationOptions)
  }
}
