
package io.temporal.common.converter

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

class KotlinObjectMapperFactory {
  companion object {
    @JvmStatic
    fun new(): ObjectMapper {
      // Let jackson-module-kotlin construct the module rather than calling a constructor here.
      // `KotlinModule()` compiles to the synthetic all-defaults overload of its deprecated
      // constructor, and that parameter list changed in 2.11, 2.12 and 2.16, so the call only
      // linked against the versions sharing the shape we happened to build against and threw
      // NoSuchMethodError on every other version. `registerKotlinModule` has kept a single
      // signature since 2.9.0, which is the whole Jackson range this SDK supports.
      return JacksonJsonPayloadConverter.newDefaultObjectMapper().registerKotlinModule()
    }
  }
}
