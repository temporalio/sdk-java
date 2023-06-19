package io.temporal.workflow

import io.temporal.activity.ActivityOptions
import io.temporal.internal.async.KotlinActivityStub
import io.temporal.internal.async.KotlinWorkflowInternal

class KotlinWorkflow {
  companion object {
    /**
     * Creates non typed client stub to activities. Allows executing activities by their string name.
     *
     * @param options specify the activity invocation parameters.
     */
    fun newUntypedActivityStub(options: ActivityOptions?): KotlinActivityStub {
      return KotlinWorkflowInternal.newUntypedActivityStub(options)
    }
  }
}
