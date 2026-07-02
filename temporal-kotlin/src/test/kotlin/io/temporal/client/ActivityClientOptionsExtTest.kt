package io.temporal.client

import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityClientOptionsExtTest {

  @Test
  fun `ActivityClientOptions DSL should be equivalent to builder`() {
    val dslOptions = ActivityClientOptions {
      setNamespace("test")
      setIdentity("identity")
    }

    val builderOptions = ActivityClientOptions.newBuilder()
      .setNamespace("test")
      .setIdentity("identity")
      .build()

    assertEquals(builderOptions, dslOptions)
  }

  @Test
  fun `ActivityClientOptions copy() DSL should merge override options`() {
    val sourceOptions = ActivityClientOptions {
      setNamespace("test")
      setIdentity("identity1")
    }

    val overriddenOptions = sourceOptions.copy {
      setIdentity("identity2")
    }

    val expectedOptions = ActivityClientOptions {
      setNamespace("test")
      setIdentity("identity2")
    }

    assertEquals(expectedOptions, overriddenOptions)
  }
}
