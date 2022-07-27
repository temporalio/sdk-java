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

package io.temporal.common.converter;

import java.util.concurrent.atomic.AtomicReference;

public class GlobalDataConverter {
  private GlobalDataConverter() {}

  private static final AtomicReference<DataConverter> globalDataConverterInstance =
      new AtomicReference<>(DefaultDataConverter.STANDARD_INSTANCE);

  /**
   * Override the global data converter default.
   *
   * <p>Consider using {@link
   * io.temporal.client.WorkflowClientOptions.Builder#setDataConverter(DataConverter)} to set data
   * converter per client / worker instance to avoid conflicts if your setup requires different
   * converters for different clients / workers.
   */
  public static void register(DataConverter converter) {
    globalDataConverterInstance.set(converter);
  }

  public static DataConverter get() {
    return globalDataConverterInstance.get();
  }
}
