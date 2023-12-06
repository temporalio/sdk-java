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

package io.temporal.testing.junit5.testWorkflowImplementationOptions;

import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class TestWorkflowImplementationOptionsCommon {

  @WorkflowInterface
  public interface HelloWorkflow {

    @WorkflowMethod
    String sayHello(String name);
  }

  /* No full Exceptionimplementation. Just for testing TestWorkflowExtension::registerWorkflowImplementationTypes*/
  public static class TestException extends RuntimeException {
    public TestException(String message) {
      super(message);
    }
  }

  public static class HelloWorkflowImpl implements HelloWorkflow {

    private static final Logger logger = Workflow.getLogger(HelloWorkflowImpl.class);

    @Override
    public String sayHello(String name) {
      logger.info("Hello, {}", name);
      throw new TestException("Hello World");
    }
  }
}
