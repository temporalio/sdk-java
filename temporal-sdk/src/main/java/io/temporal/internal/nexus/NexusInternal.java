package io.temporal.internal.nexus;

import io.temporal.nexus.NexusOperationContext;

public final class NexusInternal {
  private NexusInternal() {}

  public static NexusOperationContext getOperationContext() {
    return CurrentNexusOperationContext.get().getUserFacingContext();
  }
}
