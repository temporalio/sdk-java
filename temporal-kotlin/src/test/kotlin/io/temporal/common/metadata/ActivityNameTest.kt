/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
