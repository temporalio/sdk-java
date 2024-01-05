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

package io.temporal.internal.client;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

import io.temporal.client.WorkflowClient;
import io.temporal.worker.BaseWorkerFactory;
import io.temporal.worker.WorkerFactory;
import java.util.Iterator;
import org.junit.Test;

public class WorkerFactoryRegistryTest {
  /**
   * Covers a situation when one {@link WorkflowClient} used to create several WorkerFactories.
   * {@link WorkerFactoryRegistry#workerFactoriesRandomOrder()} will be used to get an iterator over
   * registered WorkerFactories and such an iterator should lead to an even distribution of
   * requests.
   */
  @Test
  public void testRandomOrder() {
    final int TOTAL_COUNT = 3000;

    WorkerFactoryRegistry workerFactoryRegistry = new WorkerFactoryRegistry();
    WorkerFactory workerFactory1 = mock(WorkerFactory.class);
    WorkerFactory workerFactory2 = mock(WorkerFactory.class);
    WorkerFactory workerFactory3 = mock(WorkerFactory.class);

    workerFactoryRegistry.register(workerFactory1);
    workerFactoryRegistry.register(workerFactory2);
    workerFactoryRegistry.register(workerFactory3);

    int firstFactoryFirst = 0;
    int secondFactoryFirst = 0;
    int thirdFactoryFirst = 0;

    for (int i = 0; i < TOTAL_COUNT; i++) {
      Iterable<BaseWorkerFactory> workerFactories =
          workerFactoryRegistry.workerFactoriesRandomOrder();
      Iterator<BaseWorkerFactory> iterator = workerFactories.iterator();
      BaseWorkerFactory first = iterator.next();
      BaseWorkerFactory second = iterator.next();
      BaseWorkerFactory third = iterator.next();

      assertFalse(iterator.hasNext());
      assertNotEquals(first, second);
      assertNotEquals(first, third);
      assertNotEquals(second, third);

      if (first == workerFactory1) {
        firstFactoryFirst++;
      } else if (first == workerFactory2) {
        secondFactoryFirst++;
      } else if (first == workerFactory3) {
        thirdFactoryFirst++;
      } else {
        fail("Unexpected WorkerFactory");
      }
    }

    assertTrue(Math.abs(secondFactoryFirst - firstFactoryFirst) < TOTAL_COUNT * 0.05);
    assertTrue(Math.abs(thirdFactoryFirst - firstFactoryFirst) < TOTAL_COUNT * 0.05);
    assertTrue(Math.abs(thirdFactoryFirst - secondFactoryFirst) < TOTAL_COUNT * 0.05);
  }
}
