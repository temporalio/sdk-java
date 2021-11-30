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

package io.temporal.workflow;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that the method is a signal handler method. A signal method gets executed when a
 * workflow receives a signal.
 *
 * <p>A Signal can be received and the corresponding signal method can be triggered before execution
 * of the first workflow task. Workflow and Signal method implementations should be compliant with
 * this possibility.<br>
 * Importantly, this happens when a reset of history to the first workflow task (with signal
 * preservation) is performed. This results in signals being appended to the start of the new
 * history.<br>
 * Techniques to consider:
 *
 * <ul>
 *   <li>Workflow object constructors and initialization blocks should be used to initialize the
 *       internal data structures if possible.
 *   <li>In rare cases signal processing may require initialization to be performed by the workflow
 *       code first. An example is initialization that depends on the workflow input parameters. You
 *       may persist data from the signals received when initialization is incomplete into a
 *       workflow field. This data can be processed in the workflow method itself after the required
 *       initialization is performed.
 * </ul>
 *
 * <p>This annotation applies only to workflow interface methods.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SignalMethod {
  /**
   * Name of the signal type. Default is method name.
   *
   * <p>Be careful about names that contain special characters. These names can be used as metric
   * tags. And systems like prometheus ignore metrics which have tags with unsupported characters.
   */
  String name() default "";
}
