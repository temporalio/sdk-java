
package io.temporal.common.converter

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule

class KotlinObjectMapperFactory {
  companion object {
    @JvmStatic
    fun new(): ObjectMapper {
      val mapper = JacksonJsonPayloadConverter.newDefaultObjectMapper()

      // use deprecated constructor instead of builder to maintain compatibility with old jackson versions
      @Suppress("deprecation")
      val km = KotlinModule()
      mapper.registerModule(km)
      return mapper
    }
  }
}
