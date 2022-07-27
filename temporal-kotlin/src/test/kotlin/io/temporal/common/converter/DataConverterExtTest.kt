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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.UUID

class DataConverterExtTest {

  private val dataConverter = DefaultDataConverter.STANDARD_INSTANCE

  @Test
  fun `fromPayload method should resolve generic parameters`() {
    val initialValue: Map<String, List<Int>> = mapOf(
      "key1" to listOf(1, 2),
      "key2" to listOf(42),
      "key3" to emptyList()
    )

    val payload = dataConverter.toPayloadOrNull(initialValue)
    assertNotNull(payload)

    val convertedValue: Map<String, List<Int>>? = dataConverter.fromPayload(payload!!)
    assertEquals(initialValue, convertedValue)
  }

  @Test
  fun `fromPayloads method should resolve generic parameters`() {
    val value0: Int? = null
    val value1: Map<String, List<Int>> = mapOf(
      "key1" to listOf(1, 2),
      "key2" to listOf(42),
      "key3" to emptyList()
    )
    val value2: UUID = UUID.fromString("73d2b9f3-c2ee-4920-b737-053c6a9dac64")
    val value3: List<Long> = listOf(1, 2, 3, 4, 5)

    val payloads = dataConverter.toPayloadsOrNull(value0, value1, value2, value3)

    assertEquals(value0, dataConverter.fromPayloads<Int?>(0, payloads))
    assertEquals(value1, dataConverter.fromPayloads<Map<String, List<Int>>>(1, payloads))
    assertEquals(value2, dataConverter.fromPayloads<UUID>(2, payloads))
    assertEquals(value3, dataConverter.fromPayloads<List<Long>>(3, payloads))
  }
}
