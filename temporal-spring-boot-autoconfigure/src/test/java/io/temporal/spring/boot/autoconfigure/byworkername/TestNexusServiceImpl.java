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

package io.temporal.spring.boot.autoconfigure.byworkername;

import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.spring.boot.NexusServiceImpl;
import io.temporal.spring.boot.autoconfigure.bytaskqueue.TestNexusService;
import org.springframework.stereotype.Component;

@Component("TestNexusServiceImpl")
@NexusServiceImpl(workers = "mainWorker")
@ServiceImpl(service = TestNexusService.class)
public class TestNexusServiceImpl {

  @OperationImpl
  public OperationHandler<String, String> sayHello1() {
    // Implemented inline
    return OperationHandler.sync((ctx, details, name) -> "Hello, " + name + "!");
  }

  @OperationImpl
  public OperationHandler<String, String> sayHello2() {
    // Implemented via handler
    return OperationHandler.sync((ctx, details, name) -> "Hello, " + name + "!");
  }
}
