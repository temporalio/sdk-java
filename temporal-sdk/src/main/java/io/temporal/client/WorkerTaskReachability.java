package io.temporal.client;

import io.temporal.api.enums.v1.TaskReachability;
import io.temporal.api.workflowservice.v1.GetWorkerTaskReachabilityResponse;
import io.temporal.common.Experimental;
import java.util.*;

/** Contains information about the reachability of some Build IDs. */
@Experimental
public class WorkerTaskReachability {
  private final Map<String, BuildIdReachability> buildIdReachability;

  public WorkerTaskReachability(GetWorkerTaskReachabilityResponse reachabilityResponse) {
    buildIdReachability = new HashMap<>(reachabilityResponse.getBuildIdReachabilityList().size());
    for (io.temporal.api.taskqueue.v1.BuildIdReachability BuildReachability :
        reachabilityResponse.getBuildIdReachabilityList()) {
      Set<String> unretrievedTaskQueues = new HashSet<>();
      Map<String, List<TaskReachability>> retrievedTaskQueues = new HashMap<>();
      for (io.temporal.api.taskqueue.v1.TaskQueueReachability taskQueueReachability :
          BuildReachability.getTaskQueueReachabilityList()) {
        if (taskQueueReachability.getReachabilityList().size() == 1
            && taskQueueReachability.getReachabilityList().get(0)
                == TaskReachability.TASK_REACHABILITY_UNSPECIFIED) {
          unretrievedTaskQueues.add(taskQueueReachability.getTaskQueue());
        } else {
          retrievedTaskQueues.put(
              taskQueueReachability.getTaskQueue(), taskQueueReachability.getReachabilityList());
        }
      }
      buildIdReachability.put(
          BuildReachability.getBuildId(),
          new BuildIdReachability(retrievedTaskQueues, unretrievedTaskQueues));
    }
  }

  /**
   * @return Returns a map of Build IDs to information about their reachability.
   */
  public Map<String, BuildIdReachability> getBuildIdReachability() {
    return Collections.unmodifiableMap(buildIdReachability);
  }
}
