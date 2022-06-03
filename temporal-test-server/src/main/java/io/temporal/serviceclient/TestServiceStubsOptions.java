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

public final class TestServiceStubsOptions extends ServiceStubsOptions {
  private static final TestServiceStubsOptions DEFAULT_INSTANCE =
      newBuilder().validateAndBuildWithDefaults();

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(ServiceStubsOptions options) {
    return new Builder(options);
  }

  public static TestServiceStubsOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private TestServiceStubsOptions(ServiceStubsOptions serviceStubsOptions) {
    super(serviceStubsOptions);
  }

  /** Builder is the builder for ClientOptions. */
  public static class Builder extends ServiceStubsOptions.Builder<Builder> {

    private Builder() {}

    private Builder(ServiceStubsOptions options) {
      super(options);
    }

    /**
     * Builds and returns a ClientOptions object.
     *
     * @return ClientOptions object with the specified params.
     */
    public TestServiceStubsOptions build() {
      return new TestServiceStubsOptions(super.build());
    }

    public TestServiceStubsOptions validateAndBuildWithDefaults() {
      ServiceStubsOptions serviceStubsOptions = super.validateAndBuildWithDefaults();
      return new TestServiceStubsOptions(serviceStubsOptions);
    }
  }
}
