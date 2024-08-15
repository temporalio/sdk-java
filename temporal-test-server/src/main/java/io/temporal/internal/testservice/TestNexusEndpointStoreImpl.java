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

import io.grpc.Status;
import io.temporal.api.nexus.v1.Endpoint;
import io.temporal.api.nexus.v1.EndpointSpec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * TestNexusEndpointStoreImpl is an in-memory implementation of Nexus endpoint CRUD operations for
 * use with the test server. Because conflict resolution is not required, there is no handling for
 * created or updated timestamps.
 */
public class TestNexusEndpointStoreImpl implements TestNexusEndpointStore {

  private static final Pattern ENDPOINT_NAME_REGEX =
      Pattern.compile("^[a-zA-Z][a-zA-Z0-9\\-]*[a-zA-Z0-9]$");

  private final SortedMap<String, Endpoint> endpointsById = new ConcurrentSkipListMap<>();
  private final ConcurrentMap<String, Endpoint> endpointsByName = new ConcurrentHashMap<>();

  @Override
  public Endpoint createEndpoint(EndpointSpec spec) {
    validateEndpointSpec(spec);

    String id = UUID.randomUUID().toString();
    Endpoint endpoint = Endpoint.newBuilder().setId(id).setVersion(1).setSpec(spec).build();

    if (endpointsByName.putIfAbsent(spec.getName(), endpoint) != null) {
      throw Status.ALREADY_EXISTS
          .withDescription("Nexus endpoint already registered with name: " + spec.getName())
          .asRuntimeException();
    }

    if (endpointsById.putIfAbsent(id, endpoint) != null) {
      // This should never happen in practice
      throw Status.ALREADY_EXISTS
          .withDescription("Nexus endpoint already exists with ID: " + id)
          .asRuntimeException();
    }

    return endpoint;
  }

  @Override
  public Endpoint updateEndpoint(String id, long version, EndpointSpec spec) {
    validateEndpointSpec(spec);

    Endpoint prev = endpointsById.get(id);

    if (prev == null) {
      throw Status.NOT_FOUND
          .withDescription("Could not find Nexus endpoint with ID: " + id)
          .asRuntimeException();
    }

    if (prev.getVersion() != version) {
      throw Status.INVALID_ARGUMENT
          .withDescription(
              "Error updating Nexus endpoint: version mismatch."
                  + " Expected: "
                  + prev.getVersion()
                  + " Received: "
                  + version)
          .asRuntimeException();
    }

    Endpoint updated = Endpoint.newBuilder(prev).setVersion(version + 1).setSpec(spec).build();

    if (!prev.getSpec().getName().equals(spec.getName())
        && endpointsByName.putIfAbsent(spec.getName(), updated) != null) {
      throw Status.ALREADY_EXISTS
          .withDescription(
              "Error updating Nexus endpoint: "
                  + "endpoint already registered with updated name: "
                  + spec.getName())
          .asRuntimeException();
    } else {
      endpointsByName.remove(prev.getSpec().getName());
    }

    endpointsById.put(id, updated);
    return updated;
  }

  @Override
  public void deleteEndpoint(String id, long version) {
    Endpoint existing = endpointsById.get(id);

    if (existing == null) {
      throw Status.NOT_FOUND
          .withDescription("Could not find Nexus endpoint with ID: " + id)
          .asRuntimeException();
    }

    if (existing.getVersion() != version) {
      throw Status.INVALID_ARGUMENT
          .withDescription(
              "Error deleting Nexus endpoint: version mismatch."
                  + " Expected "
                  + existing.getVersion()
                  + " Received: "
                  + version)
          .asRuntimeException();
    }

    endpointsById.remove(id);
    endpointsByName.remove(existing.getSpec().getName());
  }

  @Override
  public Endpoint getEndpoint(String id) {
    Endpoint endpoint = endpointsById.get(id);
    if (endpoint == null) {
      throw Status.NOT_FOUND
          .withDescription("Could not find Nexus endpoint with ID: " + id)
          .asRuntimeException();
    }
    return endpoint;
  }

  @Override
  public Endpoint getEndpointByName(String name) {
    Endpoint endpoint = endpointsByName.get(name);
    if (endpoint == null) {
      throw Status.NOT_FOUND
          .withDescription("Could not find Nexus endpoint with name: " + name)
          .asRuntimeException();
    }
    return endpoint;
  }

  @Override
  public List<Endpoint> listEndpoints(long pageSize, byte[] nextPageToken, String name) {
    if (name != null && !name.isEmpty()) {
      return endpointsById.values().stream()
          .filter(ep -> ep.getSpec().getName().equals(name))
          .limit(1)
          .collect(Collectors.toList());
    }

    if (nextPageToken.length > 0) {
      return endpointsById.tailMap(new String(nextPageToken)).values().stream()
          .skip(1)
          .limit(pageSize)
          .collect(Collectors.toList());
    }
    return endpointsById.values().stream().limit(pageSize).collect(Collectors.toList());
  }

  @Override
  public void validateEndpointSpec(EndpointSpec spec) {
    if (spec.getName().isEmpty()) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Nexus endpoint name cannot be empty")
          .asRuntimeException();
    }
    if (!ENDPOINT_NAME_REGEX.matcher(spec.getName()).matches()) {
      throw Status.INVALID_ARGUMENT
          .withDescription(
              "Nexus endpoint name ("
                  + spec.getName()
                  + ") does not match expected pattern: "
                  + ENDPOINT_NAME_REGEX.pattern())
          .asRuntimeException();
    }
    if (!spec.hasTarget()) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Nexus endpoint spec must have a target")
          .asRuntimeException();
    }
    if (!spec.getTarget().hasWorker()) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Test server only supports Nexus endpoints with worker targets")
          .asRuntimeException();
    }
  }

  @Override
  public void close() {}
}
