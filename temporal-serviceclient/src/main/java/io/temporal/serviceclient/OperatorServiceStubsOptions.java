/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
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

package io.temporal.serviceclient;

public final class OperatorServiceStubsOptions extends ServiceStubsOptions {
  private static final OperatorServiceStubsOptions DEFAULT_INSTANCE =
      newBuilder().validateAndBuildWithDefaults();

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(OperatorServiceStubsOptions options) {
    return new Builder(options);
  }

  public static Builder newBuilder(ServiceStubsOptions options) {
    return options instanceof OperatorServiceStubsOptions
        ? newBuilder((OperatorServiceStubsOptions) options)
        : new Builder(options);
  }

  public static OperatorServiceStubsOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private OperatorServiceStubsOptions(ServiceStubsOptions serviceStubsOptions) {
    super(serviceStubsOptions);
  }

  /** Builder is the builder for ClientOptions. */
  public static class Builder extends ServiceStubsOptions.Builder<Builder> {

    private Builder() {}

    private Builder(ServiceStubsOptions options) {
      super(options);
    }

    private Builder(OperatorServiceStubsOptions options) {
      super(options);
    }

    /**
     * Builds and returns a ClientOptions object.
     *
     * @return ClientOptions object with the specified params.
     */
    public OperatorServiceStubsOptions build() {
      return new OperatorServiceStubsOptions(super.build());
    }

    public OperatorServiceStubsOptions validateAndBuildWithDefaults() {
      ServiceStubsOptions serviceStubsOptions = super.validateAndBuildWithDefaults();
      return new OperatorServiceStubsOptions(serviceStubsOptions);
    }
  }
}
