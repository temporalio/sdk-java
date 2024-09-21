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

package io.temporal.internal.common;

import org.junit.Assert;
import org.junit.Test;

public class NexusUtilTest {
  @Test
  public void testParseRequestTimeout() {
    Assert.assertThrows(
        IllegalArgumentException.class, () -> NexusUtil.parseRequestTimeout("invalid"));
    Assert.assertThrows(IllegalArgumentException.class, () -> NexusUtil.parseRequestTimeout("1h"));
    Assert.assertEquals(java.time.Duration.ofMillis(10), NexusUtil.parseRequestTimeout("10ms"));
    Assert.assertEquals(java.time.Duration.ofMillis(10), NexusUtil.parseRequestTimeout("10.1ms"));
    Assert.assertEquals(java.time.Duration.ofSeconds(1), NexusUtil.parseRequestTimeout("1s"));
    Assert.assertEquals(java.time.Duration.ofMinutes(999), NexusUtil.parseRequestTimeout("999m"));
    Assert.assertEquals(java.time.Duration.ofMillis(1300), NexusUtil.parseRequestTimeout("1.3s"));
  }
}
