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

import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.springframework.boot.context.properties.ConstructorBinding;

public class NonRootNamespaceProperties extends NamespaceProperties {

  /**
   * The bean register name prefix. <br>
   * NOTE: Currently we register a series beans with the same alias. <br>
   * - NamespaceTemplate <br>
   * - ClientTemplate <br>
   * - WorkersTemplate <br>
   * - WorkflowClient <br>
   * - ScheduleClient <br>
   * - WorkerFactory <br>
   * You guys can use this alias to get the beans. <br>
   * for example if you set spring.temporal.namespace[0].alias=foo <br>
   * We can get bean via @Autowired @Qualifier("fooNamespaceTemplate") NamespaceTemplate
   */
  private final @Nonnull String alias;

  @ConstructorBinding
  public NonRootNamespaceProperties(
      @Nonnull String alias,
      @Nonnull String namespace,
      @Nullable WorkersAutoDiscoveryProperties workersAutoDiscovery,
      @Nullable List<WorkerProperties> workers,
      @Nullable WorkflowCacheProperties workflowCache) {
    super(namespace, workersAutoDiscovery, workers, workflowCache);
    this.alias = alias;
  }

  @Nonnull
  public String getAlias() {
    return alias;
  }
}
