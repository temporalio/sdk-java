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

package io.temporal.worker;

/**
 * This exception is thrown when worker has
 *
 * <ul>
 *   <li>an activity instance for the activity type
 *   <li>a workflow type or factory for the workflow type
 * </ul>
 *
 * already registered.
 */
public class TypeAlreadyRegisteredException extends IllegalStateException {
  private final String registeredTypeName;

  public TypeAlreadyRegisteredException(String registeredTypeName, String message) {
    super(message);
    this.registeredTypeName = registeredTypeName;
  }

  /** Workflow or Activity type that is already registered */
  public String getRegisteredTypeName() {
    return registeredTypeName;
  }
}
