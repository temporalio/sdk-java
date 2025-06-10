package io.temporal.internal.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.common.converter.DataConverterException;
import java.util.List;

// This class by mistake leaked into a public interface and is used by users, so it can't be deleted
// right away.
// To fix the mistake, it was republished in a public package as
// io.temporal.common.WorkflowExecutionHistory class
// that extends this class.
/**
 * @deprecated use {@link io.temporal.common.WorkflowExecutionHistory} instead.
 */
@Deprecated
public class WorkflowExecutionHistory {
  protected static final String DEFAULT_WORKFLOW_ID = "workflow_id_in_replay";
  private static final Gson GSON_PRETTY_PRINTER = new GsonBuilder().setPrettyPrinting().create();

  // we stay on using the old API that uses a JsonParser instance instead of static methods
  // to give users a larger range of supported version
  @SuppressWarnings("deprecation")
  private static final JsonParser GSON_PARSER = new JsonParser();

  private final History history;
  private final String workflowId;

  public WorkflowExecutionHistory(History history) {
    this(history, extractWorkflowId(history));
  }

  public WorkflowExecutionHistory(History history, String workflowId) {
    checkHistory(history);
    this.history = history;
    this.workflowId = workflowId;
  }

  public static String extractWorkflowId(History history) {
    HistoryEvent startedEvent = history.getEvents(0);
    String id = startedEvent.getWorkflowExecutionStartedEventAttributes().getWorkflowId();
    return id.isEmpty() ? DEFAULT_WORKFLOW_ID : id;
  }

  public static WorkflowExecutionHistory fromJson(String serialized) {
    io.temporal.common.WorkflowExecutionHistory parsed =
        io.temporal.common.WorkflowExecutionHistory.fromJson(serialized);
    History history = parsed.getHistory();
    return new WorkflowExecutionHistory(history, extractWorkflowId(history));
  }

  private static void checkHistory(History history) {
    List<HistoryEvent> events = history.getEventsList();
    if (events.size() == 0) {
      throw new IllegalArgumentException("Empty history");
    }
    HistoryEvent startedEvent = events.get(0);
    if (startedEvent.getEventType() != EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED) {
      throw new IllegalArgumentException(
          "First event is not WorkflowExecutionStarted but " + startedEvent);
    }
    if (!startedEvent.hasWorkflowExecutionStartedEventAttributes()) {
      throw new IllegalArgumentException("First event is corrupted");
    }
  }

  /**
   * @param prettyPrint Whether to pretty print the JSON.
   * @return Full json that can be used for replay.
   */
  public String toJson(boolean prettyPrint) {
    return toJson(prettyPrint, false);
  }

  /**
   * @param prettyPrint Whether to pretty print the JSON.
   * @param legacyFormat If true, will use the older-style protobuf enum formatting as done by
   *     Temporal tooling in the past. This is not recommended.
   * @return Full JSON that can be used for replay.
   */
  public String toJson(boolean prettyPrint, boolean legacyFormat) {
    JsonFormat.Printer printer = JsonFormat.printer();
    try {
      String protoJson = printer.print(history);
      if (legacyFormat) {
        protoJson = HistoryJsonUtils.protoJsonToHistoryFormatJson(protoJson);
      }

      if (prettyPrint) {
        @SuppressWarnings("deprecation")
        JsonElement je = GSON_PARSER.parse(protoJson);
        return GSON_PRETTY_PRINTER.toJson(je);
      } else {
        return protoJson;
      }
    } catch (InvalidProtocolBufferException e) {
      throw new DataConverterException(e);
    }
  }

  /**
   * Returns workflow instance history in a human-readable format.
   *
   * @param showWorkflowTasks when set to false workflow task events (command events) are not
   *     included
   */
  public String toProtoText(boolean showWorkflowTasks) {
    return HistoryProtoTextUtils.toProtoText(history, showWorkflowTasks);
  }

  public WorkflowExecution getWorkflowExecution() {
    return WorkflowExecution.newBuilder()
        .setWorkflowId(workflowId)
        .setRunId("run_id_in_replay")
        .build();
  }

  public List<HistoryEvent> getEvents() {
    return history.getEventsList();
  }

  public HistoryEvent getLastEvent() {
    int eventsCount = history.getEventsCount();
    return eventsCount > 0 ? history.getEvents(eventsCount - 1) : null;
  }

  public History getHistory() {
    return history;
  }

  @Override
  public String toString() {
    return "WorkflowExecutionHistory{" + "history=" + history + '}';
  }
}
