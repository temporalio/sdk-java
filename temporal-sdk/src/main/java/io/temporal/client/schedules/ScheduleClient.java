package io.temporal.client.schedules;

import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * Client to the Temporal service used to create, list and get handles to Schedules.
 *
 * @see ScheduleHandle
 */
public interface ScheduleClient {
  /**
   * Creates a client that connects to an instance of the Temporal Service to interact with
   * schedules.
   *
   * @param service client to the Temporal Service endpoint.
   * @return client to interact with schedules
   */
  static ScheduleClient newInstance(WorkflowServiceStubs service) {
    return ScheduleClientImpl.newInstance(service, ScheduleClientOptions.getDefaultInstance());
  }

  /**
   * Creates a client that connects to an instance of the Temporal Service to interact with
   * schedules.
   *
   * @param service client to the Temporal Service endpoint.
   * @param options Options (like {@link io.temporal.common.converter.DataConverter}er override) for
   *     configuring client.
   * @return client to interact with schedules
   */
  static ScheduleClient newInstance(WorkflowServiceStubs service, ScheduleClientOptions options) {
    return ScheduleClientImpl.newInstance(service, options);
  }

  /**
   * Create a schedule and return its handle.
   *
   * @param scheduleID Unique ID for the schedule.
   * @param schedule Schedule to create.
   * @param options Options for creating the schedule.
   * @throws ScheduleAlreadyRunningException if the schedule is already runnning.
   * @return A handle that can be used to perform operations on a schedule.
   */
  ScheduleHandle createSchedule(String scheduleID, Schedule schedule, ScheduleOptions options);

  /**
   * Gets the schedule handle for the given ID.
   *
   * @param scheduleID Schedule ID to get the handle for.
   * @return A handle that can be used to perform operations on a schedule.
   */
  ScheduleHandle getHandle(String scheduleID);

  /**
   * List schedules.
   *
   * @return sequential stream that performs remote pagination under the hood
   */
  Stream<ScheduleListDescription> listSchedules();

  /**
   * List schedules.
   *
   * @param pageSize how many results to fetch from the Server at a time. Default is 100.
   * @return sequential stream that performs remote pagination under the hood
   */
  Stream<ScheduleListDescription> listSchedules(@Nullable Integer pageSize);

  /**
   * List schedules.
   *
   * @param query Temporal Visibility Query, for syntax see <a
   *     href="https://docs.temporal.io/visibility#list-filter">Visibility docs</a>
   * @param pageSize how many results to fetch from the Server at a time. Default is 100.
   * @return sequential stream that performs remote pagination under the hood
   */
  Stream<ScheduleListDescription> listSchedules(@Nullable String query, @Nullable Integer pageSize);
}
