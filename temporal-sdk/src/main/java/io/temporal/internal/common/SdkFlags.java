package io.temporal.internal.common;

import io.temporal.workflow.Functions;
import java.util.EnumSet;

/** Represents all the flags that are currently set in a workflow execution. */
public final class SdkFlags {
  private final boolean supportSdkMetadata;
  private final Functions.Func<Boolean> replaying;
  // Flags that have been received from the server or have not been sent yet.
  private final EnumSet<SdkFlag> sdkFlags = EnumSet.noneOf(SdkFlag.class);
  // Flags that have been set this WFT that have not been sent to the server.
  // Keep track of them separately, so we know what to send to the server.
  private final EnumSet<SdkFlag> unsentSdkFlags = EnumSet.noneOf(SdkFlag.class);

  public SdkFlags(boolean supportSdkMetadata, Functions.Func<Boolean> replaying) {
    this.supportSdkMetadata = supportSdkMetadata;
    this.replaying = replaying;
  }

  /**
   * Marks a flag as usable regardless of replay status.
   *
   * @return True, as long as the server supports SDK flags
   */
  public boolean setSdkFlag(SdkFlag flag) {
    if (!supportSdkMetadata) {
      return false;
    }
    sdkFlags.add(flag);
    return true;
  }

  /**
   * @return True if this flag may currently be used.
   */
  public boolean tryUseSdkFlag(SdkFlag flag) {
    if (!supportSdkMetadata) {
      return false;
    }

    if (!replaying.apply()) {
      sdkFlags.add(flag);
      unsentSdkFlags.add(flag);
      return true;
    } else {
      return sdkFlags.contains(flag);
    }
  }

  /**
   * @return True if this flag is set.
   */
  public boolean checkSdkFlag(SdkFlag flag) {
    if (!supportSdkMetadata) {
      return false;
    }

    return sdkFlags.contains(flag);
  }

  /**
   * @return All flags set since the last call to takeNewSdkFlags.
   */
  public EnumSet<SdkFlag> takeNewSdkFlags() {
    EnumSet<SdkFlag> result = unsentSdkFlags.clone();
    unsentSdkFlags.clear();
    return result;
  }
}
