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

package io.temporal.client.schedules;

import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.GlobalDataConverter;
import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.List;

/** Options for ScheduleClient configuration. */
public final class ScheduleClientOptions {

  public static ScheduleClientOptions.Builder newBuilder() {
    return new ScheduleClientOptions.Builder();
  }

  public static ScheduleClientOptions.Builder newBuilder(ScheduleClientOptions options) {
    return new ScheduleClientOptions.Builder(options);
  }

  public static ScheduleClientOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  public ScheduleClientOptions.Builder toBuilder() {
    return new ScheduleClientOptions.Builder(this);
  }

  private static final ScheduleClientOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = ScheduleClientOptions.newBuilder().build();
  }

  public static final class Builder {
    private static final String DEFAULT_NAMESPACE = "default";
    private static final List<ContextPropagator> EMPTY_CONTEXT_PROPAGATORS =
        Collections.emptyList();

    private String namespace;
    private DataConverter dataConverter;
    private String identity;
    private List<ContextPropagator> contextPropagators;

    private Builder() {}

    private Builder(ScheduleClientOptions options) {
      if (options == null) {
        return;
      }
      namespace = options.namespace;
      dataConverter = options.dataConverter;
      identity = options.identity;
      contextPropagators = options.contextPropagators;
    }

    /** Set the namespace this client will operate on. */
    public Builder setNamespace(String namespace) {
      this.namespace = namespace;
      return this;
    }

    /**
     * Overrides a data converter implementation used serialize workflow arguments and results.
     *
     * <p>Default is {@link DataConverter#getDefaultInstance()}.
     */
    public Builder setDataConverter(DataConverter dataConverter) {
      this.dataConverter = dataConverter;
      return this;
    }

    /** Override human-readable identity of the client. */
    public Builder setIdentity(String identity) {
      this.identity = identity;
      return this;
    }

    /**
     * Set the context propagators for this client.
     *
     * @param contextPropagators specifies the list of context propagators to use with the client.
     */
    public Builder setContextPropagators(List<ContextPropagator> contextPropagators) {
      this.contextPropagators = contextPropagators;
      return this;
    }

    public ScheduleClientOptions build() {
      String name = identity == null ? ManagementFactory.getRuntimeMXBean().getName() : identity;
      return new ScheduleClientOptions(
          namespace == null ? DEFAULT_NAMESPACE : namespace,
          dataConverter == null ? GlobalDataConverter.get() : dataConverter,
          name,
          contextPropagators == null ? EMPTY_CONTEXT_PROPAGATORS : contextPropagators);
    }
  }

  private final String namespace;
  private final DataConverter dataConverter;
  private final String identity;
  private final List<ContextPropagator> contextPropagators;

  private ScheduleClientOptions(
      String namespace,
      DataConverter dataConverter,
      String identity,
      List<ContextPropagator> contextPropagators) {
    this.namespace = namespace;
    this.dataConverter = dataConverter;
    this.identity = identity;
    this.contextPropagators = contextPropagators;
  }

  /**
   * Get the namespace this client will operate on.
   *
   * @return Client namespace
   */
  public String getNamespace() {
    return namespace;
  }

  /**
   * Get the data converters of this client
   *
   * @return The list of data converters to use with the client.
   */
  public DataConverter getDataConverter() {
    return dataConverter;
  }

  /**
   * Get the human-readable identity of the client.
   *
   * @return The identity of the client used on some requests.
   */
  public String getIdentity() {
    return identity;
  }

  /**
   * Get the context propagators of this client
   *
   * @return The list of context propagators to use with the client.
   */
  public List<ContextPropagator> getContextPropagators() {
    return contextPropagators;
  }
}
