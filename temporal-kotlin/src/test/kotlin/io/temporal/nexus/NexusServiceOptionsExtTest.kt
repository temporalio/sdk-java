
package io.temporal.nexus

import io.temporal.workflow.NexusOperationOptions
import io.temporal.workflow.NexusServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration

class NexusServiceOptionsExtTest {

  @Test
  fun `ServiceOptions DSL should be equivalent to builder`() {
    val dslServiceOptions = NexusServiceOptions {
      setEndpoint("TestEndpoint")
      setOperationOptions(
        NexusOperationOptions {
          setScheduleToCloseTimeout(Duration.ofMinutes(1))
        }
      )
      setOperationMethodOptions(
        mapOf(
          "test" to NexusOperationOptions {
            setScheduleToCloseTimeout(Duration.ofMinutes(2))
          }
        )
      )
    }

    val builderServiceOptions = NexusServiceOptions.newBuilder()
      .setEndpoint("TestEndpoint")
      .setOperationOptions(
        NexusOperationOptions.newBuilder()
          .setScheduleToCloseTimeout(Duration.ofMinutes(1))
          .build()
      )
      .setOperationMethodOptions(
        mapOf(
          "test" to NexusOperationOptions.newBuilder()
            .setScheduleToCloseTimeout(Duration.ofMinutes(2))
            .build()
        )
      )
      .build()

    assertEquals(builderServiceOptions, dslServiceOptions)
  }
}
