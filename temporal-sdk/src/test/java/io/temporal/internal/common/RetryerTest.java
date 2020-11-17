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

package io.temporal.internal.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.temporal.common.RetryOptions;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.Test;

public class RetryerTest {

  @Test
  public void testExpiration() throws InterruptedException {
    RetryOptions options =
        RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(10))
            .setMaximumInterval(Duration.ofMillis(100))
            .validateBuildWithDefaults();
    long start = System.currentTimeMillis();
    try {
      Retryer.retryWithResultAsync(
              options,
              Optional.of(Duration.ofMillis(500)),
              () -> {
                throw new IllegalArgumentException("simulated");
              })
          .get();
      fail("unreachable");
    } catch (ExecutionException e) {
      assertTrue(e.getCause() instanceof IllegalArgumentException);
      assertEquals("simulated", e.getCause().getMessage());
    }
    assertTrue(System.currentTimeMillis() - start > 500);
  }

  @Test
  public void testExpirationFuture() throws InterruptedException {
    RetryOptions options =
        RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(10))
            .setMaximumInterval(Duration.ofMillis(100))
            .validateBuildWithDefaults();
    long start = System.currentTimeMillis();
    try {
      Retryer.retryWithResultAsync(
              options,
              Optional.of(Duration.ofMillis(500)),
              () -> {
                CompletableFuture<Void> result = new CompletableFuture<>();
                result.completeExceptionally(new IllegalArgumentException("simulated"));
                return result;
              })
          .get();
      fail("unreachable");
    } catch (ExecutionException e) {
      assertTrue(e.getCause() instanceof IllegalArgumentException);
      assertEquals("simulated", e.getCause().getMessage());
    }
    assertTrue(System.currentTimeMillis() - start > 500);
  }

  @Test
  public void testInterruptedException() throws InterruptedException {
    RetryOptions options =
        RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(10))
            .setMaximumInterval(Duration.ofMillis(100))
            .setDoNotRetry(InterruptedException.class.getName())
            .validateBuildWithDefaults();
    long start = System.currentTimeMillis();
    try {
      Retryer.retryWithResultAsync(
              options,
              Optional.of(Duration.ofMillis(100)),
              () -> {
                CompletableFuture<Void> result = new CompletableFuture<>();
                result.completeExceptionally(new InterruptedException("simulated"));
                return result;
              })
          .get();
      fail("unreachable");
    } catch (ExecutionException e) {
      assertTrue(e.getCause() instanceof CheckedExceptionWrapper);
      assertTrue(e.getCause().getCause() instanceof InterruptedException);
      assertEquals("simulated", e.getCause().getCause().getMessage());
    }
    assertTrue(System.currentTimeMillis() - start < 100000);
  }
}
