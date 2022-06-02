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

package io.temporal.internal.testservice;

import io.grpc.BindableService;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.protobuf.services.HealthStatusManager;
import java.util.Collection;

// TODO move to temporal-testing or temporal-test-server modules after WorkflowServiceStubs cleanup
public class GRPCServerHelper {
  public static void registerServicesAndHealthChecks(
      Collection<BindableService> services, ServerBuilder<?> toServerBuilder) {
    HealthStatusManager healthStatusManager = new HealthStatusManager();
    for (BindableService service : services) {
      ServerServiceDefinition serverServiceDefinition = service.bindService();
      toServerBuilder.addService(serverServiceDefinition);
      healthStatusManager.setStatus(
          service.bindService().getServiceDescriptor().getName(),
          HealthCheckResponse.ServingStatus.SERVING);
    }
    toServerBuilder.addService(healthStatusManager.getHealthService());
  }
}
