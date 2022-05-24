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

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule

class KotlinObjectMapperFactory {
  companion object {
    @JvmStatic
    fun new(): ObjectMapper {
      val mapper = ObjectMapper()
      mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
      mapper.registerModule(JavaTimeModule())
      mapper.registerModule(Jdk8Module())
      mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
      // use deprecated constructor instead of builder to maintain compatibility with old jackson versions
      @Suppress("deprecation")
      val km = KotlinModule()
      mapper.registerModule(km)
      return mapper
    }
  }
}
