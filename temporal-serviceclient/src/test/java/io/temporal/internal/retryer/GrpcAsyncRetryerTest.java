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

package io.temporal.internal.retryer;

import static org.junit.Assert.*;

import io.grpc.*;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.serviceclient.RpcRetryOptions;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;

public class GrpcAsyncRetryerTest {

  private static ScheduledExecutorService scheduledExecutor;

  @BeforeClass
  public static void beforeClass() {
    scheduledExecutor = Executors.newScheduledThreadPool(1);
  }

  @AfterClass
  public static void afterClass() {
    scheduledExecutor.shutdownNow();
  }

  @Test
  public void testExpirationAsync() throws InterruptedException {
    final Status.Code STATUS_CODE = Status.Code.DATA_LOSS;

    RpcRetryOptions options =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(10))
            .setMaximumInterval(Duration.ofMillis(100))
            .setExpiration(Duration.ofMillis(500))
            .setMaximumJitterCoefficient(0)
            .validateBuildWithDefaults();
    long start = System.currentTimeMillis();
    final AtomicInteger attempts = new AtomicInteger();
    try {
      GrpcAsyncRetryer.retry(
              scheduledExecutor,
              GetSystemInfoResponse.Capabilities::getDefaultInstance,
              () -> {
                attempts.incrementAndGet();
                throw new StatusRuntimeException(Status.fromCode(STATUS_CODE));
              },
              new GrpcRetryer.GrpcRetryerOptions(options, null))
          .get();
      fail("unreachable");
    } catch (ExecutionException e) {
      assertTrue(e.getCause() instanceof StatusRuntimeException);
      assertEquals(STATUS_CODE, ((StatusRuntimeException) e.getCause()).getStatus().getCode());
    }

    assertTrue("Should retry on DATA_LOSS failures.", attempts.get() > 1);
    assertTrue(System.currentTimeMillis() - start >= 500);
  }

  @Test
  public void testExpirationButGetSystemInfoThrowsAsync() throws InterruptedException {
    final Status.Code STATUS_CODE = Status.Code.DATA_LOSS;

    RpcRetryOptions options =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(10))
            .setMaximumInterval(Duration.ofMillis(100))
            .setExpiration(Duration.ofMillis(500))
            .setMaximumJitterCoefficient(0)
            .validateBuildWithDefaults();
    long start = System.currentTimeMillis();
    final AtomicInteger attempts = new AtomicInteger();
    try {
      GrpcAsyncRetryer.retry(
              scheduledExecutor,
              () -> {
                throw new StatusRuntimeException(Status.UNAVAILABLE);
              },
              () -> {
                attempts.incrementAndGet();
                throw new StatusRuntimeException(Status.fromCode(STATUS_CODE));
              },
              new GrpcRetryer.GrpcRetryerOptions(options, null))
          .get();
      fail("unreachable");
    } catch (ExecutionException e) {
      assertTrue(e.getCause() instanceof StatusRuntimeException);
      assertEquals(STATUS_CODE, ((StatusRuntimeException) e.getCause()).getStatus().getCode());
    }

    assertTrue("Should retry on DATA_LOSS failures.", attempts.get() > 1);
    assertTrue(System.currentTimeMillis() - start >= 500);
  }

  @Test
  public void testExpirationFutureAsync() throws InterruptedException {
    final Status.Code STATUS_CODE = Status.Code.DATA_LOSS;

    RpcRetryOptions options =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(10))
            .setMaximumInterval(Duration.ofMillis(100))
            .setExpiration(Duration.ofMillis(500))
            .setMaximumJitterCoefficient(0)
            .validateBuildWithDefaults();
    long start = System.currentTimeMillis();
    final AtomicInteger attempts = new AtomicInteger();
    try {
      GrpcAsyncRetryer.retry(
              scheduledExecutor,
              GetSystemInfoResponse.Capabilities::getDefaultInstance,
              () -> {
                attempts.incrementAndGet();
                CompletableFuture<Void> result = new CompletableFuture<>();
                result.completeExceptionally(
                    new StatusRuntimeException(Status.fromCode(STATUS_CODE)));
                return result;
              },
              new GrpcRetryer.GrpcRetryerOptions(options, null))
          .get();
      fail("unreachable");
    } catch (ExecutionException e) {
      assertTrue(e.getCause() instanceof StatusRuntimeException);
      assertEquals(STATUS_CODE, ((StatusRuntimeException) e.getCause()).getStatus().getCode());
    }

    assertTrue("Should retry on DATA_LOSS failures.", attempts.get() > 1);
    assertTrue(System.currentTimeMillis() - start >= 500);
  }

  @Test
  public void testDoNotRetryAsync() throws InterruptedException {
    final Status.Code STATUS_CODE = Status.Code.DATA_LOSS;

    RpcRetryOptions options =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(1000))
            .setMaximumInterval(Duration.ofMillis(1000))
            .setMaximumJitterCoefficient(0)
            .addDoNotRetry(STATUS_CODE, null)
            .validateBuildWithDefaults();
    long start = System.currentTimeMillis();
    final AtomicInteger attempts = new AtomicInteger();
    try {
      GrpcAsyncRetryer.retry(
              scheduledExecutor,
              GetSystemInfoResponse.Capabilities::getDefaultInstance,
              () -> {
                if (attempts.incrementAndGet() > 1)
                  fail("We should not retry on exception that we specified to don't retry");
                CompletableFuture<Void> result = new CompletableFuture<>();
                result.completeExceptionally(
                    new StatusRuntimeException(Status.fromCode(STATUS_CODE)));
                return result;
              },
              new GrpcRetryer.GrpcRetryerOptions(options, null))
          .get();
      fail("unreachable");
    } catch (ExecutionException e) {
      assertTrue(e.getCause() instanceof StatusRuntimeException);
      assertEquals(STATUS_CODE, ((StatusRuntimeException) e.getCause()).getStatus().getCode());
    }
    assertTrue(
        "We should fail fast on exception that we specified to don't retry",
        System.currentTimeMillis() - start < 1000);
  }

  @Test
  public void testInterruptedExceptionAsync() throws InterruptedException {
    RpcRetryOptions options =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(1000))
            .setMaximumInterval(Duration.ofMillis(1000))
            .setMaximumJitterCoefficient(0)
            .validateBuildWithDefaults();
    long start = System.currentTimeMillis();
    final AtomicInteger attempts = new AtomicInteger();
    try {
      GrpcAsyncRetryer.retry(
              scheduledExecutor,
              GetSystemInfoResponse.Capabilities::getDefaultInstance,
              () -> {
                if (attempts.incrementAndGet() > 1)
                  fail("We should not retry on InterruptedException");
                CompletableFuture<Void> result = new CompletableFuture<>();
                result.completeExceptionally(new InterruptedException("simulated"));
                return result;
              },
              new GrpcRetryer.GrpcRetryerOptions(options, null))
          .get();
      fail("unreachable");
    } catch (ExecutionException e) {
      assertTrue(e.getCause() instanceof InterruptedException);
      assertEquals("simulated", e.getCause().getMessage());
    }
    assertTrue(
        "We should fail fast on InterruptedException", System.currentTimeMillis() - start < 1000);
  }

  @Test
  public void testNotStatusRuntimeExceptionAsync() throws InterruptedException {
    RpcRetryOptions options =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(1000))
            .setMaximumInterval(Duration.ofMillis(1000))
            .setMaximumJitterCoefficient(0)
            .validateBuildWithDefaults();
    long start = System.currentTimeMillis();
    final AtomicInteger attempts = new AtomicInteger();
    try {
      GrpcAsyncRetryer.retry(
              scheduledExecutor,
              GetSystemInfoResponse.Capabilities::getDefaultInstance,
              () -> {
                if (attempts.incrementAndGet() > 1)
                  fail("We should not retry if the exception is not StatusRuntimeException");
                CompletableFuture<Void> result = new CompletableFuture<>();
                result.completeExceptionally(new IllegalArgumentException("simulated"));
                return result;
              },
              new GrpcRetryer.GrpcRetryerOptions(options, null))
          .get();
      fail("unreachable");
    } catch (ExecutionException e) {
      assertTrue(e.getCause() instanceof IllegalArgumentException);
      assertEquals("simulated", e.getCause().getMessage());
    }
    assertTrue(
        "If the exception is not StatusRuntimeException - we shouldn't retry",
        System.currentTimeMillis() - start < 1000);
  }

  @Test
  public void testRetryDeadlineExceededException() {
    RpcRetryOptions options =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(100))
            .setMaximumInterval(Duration.ofMillis(100))
            .setExpiration(Duration.ofMillis(500))
            .setMaximumJitterCoefficient(0)
            .validateBuildWithDefaults();
    long start = System.currentTimeMillis();
    final AtomicInteger attempts = new AtomicInteger();

    StatusRuntimeException e =
        assertThrows(
            StatusRuntimeException.class,
            () -> {
              try {
                GrpcAsyncRetryer.retry(
                        scheduledExecutor,
                        GetSystemInfoResponse.Capabilities::getDefaultInstance,
                        () -> {
                          attempts.incrementAndGet();
                          CompletableFuture<?> future = new CompletableFuture<>();
                          future.completeExceptionally(
                              new StatusRuntimeException(
                                  Status.fromCode(Status.Code.DEADLINE_EXCEEDED)));
                          return future;
                        },
                        new GrpcRetryer.GrpcRetryerOptions(options, null))
                    .get();
              } catch (ExecutionException ex) {
                throw ex.getCause();
              }
            });

    assertEquals(Status.Code.DEADLINE_EXCEEDED, e.getStatus().getCode());

    assertTrue(
        "We should retry DEADLINE_EXCEEDED if global Grpc Deadline, attempts, time are not exhausted.",
        System.currentTimeMillis() - start >= 500);

    assertTrue(
        "We should retry DEADLINE_EXCEEDED if global Grpc Deadline, attempts, time are not exhausted.",
        attempts.get() > 2);
  }

  @Test
  public void testRespectGlobalDeadlineExceeded() {
    RpcRetryOptions options =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(100))
            .setMaximumInterval(Duration.ofMillis(100))
            .setExpiration(Duration.ofMillis(50000))
            .setMaximumJitterCoefficient(0)
            .validateBuildWithDefaults();
    long start = System.currentTimeMillis();
    final AtomicInteger attempts = new AtomicInteger();
    final AtomicReference<StatusRuntimeException> exception = new AtomicReference<>();

    Context.current()
        .withDeadlineAfter(500, TimeUnit.MILLISECONDS, scheduledExecutor)
        .run(
            () ->
                exception.set(
                    assertThrows(
                        StatusRuntimeException.class,
                        () -> {
                          try {
                            GrpcAsyncRetryer.retry(
                                    scheduledExecutor,
                                    GetSystemInfoResponse.Capabilities::getDefaultInstance,
                                    () -> {
                                      attempts.incrementAndGet();
                                      CompletableFuture<?> future = new CompletableFuture<>();
                                      future.completeExceptionally(
                                          new StatusRuntimeException(
                                              Status.fromCode(Status.Code.DATA_LOSS)));
                                      return future;
                                    },
                                    new GrpcRetryer.GrpcRetryerOptions(options, null))
                                .get();
                          } catch (ExecutionException e) {
                            throw e.getCause();
                          }
                        })));

    assertEquals(Status.Code.DATA_LOSS, exception.get().getStatus().getCode());
    assertTrue(
        "We shouldn't retry after gRPC deadline from the context is expired",
        System.currentTimeMillis() - start < 10_000);

    assertTrue(
        "We shouldn't retry after gRPC deadline from the context is expired", attempts.get() < 8);
  }

  @Test
  public void testGlobalDeadlineExceededAfterAnotherException() {
    RpcRetryOptions options =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(100))
            .setMaximumInterval(Duration.ofMillis(100))
            .setMaximumJitterCoefficient(0)
            .validateBuildWithDefaults();

    final AtomicReference<StatusRuntimeException> exception = new AtomicReference<>();

    Context.current()
        .withDeadlineAfter(500, TimeUnit.MILLISECONDS, scheduledExecutor)
        .run(
            () ->
                exception.set(
                    assertThrows(
                        StatusRuntimeException.class,
                        () -> {
                          try {
                            GrpcAsyncRetryer.retry(
                                    scheduledExecutor,
                                    GetSystemInfoResponse.Capabilities::getDefaultInstance,
                                    () -> {
                                      if (Context.current().getDeadline().isExpired()) {
                                        throw new StatusRuntimeException(
                                            Status.fromCode(Status.Code.DEADLINE_EXCEEDED));
                                      } else {
                                        throw new StatusRuntimeException(
                                            Status.fromCode(Status.Code.DATA_LOSS));
                                      }
                                    },
                                    new GrpcRetryer.GrpcRetryerOptions(options, null))
                                .get();
                          } catch (ExecutionException e) {
                            throw e.getCause();
                          }
                        })));

    assertEquals(
        "We should get a previous exception in case of DEADLINE_EXCEEDED",
        Status.Code.DATA_LOSS,
        exception.get().getStatus().getCode());
  }

  @Test
  public void testResourceExhaustedFailure() throws InterruptedException {
    RpcRetryOptions options =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(1))
            .setCongestionInitialInterval(Duration.ofMillis(1000))
            .setMaximumInterval(Duration.ofMillis(1000))
            .setMaximumJitterCoefficient(0)
            .setMaximumAttempts(3)
            .validateBuildWithDefaults();
    long start = System.currentTimeMillis();
    final AtomicInteger attempts = new AtomicInteger();

    try {
      GrpcAsyncRetryer.retry(
              scheduledExecutor,
              GetSystemInfoResponse.Capabilities::getDefaultInstance,
              () -> {
                attempts.incrementAndGet();
                CompletableFuture<?> future = new CompletableFuture<>();
                future.completeExceptionally(
                    new StatusRuntimeException(Status.fromCode(Status.Code.RESOURCE_EXHAUSTED)));
                return future;
              },
              new GrpcRetryer.GrpcRetryerOptions(options, null))
          .get();
      fail("unreachable");
    } catch (ExecutionException e) {
      assertTrue(e.getCause() instanceof StatusRuntimeException);
      assertEquals(
          Status.Code.RESOURCE_EXHAUSTED,
          ((StatusRuntimeException) e.getCause()).getStatus().getCode());
    }

    long elapsedTime = System.currentTimeMillis() - start;
    assertTrue("We should retry RESOURCE_EXHAUSTED failures.", attempts.get() > 2);
    assertTrue(
        "We should retry RESOURCE_EXHAUSTED failures using congestionInitialInterval.",
        elapsedTime >= 2000);
  }
}
