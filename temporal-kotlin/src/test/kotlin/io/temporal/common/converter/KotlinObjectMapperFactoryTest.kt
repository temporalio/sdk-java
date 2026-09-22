package io.temporal.common.converter

import org.junit.Assert.assertEquals
import org.junit.Test

class KotlinObjectMapperFactoryTest {

  data class TestPayload(val name: String, val count: Int)

  /**
   * A data class has no no-arg constructor, so Jackson can only deserialize it when the Kotlin
   * module is registered. This also guards against [KotlinObjectMapperFactory.new] failing to link
   * against the jackson-module-kotlin version present at runtime, which is not necessarily the one
   * the SDK was compiled against.
   */
  @Test
  fun `new should return a mapper that round-trips a Kotlin data class`() {
    val mapper = KotlinObjectMapperFactory.new()

    val value = TestPayload("payload", 42)
    val roundTripped = mapper.readValue(mapper.writeValueAsString(value), TestPayload::class.java)

    assertEquals(value, roundTripped)
  }
}
