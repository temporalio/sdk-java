
package io.temporal.common.metadata

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityNameTest {

  @Test
  fun `should resolve simple activity name`() {
    assertEquals("SomeActivityMethod", activityName(Activity1::someActivityMethod))
  }

  @Test
  fun `should resolve activity name override`() {
    assertEquals("OverriddenActivityMethod", activityName(Activity2::someActivityMethod))
  }

  @Test
  fun `should resolve prefixed activity name`() {
    assertEquals("Activity3_SomeActivityMethod", activityName(Activity3::someActivityMethod))
  }

  @Test(expected = IllegalArgumentException::class)
  fun `should fail if not provided with a method reference`() {
    activityName(::String)
  }

  @Test(expected = IllegalArgumentException::class)
  fun `should fail if not provided with an activity interface`() {
    activityName(NotAnActivity::aMethod)
  }

  @ActivityInterface
  interface Activity1 {
    fun someActivityMethod()
  }

  @ActivityInterface
  interface Activity2 {
    @ActivityMethod(name = "OverriddenActivityMethod")
    fun someActivityMethod(param: String)
  }

  @ActivityInterface(namePrefix = "Activity3_")
  interface Activity3 {
    @ActivityMethod
    fun someActivityMethod(): Long
  }

  abstract class NotAnActivity {
    abstract fun aMethod()
  }
}
