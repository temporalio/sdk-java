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

package io.temporal.authorization;

/**
 * Supplies tokens that will be sent to the Temporal server to perform authorization.
 * Implementations have to be thread-safe.
 *
 * <p>The default JWT ClaimMapper expects authorization tokens to be in the following format:
 *
 * <p>{@code Bearer <token>}
 *
 * <p>{@code <token>} Must be the Base64 url-encoded value of the token.
 *
 * @see <a href="https://docs.temporal.io/docs/server/security/#format-of-json-web-tokens">Format of
 *     JWT</a>
 */
public interface AuthorizationTokenSupplier {
  /** @return token to be passed in authorization header */
  String supply();
}
