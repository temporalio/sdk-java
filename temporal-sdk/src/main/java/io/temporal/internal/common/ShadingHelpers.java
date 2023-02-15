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

package io.temporal.internal.common;

import io.temporal.serviceclient.ServiceStubsOptions;
import io.temporal.serviceclient.SimpleSslContextBuilder;
import javax.net.ssl.SSLException;

/**
 * This class provides shading-safe utils that don't return classes that we relocate during shading
 * to the caller. These tools help modules like spring boot stay not shaded and still be compatible
 * with our shaded artifact
 */
public final class ShadingHelpers {
  private ShadingHelpers() {}

  public static void buildSslContextAndPublishIntoStubOptions(
      SimpleSslContextBuilder sslContextBuilder, ServiceStubsOptions.Builder<?> optionsBuilder)
      throws SSLException {
    optionsBuilder.setSslContext(sslContextBuilder.build());
  }
}
