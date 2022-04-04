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

package io.temporal.internal.async.spi;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Provides language specific functionality to disassemble method references that could be passed
 * from different languages into temporal code and extract target from them, which is required for
 * correct working of {@link io.temporal.workflow.Async}
 */
public interface MethodReferenceDisassemblyService {
  String KOTLIN = "kotlin";

  /**
   * @param methodReference method reference to extract target from
   * @return target of the method reference {@code methodReference}, null if methodReference is not
   *     recognized by implementation as a method reference (means that it's general purpose lambda)
   */
  @Nullable
  Object getMethodReferenceTarget(@Nonnull Object methodReference);

  /**
   * @return language this service provides an extension or implementation for
   */
  String getLanguageName();
}
