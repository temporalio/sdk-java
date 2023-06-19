package io.temporal.internal.async

import io.temporal.api.common.v1.Payloads
import io.temporal.common.interceptors.Header

interface KotlinWorkflowDefinition {

  /** Always called first.  */
  suspend fun initialize()

  suspend fun execute(header: Header?, input: Payloads?): Payloads?
}
