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

package io.temporal.workflow;

import io.temporal.common.Experimental;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that the constructor should be used as a workflow initialization method. The
 * constructor annotated with this annotation is called when a new workflow instance is created. The
 * method must be public and take the same arguments as the workflow method. All the same
 * constraints as for workflow methods apply to workflow initialization methods. Any exceptions
 * thrown by the constructor are treated the same as exceptions thrown by the workflow method.
 *
 * <p>Workflow initialization methods are called before the workflow method, signal handlers, update
 * handlers or query handlers.
 *
 * <p>This annotation applies only to workflow implementation constructors.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.CONSTRUCTOR)
@Experimental
public @interface WorkflowInit {}
