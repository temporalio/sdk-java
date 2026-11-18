package io.temporal.internal.statemachines;

import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.RecordMarkerCommandAttributes;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.sdk.v1.EventGroupMarker;
import io.temporal.api.sdk.v1.UserMetadata;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

class StateMachineCommandUtils {
  public static final Command RECORD_MARKER_FAKE_COMMAND =
      createRecordMarker(RecordMarkerCommandAttributes.getDefaultInstance(), null);

  public static Command createRecordMarker(
      RecordMarkerCommandAttributes attributes, @Nullable UserMetadata metadata) {
    return createRecordMarker(attributes, metadata, Collections.emptyList());
  }

  public static Command createRecordMarker(
      RecordMarkerCommandAttributes attributes,
      @Nullable UserMetadata metadata,
      List<EventGroupMarker> eventGroupMarkers) {
    Command.Builder command =
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_RECORD_MARKER)
            .setRecordMarkerCommandAttributes(attributes)
            .addAllEventGroupMarkers(eventGroupMarkers);
    if (metadata != null) {
      command.setUserMetadata(metadata);
    }
    return command.build();
  }

  public static Command createFakeMarkerCommand(String markerName) {
    return createRecordMarker(
        RecordMarkerCommandAttributes.newBuilder().setMarkerName(markerName).build(), null);
  }
}
