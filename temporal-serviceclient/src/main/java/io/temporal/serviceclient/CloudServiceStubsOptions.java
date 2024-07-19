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

package io.temporal.serviceclient;

import io.grpc.ManagedChannel;
import java.util.Objects;

/**
 * Options for cloud service.
 *
 * <p>WARNING: The cloud service is currently experimental.
 */
public final class CloudServiceStubsOptions extends ServiceStubsOptions {
  public static final String DEFAULT_CLOUD_TARGET = "saas-api.tmprl.cloud:443";

  private static final CloudServiceStubsOptions DEFAULT_INSTANCE =
      newBuilder().validateAndBuildWithDefaults();

  /** Version header if any. */
  private final String version;

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(CloudServiceStubsOptions options) {
    // We intentionally only accept our options and not the base class of
    // options to ensure our defaults were originally applied at some point
    // when the options class was first created.
    return new Builder(options);
  }

  public static CloudServiceStubsOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private CloudServiceStubsOptions(ServiceStubsOptions serviceStubsOptions, String version) {
    super(serviceStubsOptions);
    this.version = version;
  }

  /**
   * @return Returns the version used for the version header if any.
   */
  public String getVersion() {
    return version;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    if (!super.equals(o)) return false;
    CloudServiceStubsOptions that = (CloudServiceStubsOptions) o;
    return Objects.equals(version, that.version);
  }

  @Override
  public int hashCode() {
    return Objects.hash(super.hashCode(), version);
  }

  /** Builder is the builder for ClientOptions. */
  public static class Builder extends ServiceStubsOptions.Builder<Builder> {
    private String version;

    private Builder() {
      // Set defaults only in this constructor
      setTarget(DEFAULT_CLOUD_TARGET);
      setEnableHttps(true);
    }

    private Builder(CloudServiceStubsOptions options) {
      super(options);
      this.version = options.version;
    }

    /** Set a cloud operation service version. This sets the version header for each call. */
    public Builder setVersion(String version) {
      this.version = version;
      return this;
    }

    /** Default is {@link #DEFAULT_CLOUD_TARGET}. See inherited method for more details. */
    @Override
    public Builder setTarget(String target) {
      return super.setTarget(target);
    }

    @Override
    public Builder setChannel(ManagedChannel channel) {
      // Unset our defaults
      setEnableHttps(false);
      setTarget(null);
      return super.setChannel(channel);
    }

    /**
     * Builds and returns a ClientOptions object.
     *
     * @return ClientOptions object with the specified params.
     */
    public CloudServiceStubsOptions build() {
      return new CloudServiceStubsOptions(super.build(), this.version);
    }

    public CloudServiceStubsOptions validateAndBuildWithDefaults() {
      ServiceStubsOptions serviceStubsOptions = super.validateAndBuildWithDefaults();
      return new CloudServiceStubsOptions(serviceStubsOptions, this.version);
    }
  }
}
