package io.temporal.internal.statemachines;

import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.RecordMarkerCommandAttributes;
import io.temporal.api.enums.v1.CommandType;

class StateMachineCommandUtils {
  public static final Command RECORD_MARKER_FAKE_COMMAND =
      createRecordMarker(RecordMarkerCommandAttributes.getDefaultInstance());

  public static Command createRecordMarker(RecordMarkerCommandAttributes attributes) {
    return Command.newBuilder()
        .setCommandType(CommandType.COMMAND_TYPE_RECORD_MARKER)
        .setRecordMarkerCommandAttributes(attributes)
        .build();
  }

  public static Command createFakeMarkerCommand(String markerName) {
    return createRecordMarker(
        RecordMarkerCommandAttributes.newBuilder().setMarkerName(markerName).build());
  }
}
