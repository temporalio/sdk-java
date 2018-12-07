/*
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

package com.uber.cadence.workerFactory;

import static org.junit.Assert.assertTrue;

import com.uber.cadence.worker.Worker;
import java.time.Duration;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

public class WorkerFactoryTests {

  private static final boolean skipDockerService =
      Boolean.parseBoolean(System.getenv("SKIP_DOCKER_SERVICE"));

  @BeforeClass
  public static void beforeClass() throws Exception {
    Assume.assumeTrue(!skipDockerService);
  }

  @Test
  public void whenAFactoryIsStartedAllWorkersStart() {
    Worker.Factory factory = new Worker.Factory("domain");
    Worker worker1 = factory.newWorker("task1");
    Worker worker2 = factory.newWorker("task2");

    factory.start();
    assertTrue(worker1.isStarted());
    assertTrue(worker2.isStarted());
    factory.shutdown(Duration.ofSeconds(1));
  }

  @Test
  public void whenAFactoryIsShutdownAllWorkersAreShutdown() {
    Worker.Factory factory = new Worker.Factory("domain");
    Worker worker1 = factory.newWorker("task1");
    Worker worker2 = factory.newWorker("task2");

    factory.start();
    factory.shutdown(Duration.ofMillis(1));

    assertTrue(worker1.isClosed());
    assertTrue(worker2.isClosed());
    factory.shutdown(Duration.ofSeconds(1));
  }

  @Test
  public void aFactoryCanBeStartedMoreThanOnce() {
    Worker.Factory factory = new Worker.Factory("domain");

    factory.start();
    factory.start();
    factory.shutdown(Duration.ofSeconds(1));
  }

  @Test(expected = IllegalStateException.class)
  public void aFactoryCannotBeStartedAfterShutdown() {
    Worker.Factory factory = new Worker.Factory("domain");
    Worker worker1 = factory.newWorker("task1");

    factory.shutdown(Duration.ofMillis(1));
    factory.start();
  }

  @Test(expected = IllegalStateException.class)
  public void workersCannotBeCreatedAfterFactoryHasStarted() {
    Worker.Factory factory = new Worker.Factory("domain");
    Worker worker1 = factory.newWorker("task1");

    factory.start();

    try {
      Worker worker2 = factory.newWorker("task2");
    } finally {
      factory.shutdown(Duration.ofSeconds(1));
    }
  }

  @Test(expected = IllegalStateException.class)
  public void workersCannotBeCreatedAfterFactoryIsShutdown() {
    Worker.Factory factory = new Worker.Factory("domain");
    Worker worker1 = factory.newWorker("task1");

    factory.shutdown(Duration.ofMillis(1));
    try {
      Worker worker2 = factory.newWorker("task2");
    } finally {
      factory.shutdown(Duration.ofSeconds(1));
    }
  }

  @Test
  public void factoryCanOnlyBeShutdownMoreThanOnce() {
    Worker.Factory factory = new Worker.Factory("domain");
    Worker worker1 = factory.newWorker("task1");

    factory.shutdown(Duration.ofMillis(1));
    factory.shutdown(Duration.ofMillis(1));
  }
}
