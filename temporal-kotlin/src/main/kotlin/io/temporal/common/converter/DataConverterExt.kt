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

package io.temporal.common.converter

import io.temporal.api.common.v1.Payload
import io.temporal.api.common.v1.Payloads
import java.util.Optional
import kotlin.reflect.javaType
import kotlin.reflect.typeOf

fun DataConverter.toPayloadOrNull(value: Any?): Payload? {
  return toPayload(value).orElse(null)
}

@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T> DataConverter.fromPayload(payload: Payload): T? {
  return fromPayload(payload, T::class.java, typeOf<T>().javaType)
}

/**
 * Implements conversion of a list of values.
 *
 * @param values Java values to convert to String.
 * @return converted value
 * @throws DataConverterException if conversion of the value passed as parameter failed for any
 * reason.
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
 * @param T type of the parameter stored in the content
 * @return converted Java object
 * @throws DataConverterException if conversion of the data passed as parameter failed for any
 * reason.
 */
@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T> DataConverter.fromPayloads(index: Int, content: Payloads?): T? {
  return fromPayloads(index, Optional.ofNullable(content), T::class.java, typeOf<T>().javaType)
}
