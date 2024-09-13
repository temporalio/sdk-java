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
import java.util.Collections;
import java.util.Map;

/**
 * Options for configuring a NexusService in a Workflow.
 *
 * <p>Use {@link NexusServiceOptions#newBuilder()} to construct an instance.
 */
@Experimental
public final class NexusServiceOptions {

  public static NexusServiceOptions.Builder newBuilder() {
    return new NexusServiceOptions.Builder();
  }

  public static NexusServiceOptions.Builder newBuilder(NexusServiceOptions options) {
    return new NexusServiceOptions.Builder(options);
  }

  public static NexusServiceOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final NexusServiceOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = NexusServiceOptions.newBuilder().build();
  }

  public static final class Builder {
    private String endpoint;
    private NexusOperationOptions operationOptions;
    private Map<String, NexusOperationOptions> operationMethodOptions;

    /**
     * Sets the operation options for the NexusService. These options are used as the default for
     * all operations.
     */
    public NexusServiceOptions.Builder setOperationOptions(NexusOperationOptions operationOptions) {
      this.operationOptions = operationOptions;
      return this;
    }

    /**
     * Sets the endpoint for the NexusService.
     *
     * @param endpoint the endpoint for the NexusService
     */
    public NexusServiceOptions.Builder setEndpoint(String endpoint) {
      this.endpoint = endpoint;
      return this;
    }

    /**
     * Sets operation specific options by the operation name. Merged with the base operation
     * options.
     *
     * @param operationMethodOptions the operation specific options by the operation name
     */
    public NexusServiceOptions.Builder setOperationMethodOptions(
        Map<String, NexusOperationOptions> operationMethodOptions) {
      this.operationMethodOptions = operationMethodOptions;
      return this;
    }

    private Builder() {}

    private Builder(NexusServiceOptions options) {
      if (options == null) {
        return;
      }
      this.endpoint = options.getEndpoint();
      this.operationOptions = options.getOperationOptions();
      this.operationMethodOptions = options.getOperationMethodOptions();
    }

    public NexusServiceOptions build() {
      return new NexusServiceOptions(endpoint, operationOptions, operationMethodOptions);
    }

    public NexusServiceOptions.Builder mergeNexusServiceOptions(NexusServiceOptions override) {
      if (override == null) {
        return this;
      }
      this.endpoint = (override.endpoint == null) ? this.endpoint : override.endpoint;
      this.operationOptions =
          (override.operationOptions == null) ? this.operationOptions : override.operationOptions;
      Map<String, NexusOperationOptions> mergeTo = this.operationMethodOptions;
      if (override.getOperationMethodOptions() != null) {
        override
            .getOperationMethodOptions()
            .forEach(
                (key, value) ->
                    mergeTo.merge(
                        key,
                        value,
                        (o1, o2) ->
                            NexusOperationOptions.newBuilder(o1)
                                .mergeNexusOperationOptions(o2)
                                .build()));
      }
      return this;
    }
  }

  private final NexusOperationOptions operationOptions;

  private final Map<String, NexusOperationOptions> operationMethodOptions;
  private final String endpoint;

  NexusServiceOptions(
      String endpoint,
      NexusOperationOptions operationOptions,
      Map<String, NexusOperationOptions> operationMethodOptions) {
    this.endpoint = endpoint;
    this.operationOptions = operationOptions;
    this.operationMethodOptions =
        (operationMethodOptions == null)
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(operationMethodOptions);
  }

  public NexusOperationOptions getOperationOptions() {
    return operationOptions;
  }

  public String getEndpoint() {
    return endpoint;
  }

  public Map<String, NexusOperationOptions> getOperationMethodOptions() {
    return operationMethodOptions;
  }
}
