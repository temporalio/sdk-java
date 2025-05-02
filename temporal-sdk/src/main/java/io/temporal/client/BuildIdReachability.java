package io.temporal.client;

import io.temporal.api.enums.v1.TaskReachability;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Contains information about the reachability of a specific Build ID. */
public class BuildIdReachability {
  private final Map<String, List<TaskReachability>> taskQueueReachability;
  private final Set<String> unretrievedTaskQueues;

  public BuildIdReachability(
      Map<String, List<TaskReachability>> taskQueueReachability,
      Set<String> unretrievedTaskQueues) {
    this.taskQueueReachability = taskQueueReachability;
    this.unretrievedTaskQueues = unretrievedTaskQueues;
  }

  /**
   * @return A map of Task Queue names to the reachability status of the Build ID on that queue. If
   *     the value is an empty list, the Build ID is not reachable on that queue.
   */
  public Map<String, List<TaskReachability>> getTaskQueueReachability() {
    return Collections.unmodifiableMap(taskQueueReachability);
  }

  /**
   * @return a list of any Task Queues that could not be retrieved because the server limits the
   *     number that can be queried at once.
   */
  public Set<String> getUnretrievedTaskQueues() {
    return Collections.unmodifiableSet(unretrievedTaskQueues);
  }
}
