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

package io.temporal.spring.boot.autoconfigure.properties;

import com.google.common.base.MoreObjects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.springframework.boot.context.properties.ConstructorBinding;

@ConstructorBinding
public class ClientProperties {
  public static final String TARGET_IN_PROCESS_TEST_SERVER = "inprocess";

  public static final String TARGET_LOCAL_SERVICE = "local";

  public static final String NAMESPACE_DEFAULT = "default";

  @Nonnull private final String target;
  @Nonnull private final String namespace;

  ClientProperties(@Nonnull String target, @Nullable String namespace) {
    this.target = target;
    this.namespace = MoreObjects.firstNonNull(namespace, NAMESPACE_DEFAULT);
  }

  /**
   * @see io.temporal.serviceclient.WorkflowServiceStubsOptions.Builder#setTarget(String)
   */
  @Nonnull
  public String getTarget() {
    return target;
  }

  /**
   * @see io.temporal.client.WorkflowClientOptions.Builder#setNamespace(String)
   */
  @Nonnull
  public String getNamespace() {
    return namespace;
  }
}
