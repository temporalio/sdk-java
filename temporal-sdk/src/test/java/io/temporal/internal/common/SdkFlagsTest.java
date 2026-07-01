package io.temporal.internal.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public class SdkFlagsTest {

  @Test
  public void setSdkFlagIsRespectedWithoutMetadataCapability() {
    SdkFlags flags = new SdkFlags(false, () -> true);

    flags.setSdkFlag(SdkFlag.VERSION_WAIT_FOR_MARKER);

    assertTrue(flags.checkSdkFlag(SdkFlag.VERSION_WAIT_FOR_MARKER));
    assertTrue(flags.tryUseSdkFlag(SdkFlag.VERSION_WAIT_FOR_MARKER));
    assertEquals(Collections.emptySet(), flags.takeNewSdkFlags());
  }

  @Test
  public void tryUseDoesNotRecordNewFlagsWithoutMetadataCapability() {
    SdkFlags flags = new SdkFlags(false, () -> false);

    assertFalse(flags.tryUseSdkFlag(SdkFlag.SKIP_YIELD_ON_VERSION));

    assertFalse(flags.checkSdkFlag(SdkFlag.SKIP_YIELD_ON_VERSION));
    assertEquals(Collections.emptySet(), flags.takeNewSdkFlags());
  }

  @Test
  public void checkSdkFlagReturnsFalseForMissingFlagsWithoutMetadataCapability() {
    SdkFlags flags = new SdkFlags(false, () -> false);

    assertFalse(flags.checkSdkFlag(SdkFlag.VERSION_WAIT_FOR_MARKER));
  }

  @Test
  public void tryUseRecordsNewFlagsWithMetadataCapability() {
    SdkFlags flags = new SdkFlags(true, () -> false);

    assertTrue(flags.tryUseSdkFlag(SdkFlag.SKIP_YIELD_ON_VERSION));

    assertTrue(flags.checkSdkFlag(SdkFlag.SKIP_YIELD_ON_VERSION));
    assertEquals(EnumSet.of(SdkFlag.SKIP_YIELD_ON_VERSION), flags.takeNewSdkFlags());
    assertEquals(Collections.emptySet(), flags.takeNewSdkFlags());
  }

  @Test
  public void tryUseInReplayRequiresFlagInHistory() {
    AtomicBoolean replaying = new AtomicBoolean(true);
    SdkFlags flags = new SdkFlags(true, replaying::get);

    assertFalse(flags.tryUseSdkFlag(SdkFlag.SKIP_YIELD_ON_VERSION));

    flags.setSdkFlag(SdkFlag.SKIP_YIELD_ON_VERSION);

    assertTrue(flags.tryUseSdkFlag(SdkFlag.SKIP_YIELD_ON_VERSION));
    assertEquals(Collections.emptySet(), flags.takeNewSdkFlags());
  }
}
