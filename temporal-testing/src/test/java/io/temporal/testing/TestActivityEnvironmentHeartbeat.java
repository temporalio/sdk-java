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

package io.temporal.testing;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestActivityEnvironmentHeartbeat {

  TestActivityEnvironment testActivityEnvironment = TestActivityEnvironment.newInstance();

  @Test
  void testHeartbeat() {
    String detailString = "details";
    testActivityEnvironment.registerActivitiesImplementations(new HeartbeatingActivityImpl());
    testActivityEnvironment.setHeartbeatDetails(detailString);
    HearbeatingActivity heartbeatActivityStub =
        testActivityEnvironment.newActivityStub(HearbeatingActivity.class);

    String result = heartbeatActivityStub.heartbeatMethod();
    Assertions.assertEquals(detailString, result);

    String secondResult = heartbeatActivityStub.heartbeatMethod();
    Assertions.assertNull(secondResult);
  }

  @Test
  void testHeartbeatNullWithoutSetting() {
    testActivityEnvironment.registerActivitiesImplementations(new HeartbeatingActivityImpl());
    HearbeatingActivity heartbeatActivityStub =
        testActivityEnvironment.newActivityStub(HearbeatingActivity.class);

    String result = heartbeatActivityStub.heartbeatMethod();
    Assertions.assertNull(result);
  }

  @ActivityInterface
  public interface HearbeatingActivity {

    @ActivityMethod
    String heartbeatMethod();
  }

  static class HeartbeatingActivityImpl implements HearbeatingActivity {

    @Override
    public String heartbeatMethod() {
      Optional<String> maybeDetails =
          Activity.getExecutionContext().getHeartbeatDetails(String.class);
      return maybeDetails.orElse(null);
    }
  }
}
