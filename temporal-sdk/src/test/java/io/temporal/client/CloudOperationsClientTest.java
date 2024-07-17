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

package io.temporal.client;

import io.temporal.api.cloud.cloudservice.v1.GetNamespaceRequest;
import io.temporal.api.cloud.cloudservice.v1.GetNamespaceResponse;
import io.temporal.serviceclient.CloudServiceStubs;
import io.temporal.serviceclient.CloudServiceStubsOptions;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

public class CloudOperationsClientTest {
  private String namespace;
  private String apiKey;
  private String apiVersion;

  @Before
  public void checkCloudEnvVars() {
    namespace = System.getenv("TEMPORAL_CLIENT_CLOUD_NAMESPACE");
    apiKey = System.getenv("TEMPORAL_CLIENT_CLOUD_API_KEY");
    apiVersion = System.getenv("TEMPORAL_CLIENT_CLOUD_API_VERSION");
    Assume.assumeTrue(
        "Cloud environment variables not present", namespace != null && apiKey != null);
  }

  @Test
  public void simpleCall() {
    CloudOperationsClient client =
        CloudOperationsClient.newInstance(
            CloudServiceStubs.newServiceStubs(
                CloudServiceStubsOptions.newBuilder()
                    .addApiKey(() -> apiKey)
                    .setVersion(apiVersion)
                    .build()));
    // Do simple get namespace call
    GetNamespaceResponse resp =
        client
            .getCloudServiceStubs()
            .blockingStub()
            .getNamespace(GetNamespaceRequest.newBuilder().setNamespace(namespace).build());
    Assert.assertEquals(namespace, resp.getNamespace().getNamespace());
  }
}
