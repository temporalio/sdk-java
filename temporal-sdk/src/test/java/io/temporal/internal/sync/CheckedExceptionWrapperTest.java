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

package io.temporal.internal.sync;

import static org.junit.Assert.assertTrue;

import io.temporal.serviceclient.CheckedExceptionWrapper;
import io.temporal.workflow.Workflow;
import org.junit.Assert;
import org.junit.Test;

public class CheckedExceptionWrapperTest {

  @Test
  public void testUnwrap() {
    try {
      try {
        try {
          try {
            try {
              throw new Exception("1");
            } catch (Exception e) {
              throw Workflow.wrap(e);
            }
          } catch (Exception e) {
            throw Workflow.wrap(e);
          }
        } catch (Exception e) {
          throw new Exception("2", e);
        }
      } catch (Exception e) {
        throw Workflow.wrap(e);
      }
    } catch (Exception e) {
      Throwable result = CheckedExceptionWrapper.unwrap(e);
      Assert.assertEquals("2", result.getMessage());
      Assert.assertEquals("java.lang.Exception: 1", result.getCause().getMessage());
      Assert.assertEquals("1", result.getCause().getCause().getMessage());
      Assert.assertNull(result.getCause().getCause().getCause());
    }
    Exception e = new Exception("5");
    Throwable eu = CheckedExceptionWrapper.unwrap(e);
    Assert.assertEquals(e, eu);
  }

  @Test
  public void customThrowable() {
    RuntimeException wrapped = CheckedExceptionWrapper.wrap(new CustomThrowable());
    Throwable unwrapped = CheckedExceptionWrapper.unwrap(wrapped);
    assertTrue(unwrapped instanceof CustomThrowable);
  }

  private static class CustomThrowable extends Throwable {}
}
