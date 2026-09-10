
package io.temporal.common.converter

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

class KotlinObjectMapperFactory {
  companion object {
    @JvmStatic
    fun new(): ObjectMapper {
      val mapper = JacksonJsonPayloadConverter.newDefaultObjectMapper()

      return mapper.registerKotlinModule()
    }
  }
}
