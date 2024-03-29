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

package io.temporal.common.interceptors;

import io.temporal.api.common.v1.Payload;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;

public class Header {
  private final Map<String, Payload> values;

  public Header(@Nonnull io.temporal.api.common.v1.Header header) {
    values = header.getFieldsMap();
  }

  public static Header empty() {
    return new Header(new HashMap<>());
  }

  public Header(Map<String, Payload> values) {
    this.values = values;
  }

  public Map<String, Payload> getValues() {
    return values;
  }
}
