package io.temporal.internal.activity;

import io.temporal.activity.ActivityInfo;
import io.temporal.api.common.v1.Header;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.nexus.v1.PropagatedSerializationContext;
import io.temporal.workflow.Functions;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * An extension for {@link ActivityInfo} with information about the activity task that the current
 * activity is handling that should be available for Temporal SDK, but shouldn't be available or
 * exposed to Activity implementation code.
 */
interface ActivityInfoInternal extends ActivityInfo {
  /**
   * @return function shat should be triggered after activity completion with any outcome (success,
   *     failure, cancelling)
   */
  Functions.Proc getCompletionHandle();

  /**
   * @return input parameters of the activity execution
   */
  Optional<Payloads> getInput();

  /**
   * @return header that is passed with the activity execution
   */
  Optional<Header> getHeader();

  /**
   * @return serialization context of the Nexus operation handler that started this standalone
   *     activity, as propagated by the server, or null if the activity was not started by one
   */
  @Nullable
  PropagatedSerializationContext getPropagatedNexusSerializationContext();
}
