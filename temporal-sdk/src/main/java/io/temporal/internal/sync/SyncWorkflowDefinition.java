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

package io.temporal.internal.sync;

import io.temporal.api.common.v1.Payloads;
import io.temporal.common.VersioningBehavior;
import io.temporal.common.interceptors.Header;
import java.util.Optional;
import javax.annotation.Nullable;

/** Workflow wrapper used by the workflow thread to start a workflow */
interface SyncWorkflowDefinition {

  /** Always called first. */
  void initialize(Optional<Payloads> input);

  /**
   * Returns the workflow instance that is executing this code. Must be called after {@link
   * #initialize(Optional)}.
   */
  @Nullable
  Object getInstance();

  Optional<Payloads> execute(Header header, Optional<Payloads> input);

  /**
   * @return The versioning behavior for this workflow as defined by the attached annotation,
   *     otherwise {@link VersioningBehavior#UNSPECIFIED}.
   */
  VersioningBehavior getVersioningBehavior();
}
