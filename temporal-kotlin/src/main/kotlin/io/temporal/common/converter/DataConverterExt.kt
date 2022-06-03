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

package io.temporal.common.converter

import io.temporal.api.common.v1.Payload
import io.temporal.api.common.v1.Payloads
import java.util.Optional
import kotlin.reflect.javaType
import kotlin.reflect.typeOf

/**
 * @see DataConverter.toPayload
 */
fun DataConverter.toPayloadOrNull(value: Any?): Payload? {
  return toPayload(value).orElse(null)
}

/**
 * @param T type of the object
 * @see DataConverter.fromPayload
 */
@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T> DataConverter.fromPayload(payload: Payload): T? {
  return fromPayload(payload, T::class.java, typeOf<T>().javaType)
}

/**
 * @see DataConverter.toPayloads
 */
fun DataConverter.toPayloadsOrNull(vararg values: Any?): Payloads? {
  return toPayloads(*values).orElse(null)
}

/**
 * Implements conversion of an array of values of different types. Useful for deserializing
 * arguments of function invocations.
 *
 * @param index index of the value in the payloads
 * @param content serialized value to convert to Java objects.
 * @param T type of the parameter stored in the [content]
 * @see DataConverter.fromPayloads
 */
@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T> DataConverter.fromPayloads(index: Int, content: Payloads?): T? {
  return fromPayloads(index, Optional.ofNullable(content), T::class.java, typeOf<T>().javaType)
}
