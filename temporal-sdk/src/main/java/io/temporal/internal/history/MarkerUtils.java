package io.temporal.internal.history;

import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.RecordMarkerCommandAttributes;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.MarkerRecordedEventAttributes;
import io.temporal.common.converter.StdConverterBackwardsCompatAdapter;
import java.util.Optional;

public class MarkerUtils {

  /**
   * @param event {@code HistoryEvent} to inspect
   * @param markerName expected marker name
   * @return true if the event has a correct structure for a marker and an expected marker name
   */
  public static boolean verifyMarkerName(HistoryEvent event, String markerName) {
    if (!EventType.EVENT_TYPE_MARKER_RECORDED.equals(event.getEventType())) {
      return false;
    }
    MarkerRecordedEventAttributes attributes = event.getMarkerRecordedEventAttributes();
    return markerName.equals(attributes.getMarkerName());
  }

  /**
   * @param command {@code Command} to inspect
   * @param markerName expected marker name
   * @return true if the command has a correct structure for a marker and an expected marker name
   */
  public static boolean verifyMarkerName(Command command, String markerName) {
    if (!CommandType.COMMAND_TYPE_RECORD_MARKER.equals(command.getCommandType())) {
      return false;
    }
    RecordMarkerCommandAttributes attributes = command.getRecordMarkerCommandAttributes();
    return markerName.equals(attributes.getMarkerName());
  }

  /**
   * This method should be used to extract values from the marker persisted by the SDK itself. These
   * values are converted using standard data converter to be always accessible by the SDK.
   *
   * @param markerAttributes marker attributes to extract the value frm
   * @param key key of the value in {@code markerAttributes} details map
   * @param simpleValueType class of a non-generic value to extract
   * @param <T> type of the value to extract
   * @return the value deserialized using standard data converter
   */
  public static <T> T getValueFromMarker(
      MarkerRecordedEventAttributes markerAttributes, String key, Class<T> simpleValueType) {
    Optional<Payloads> payloads = Optional.ofNullable(markerAttributes.getDetailsMap().get(key));
    return StdConverterBackwardsCompatAdapter.fromPayloads(
        0, payloads, simpleValueType, simpleValueType);
  }
}
