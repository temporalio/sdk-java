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

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * WorkflowInterface annotation indicates that an interface is a Workflow interface. Only interfaces
 * annotated with this annotation can be used as parameters to {@link
 * WorkflowClient#newWorkflowStub(Class, WorkflowOptions)} and {@link
 * Workflow#newChildWorkflowStub(Class)} methods.
 *
 * <p>All methods of an interface annotated with WorkflowInterface must have one of the following
 * annotations: {@literal @}WorkflowMethod, {@literal @}SignalMethod or {@literal @}QueryMethod
 *
 * <p>An interface annotated with WorkflowInterface can extend other interfaces annotated with
 * WorkflowInterface having that it can have <b>at most one</b> method annotated with
 * {@literal @}WorkflowMethod including all inherited methods.
 *
 * <p>The prefix of workflow, signal and query type names is the name of the declaring interface
 * annotated with WorkflowInterface. If a method is declared in non annotated interface the prefix
 * comes from the first sub-interface that has the WorkflowInterface annotation.
 *
 * <p>A workflow implementation object must have <b>exactly one</b> method annotated with
 * {@literal @}WorkflowMethod inherited from all the interfaces it implements.
 *
 * <p>A workflow implementation may have a no-arg constructor or a constructor annotated with
 * {@literal @}WorkflowInit that takes the same set of arguments as the workflow interface method
 * annotated with {@literal @}WorkflowMethod. Users should avoid blocking operations in the
 * constructor as the constructor must be called before the workflow method is invoked and before
 * any Signal, Update, or Queries handlers are registered.
 *
 * <p>Example:
 *
 * <pre><code>
 *
 *  public interface A {
 *     {@literal @}SignalMethod
 *      a();
 *      aa();
 *  }
 *
 * {@literal @}WorkflowInterface
 *  public interface B extends A {
 *     {@literal @}SignalMethod
 *      b();
 *
 *     {@literal @}SignalMethod // must define the type of the inherited method
 *      aa();
 *  }
 *
 * {@literal @}WorkflowInterface
 *  public interface C extends B {
 *    {@literal @}WorkflowMethod
 *     c();
 *  }
 *
 * {@literal @}WorkflowInterface
 *  public interface D extends C {
 *    {@literal @}QueryMethod
 *     String d();
 *  }
 *
 *  public class DImpl implements D {
 *      public void a() {}
 *      public void aa() {}
 *      public void b() {}
 *      public void c() {}
 *      public String d() { return "foo"; }
 *  }
 * </code></pre>
 *
 * When <code>DImpl</code> instance is registered with the {@link io.temporal.worker.Worker} the
 * following is registered:
 *
 * <p>
 *
 * <ul>
 *   <li>a signal handler
 *   <li>b signal handler
 *   <li>aa signal handler
 *   <li>c workflow main method
 *   <li>d query method
 * </ul>
 *
 * The client code can call signals through stubs to <code>B</code>, <code>C</code> and <code>D
 * </code> interfaces. A call to create a stub to <code>A</code> interface will fail as <code>A
 * </code> is not annotated with the WorkflowInterface.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface WorkflowInterface {}
