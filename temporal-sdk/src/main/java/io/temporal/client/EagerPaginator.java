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

import com.google.protobuf.ByteString;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nonnull;
import org.slf4j.LoggerFactory;

/**
 * The main goal for this Iterator implementation is to abstract the functionality of "eager
 * pagination". This implementation requests the next page when we start iterating through the
 * previous page. The main goal of this approach is to reduce a synchronous wait that would
 * otherwise happen when a first element of the next page is requested.
 */
abstract class EagerPaginator<Resp, T> implements Iterator<T> {
  private List<T> activeResponse;
  private int nextActiveResponseIndex;
  private CompletableFuture<Resp> nextResponse;

  @Override
  public boolean hasNext() {
    if (nextActiveResponseIndex < activeResponse.size()) {
      return true;
    }
    fetch();
    return nextActiveResponseIndex < activeResponse.size();
  }

  @Override
  public T next() {
    if (hasNext()) {
      return activeResponse.get(nextActiveResponseIndex++);
    } else {
      throw new NoSuchElementException();
    }
  }

  void fetch() {
    if (nextResponse == null) {
      // if nextResponse is null, it's the end of the iteration through the pages
      return;
    }

    Resp response = waitAndGetNextResponse();

    ByteString nextPageToken = getNextPageToken(response);
    if (nextPageToken != null && !nextPageToken.isEmpty()) {
      this.nextResponse = performRequest(nextPageToken);
    } else {
      this.nextResponse = null;
    }

    List<T> responseElements = toElements(response);

    if (responseElements.size() == 0 && nextResponse != null) {
      LoggerFactory.getLogger(this.getClass())
          .warn("[BUG] iterator received an empty collection with a non-empty nextPageToken");
      // shouldn't be happening, but we want to tolerate it if it does, so we just effectively
      // skip the empty response and wait for the next one in a blocking manner.
      // If this actually ever happens as a normal scenario, this skipping should be reworked to
      // be done asynchronously on a completion of nextResponse future.
      fetch();
      return;
    }

    activeResponse = responseElements;
    nextActiveResponseIndex = 0;
  }

  public void init() {
    nextResponse = performRequest(ByteString.empty());
    waitAndGetNextResponse();
    fetch();
  }

  private Resp waitAndGetNextResponse() {
    Resp response;
    try {
      response = this.nextResponse.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      throw (cause instanceof RuntimeException
          ? (RuntimeException) cause
          : new RuntimeException(cause));
    }
    return response;
  }

  abstract CompletableFuture<Resp> performRequest(@Nonnull ByteString nextPageToken);

  abstract ByteString getNextPageToken(Resp response);

  abstract List<T> toElements(Resp response);
}
