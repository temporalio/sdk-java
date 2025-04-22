/*
 * Copyright (C) 2024 Temporal Technologies, Inc. All Rights Reserved.
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

package io.temporal.failure;
// TODO(thomas): string values ?
/**
 * Mirrors the proto definition for ApplicationErrorCategory. Used to categorize application
 * failures, for example, to distinguish benign errors from others.
 *
 * @see io.temporal.api.enums.v1.ApplicationErrorCategory
 */
public enum ApplicationErrorCategory {
  UNSPECIFIED,
  /** Expected application error with little/no severity. */
  BENIGN,
} 