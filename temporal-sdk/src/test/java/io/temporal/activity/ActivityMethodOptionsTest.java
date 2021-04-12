/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.activity;

import io.temporal.common.RetryOptions;
import io.temporal.common.metadata.POJOActivityInterfaceMetadata;
import io.temporal.common.metadata.POJOActivityMethodMetadata;
import io.temporal.internal.sync.ActivityInvocationHandler;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class ActivityMethodOptionsTest {

    private final ActivityOptions options =
            ActivityOptions.newBuilder()
                    .setTaskQueue("ActivityOptions")
                    .setHeartbeatTimeout(Duration.ofSeconds(5))
                    .setScheduleToStartTimeout(Duration.ofSeconds(1))
                    .setScheduleToCloseTimeout(Duration.ofDays(5))
                    .setStartToCloseTimeout(Duration.ofSeconds(1))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                    .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
                    .setContextPropagators(null)
                    .build();
    private final ActivityOptions methodOptions =
            ActivityOptions.newBuilder()
                    .setTaskQueue("ActivityMethodOptions")
                    .setHeartbeatTimeout(Duration.ofSeconds(3))
                    .setScheduleToStartTimeout(Duration.ofSeconds(3))
                    .setScheduleToCloseTimeout(Duration.ofDays(3))
                    .setStartToCloseTimeout(Duration.ofSeconds(3))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(33).build())
                    .setCancellationType(ActivityCancellationType.TRY_CANCEL)
                    .setContextPropagators(null)
                    .build();

    @Test
    public void testActivityOptionsMerge() {
        // Assert no changes if no per method options
        ActivityOptions merged = ActivityOptions.newBuilder(options).mergeActivityOptions(null).build();
        Assert.assertEquals(options, merged);
        // Assert options were overridden with method options
        merged = ActivityOptions.newBuilder(options).mergeActivityOptions(methodOptions).build();
        Assert.assertEquals(methodOptions, merged);
    }

    @Test
    public void testActivityRetryOptionsChange() {
        Map<String, ActivityOptions> activityMethodOptions =
                new HashMap<String, ActivityOptions>() {
                    {
                        put("method1", methodOptions);
                    }
                };

        // Test that Map<Method, ActivityOptions> was created
        ActivityInvocationHandler invocationHandler =
                (ActivityInvocationHandler)
                        ActivityInvocationHandler.newInstance(
                                TestActivity.class, options, activityMethodOptions, null);
        Map<Method, ActivityOptions> methodToOptionsMap = invocationHandler.getActivityMethodOptions();
        POJOActivityInterfaceMetadata activityMetadata =
                POJOActivityInterfaceMetadata.newInstance(TestActivity.class);

        for (POJOActivityMethodMetadata methodMetadata : activityMetadata.getMethodsMetadata()) {
            Method method = methodMetadata.getMethod();
            if (method.getName().equals("method1")) {
                Assert.assertEquals(methodOptions, methodToOptionsMap.get(method));
            } else {
                Assert.assertEquals(options, methodToOptionsMap.get(method));
            }
        }
    }

    @ActivityInterface
    public interface TestActivity {

        @ActivityMethod
        void method1();

        @ActivityMethod
        void method2();
    }

    class TestActivityImpl implements TestActivity {
        @Override
        public void method1() {
            System.out.printf("Executing method1.");
        }

        @Override
        public void method2() {
            System.out.printf("Executing method2.");
        }
    }
}