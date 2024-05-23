package io.temporal.common.converter

import com.fasterxml.jackson.module.kotlin.PackageVersion
import org.junit.Assert.assertEquals
import org.junit.Test

class KotlinObjectMapperFactoryTest {
  @Test
  fun `test jackson 2 9 0`() {
    assertEquals(PackageVersion.VERSION.toString(), "2.9.0")
    KotlinObjectMapperFactory.new()
  }
}
