package io.temporal.internal.sync;

import io.temporal.api.common.v1.Payloads;
import io.temporal.common.VersioningBehavior;
import io.temporal.common.interceptors.Header;
import java.util.Optional;
import javax.annotation.Nullable;

/** Workflow wrapper used by the workflow thread to start a workflow */
interface SyncWorkflowDefinition {

  /** Always called first. */
  void initialize(Optional<Payloads> input);

  /**
   * Returns the workflow instance that is executing this code. Must be called after {@link
   * #initialize(Optional)}.
   */
  @Nullable
  Object getInstance();

  Optional<Payloads> execute(Header header, Optional<Payloads> input);

  /**
   * @return The versioning behavior for this workflow as defined by the attached annotation,
   *     otherwise {@link VersioningBehavior#UNSPECIFIED}.
   */
  VersioningBehavior getVersioningBehavior();
}
