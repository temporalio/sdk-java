package io.temporal.worker.tuning;

import io.temporal.common.Experimental;
import java.util.Map;

@Experimental
public interface SlotReserveContext<SI extends SlotInfo> {
  /**
   * @return the Task Queue for which this reservation request is associated.
   */
  String getTaskQueue();

  /**
   * @return A read-only & safe for concurrent access mapping of slot permits to the information
   *     associated with the in-use slot. This map is changed internally any time new slots are
   *     used.
   */
  Map<SlotPermit, SI> getUsedSlots();

  /**
   * @return The worker's identity that is associated with this reservation request.
   */
  String getWorkerIdentity();

  /**
   * @return The worker's build ID that is associated with this reservation request.
   */
  String getWorkerBuildId();

  /**
   * @return The number of currently outstanding slot permits of this type, whether used or not.
   */
  int getNumIssuedSlots();
}
