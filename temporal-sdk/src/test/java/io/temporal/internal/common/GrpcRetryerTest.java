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

import static org.junit.Assert.*;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.serviceclient.CheckedExceptionWrapper;
import io.temporal.serviceclient.GrpcRetryer;
import io.temporal.serviceclient.RpcRetryOptions;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.Test;

public class GrpcRetryerTest {

  @Test
  public void testExpiration() {
    final Status.Code STATUS_CODE = Status.Code.DATA_LOSS;

    RpcRetryOptions options =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(10))
            .setMaximumInterval(Duration.ofMillis(100))
            .setExpiration(Duration.ofMillis(500))
            .validateBuildWithDefaults();
    long start = System.currentTimeMillis();
    try {
      GrpcRetryer.retryWithResult(
          options,
          () -> {
            throw new StatusRuntimeException(Status.fromCode(STATUS_CODE));
          });
      fail("unreachable");
    } catch (Exception e) {
      assertTrue(e instanceof StatusRuntimeException);
      assertEquals(STATUS_CODE, ((StatusRuntimeException) e).getStatus().getCode());
    }

    assertTrue(System.currentTimeMillis() - start > 500);
  }

  @Test
  public void testDoNotRetry() {
    final Status.Code STATUS_CODE = Status.Code.DATA_LOSS;

    RpcRetryOptions options =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(10))
            .setMaximumInterval(Duration.ofMillis(100))
            .addDoNotRetry(STATUS_CODE, null)
            .validateBuildWithDefaults();
    long start = System.currentTimeMillis();
    try {
      GrpcRetryer.retryWithResult(
          options,
          () -> {
            throw new StatusRuntimeException(Status.fromCode(STATUS_CODE));
          });
      fail("unreachable");
    } catch (Exception e) {
      assertTrue(e instanceof StatusRuntimeException);
      assertEquals(STATUS_CODE, ((StatusRuntimeException) e).getStatus().getCode());
    }
    assertTrue(
        "We should fail fast on exception that we specified to don't retry",
        System.currentTimeMillis() - start < 10_000);
  }

  @Test
  public void testInterruptedException() {
    RpcRetryOptions options =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(10))
            .setMaximumInterval(Duration.ofMillis(100))
            .validateBuildWithDefaults();
    long start = System.currentTimeMillis();
    try {
      GrpcRetryer.retryWithResult(
          options,
          () -> {
            Thread.currentThread().interrupt();
            throw new InterruptedException();
          });
      fail("unreachable");
    } catch (Exception e) {
      // I'm not sure it's a good idea to replace interrupted exception with CancellationException,
      // especially when async version of this method doesn't shadow the InterruptedException
      // @see #testInterruptedExceptionAsync
      assertTrue(e instanceof CancellationException);
    }
    assertTrue(
        "We should fail fast on InterruptedException", System.currentTimeMillis() - start < 10_000);
  }

  @Test
  public void testNotStatusRuntimeException() {
    RpcRetryOptions options =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(10))
            .setMaximumInterval(Duration.ofMillis(100))
            .validateBuildWithDefaults();
    long start = System.currentTimeMillis();
    try {
      GrpcRetryer.retryWithResult(
          options,
          () -> {
            throw new IllegalArgumentException("simulated");
          });
      fail("unreachable");
    } catch (Exception e) {
      assertTrue(e instanceof IllegalArgumentException);
      assertEquals("simulated", e.getMessage());
    }
    assertTrue(
        "If the exception is not StatusRuntimeException - we shouldn't retry",
        System.currentTimeMillis() - start < 10_000);
  }

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
      GrpcRetryer.retryWithResultAsync(
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
      GrpcRetryer.retryWithResultAsync(
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
      GrpcRetryer.retryWithResultAsync(
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
      GrpcRetryer.retryWithResultAsync(
              options,
              () -> {
                CompletableFuture<Void> result = new CompletableFuture<>();
                Thread.currentThread().interrupt();
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
      GrpcRetryer.retryWithResultAsync(
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
}
