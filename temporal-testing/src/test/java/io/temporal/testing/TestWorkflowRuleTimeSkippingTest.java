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

package io.temporal.testing;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TestWorkflowRuleTimeSkippingTest {

  @Test
  public void testWorkflowRuleTimeSkipping() {
    TestWorkflowRule defaultTestWorkflowRule = TestWorkflowRule.newBuilder().build();
    TestWorkflowRule noTimeSkippingWorkflowRule =
        TestWorkflowRule.newBuilder().setUseTimeskipping(false).build();

    assertTrue(
        "By default time skipping should be on",
        defaultTestWorkflowRule
            .createTestEnvOptions(System.currentTimeMillis())
            .isUseExternalService());
    assertFalse(
        "We disabled the time skipping on the rule, so the TestEnvironmentOptions should have it off too",
        noTimeSkippingWorkflowRule
            .createTestEnvOptions(System.currentTimeMillis())
            .isUseExternalService());
  }
}
