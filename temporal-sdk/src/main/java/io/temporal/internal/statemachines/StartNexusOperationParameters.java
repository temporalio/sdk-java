package io.temporal.internal.statemachines;

import io.temporal.api.command.v1.ScheduleNexusOperationCommandAttributes;
import io.temporal.api.sdk.v1.UserMetadata;
import io.temporal.workflow.NexusOperationCancellationType;
import javax.annotation.Nullable;

public class StartNexusOperationParameters {

  private final ScheduleNexusOperationCommandAttributes.Builder attributes;
  private final NexusOperationCancellationType cancellationType;
  private final UserMetadata metadata;

  public StartNexusOperationParameters(
      ScheduleNexusOperationCommandAttributes.Builder attributes,
      NexusOperationCancellationType cancellationType,
      @Nullable UserMetadata metadata) {
    this.attributes = attributes;
    this.cancellationType = cancellationType;
    this.metadata = metadata;
  }

  public ScheduleNexusOperationCommandAttributes.Builder getAttributes() {
    return attributes;
  }

  public NexusOperationCancellationType getCancellationType() {
    return cancellationType;
  }

  public @Nullable UserMetadata getMetadata() {
    return metadata;
  }
}
