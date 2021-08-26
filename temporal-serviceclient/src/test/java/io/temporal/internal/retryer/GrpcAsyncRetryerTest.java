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

package io.temporal.internal.retryer;

import static org.junit.Assert.*;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.serviceclient.RpcRetryOptions;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class GrpcAsyncRetryerTest {

  private static final GrpcAsyncRetryer DEFAULT_ASYNC_RETRYER =
      new GrpcAsyncRetryer(Clock.systemUTC());

  @Test
  public void testExpirationAsync() throws InterruptedException {
    final Status.Code STATUS_CODE = Status.Code.DATA_LOSS;

    RpcRetryOptions options =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(10))
            .setMaximumInterval(Duration.ofMillis(100))
            .setExpiration(Duration.ofMillis(500))
            .validateBuildWithDefaults();
    long start = System.currentTimeMillis();
    try {
      DEFAULT_ASYNC_RETRYER
          .retry(
              options,
              () -> {
                throw new StatusRuntimeException(Status.fromCode(STATUS_CODE));
              })
          .get();
      fail("unreachable");
    } catch (ExecutionException e) {
      assertTrue(e.getCause() instanceof StatusRuntimeException);
      assertEquals(STATUS_CODE, ((StatusRuntimeException) e.getCause()).getStatus().getCode());
    }

    assertTrue(System.currentTimeMillis() - start > 500);
  }

  @Test
  public void testExpirationFutureAsync() throws InterruptedException {
    final Status.Code STATUS_CODE = Status.Code.DATA_LOSS;

    RpcRetryOptions options =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(10))
            .setMaximumInterval(Duration.ofMillis(100))
            .setExpiration(Duration.ofMillis(500))
            .validateBuildWithDefaults();
    long start = System.currentTimeMillis();
    try {
      DEFAULT_ASYNC_RETRYER
          .retry(
              options,
              () -> {
                CompletableFuture<Void> result = new CompletableFuture<>();
                result.completeExceptionally(
                    new StatusRuntimeException(Status.fromCode(STATUS_CODE)));
                return result;
              })
          .get();
      fail("unreachable");
    } catch (ExecutionException e) {
      assertTrue(e.getCause() instanceof StatusRuntimeException);
      assertEquals(STATUS_CODE, ((StatusRuntimeException) e.getCause()).getStatus().getCode());
    }
    assertTrue(System.currentTimeMillis() - start > 500);
  }

  @Test
  public void testDoNotRetryAsync() throws InterruptedException {
    final Status.Code STATUS_CODE = Status.Code.DATA_LOSS;

    RpcRetryOptions options =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(10))
            .setMaximumInterval(Duration.ofMillis(100))
            .addDoNotRetry(STATUS_CODE, null)
            .validateBuildWithDefaults();
    long start = System.currentTimeMillis();
    try {
      DEFAULT_ASYNC_RETRYER
          .retry(
              options,
              () -> {
                CompletableFuture<Void> result = new CompletableFuture<>();
                result.completeExceptionally(
                    new StatusRuntimeException(Status.fromCode(STATUS_CODE)));
                return result;
              })
          .get();
      fail("unreachable");
    } catch (ExecutionException e) {
      assertTrue(e.getCause() instanceof StatusRuntimeException);
      assertEquals(STATUS_CODE, ((StatusRuntimeException) e.getCause()).getStatus().getCode());
    }
    assertTrue(
        "We should fail fast on exception that we specified to don't retry",
        System.currentTimeMillis() - start < 10_000);
  }

  @Test
  public void testInterruptedExceptionAsync() throws InterruptedException {
    RpcRetryOptions options =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(10))
            .setMaximumInterval(Duration.ofMillis(100))
            .validateBuildWithDefaults();
    long start = System.currentTimeMillis();
    try {
      DEFAULT_ASYNC_RETRYER
          .retry(
              options,
              () -> {
                CompletableFuture<Void> result = new CompletableFuture<>();
                result.completeExceptionally(new InterruptedException("simulated"));
                return result;
              })
          .get();
      fail("unreachable");
    } catch (ExecutionException e) {
      assertTrue(e.getCause() instanceof InterruptedException);
      assertEquals("simulated", e.getCause().getMessage());
    }
    assertTrue(
        "We should fail fast on InterruptedException", System.currentTimeMillis() - start < 10_000);
  }

  @Test
  public void testNotStatusRuntimeExceptionAsync() throws InterruptedException {
    RpcRetryOptions options =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(10))
            .setMaximumInterval(Duration.ofMillis(100))
            .validateBuildWithDefaults();
    long start = System.currentTimeMillis();
    try {
      DEFAULT_ASYNC_RETRYER
          .retry(
              options,
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
    assertTrue(
        "If the exception is not StatusRuntimeException - we shouldn't retry",
        System.currentTimeMillis() - start < 10_000);
  }

  @Test
  public void testDeadlineExceededException() throws InterruptedException {
    RpcRetryOptions options =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(10))
            .setMaximumInterval(Duration.ofMillis(100))
            .validateBuildWithDefaults();
    long start = System.currentTimeMillis();
    final AtomicInteger attempts = new AtomicInteger();
    try {
      DEFAULT_ASYNC_RETRYER
          .retry(
              options,
              () -> {
                attempts.incrementAndGet();
                CompletableFuture<?> future = new CompletableFuture<>();
                future.completeExceptionally(
                    new StatusRuntimeException(Status.fromCode(Status.Code.DEADLINE_EXCEEDED)));
                return future;
              })
          .get();
      fail("unreachable");
    } catch (ExecutionException e) {
      assertTrue(e.getCause() instanceof StatusRuntimeException);
      assertEquals(
          Status.Code.DEADLINE_EXCEEDED,
          ((StatusRuntimeException) e.getCause()).getStatus().getCode());
    }
    assertTrue(
        "If the exception is DEADLINE_EXCEEDED, we shouldn't retry",
        System.currentTimeMillis() - start < 2_000);

    assertEquals("If the exception is DEADLINE_EXCEEDED, we shouldn't retry", 1, attempts.get());
  }

  @Test
  public void testDeadlineExceededAfterAnotherException() throws InterruptedException {
    RpcRetryOptions options =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(10))
            .setMaximumInterval(Duration.ofMillis(100))
            .validateBuildWithDefaults();
    long start = System.currentTimeMillis();
    final AtomicInteger attempts = new AtomicInteger();
    try {
      DEFAULT_ASYNC_RETRYER
          .retry(
              options,
              () -> {
                CompletableFuture<?> future = new CompletableFuture<>();
                future.completeExceptionally(
                    new StatusRuntimeException(
                        attempts.incrementAndGet() > 1
                            ? Status.fromCode(Status.Code.DEADLINE_EXCEEDED)
                            : Status.fromCode(Status.Code.DATA_LOSS)));
                return future;
              })
          .get();
      fail("unreachable");
    } catch (ExecutionException e) {
      assertTrue(e.getCause() instanceof StatusRuntimeException);
      assertEquals(
          "We should get a previous exception in case of DEADLINE_EXCEEDED",
          Status.Code.DATA_LOSS,
          ((StatusRuntimeException) e.getCause()).getStatus().getCode());
    }
  }
}
