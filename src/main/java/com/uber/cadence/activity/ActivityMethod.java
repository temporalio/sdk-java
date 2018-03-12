/*
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

package com.uber.cadence.activity;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that the method is an activity method. This annotation applies only to activity
 * interface methods. Not required. Use it to override default activity type name or other options.
 * When both {@link ActivityOptions} and {@link ActivityMethod} have non default value for some
 * parameter the {@link ActivityOptions} one takes precedence.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ActivityMethod {

  /** Name of the workflow type. Default is {short class name}::{method name} */
  String name() default "";

  /**
   * Overall timeout workflow is willing to wait for activity to complete. It includes time in a
   * task list (use {@link #scheduleToStartTimeoutSeconds()} to limit it) plus activity execution
   * time (use {@link #startToCloseTimeoutSeconds()} to limit it). Either this option or both
   * schedule to start and start to close are required.
   */
  int scheduleToCloseTimeoutSeconds() default 0;

  /**
   * Time activity can stay in task list before it is picked up by a worker. If schedule to close is
   * not provided then both this and start to close are required.
   */
  int scheduleToStartTimeoutSeconds() default 0;

  /**
   * Maximum activity execution time after it was sent to a worker. If schedule to close is not
   * provided then both this and schedule to start are required.
   */
  int startToCloseTimeoutSeconds() default 0;

  /**
   * Heartbeat interval. Activity must heartbeat before this interval passes after a last heartbeat
   * or activity start.
   */
  int heartbeatTimeoutSeconds() default 0;

  /**
   * Task list to use when dispatching activity task to a worker. By default it is the same task
   * list name the workflow was started with.
   */
  String taskList() default "";
}
