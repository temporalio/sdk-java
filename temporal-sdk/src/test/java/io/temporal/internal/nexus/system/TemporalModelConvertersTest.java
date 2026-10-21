package io.temporal.internal.nexus.system;

import static org.junit.Assert.*;

import io.temporal.common.VersioningOverride;
import io.temporal.common.WorkerDeploymentVersion;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import org.junit.Test;

public class TemporalModelConvertersTest {
  private static final DataConverter CONVERTER = DefaultDataConverter.STANDARD_INSTANCE;

  @Test
  @SuppressWarnings("deprecation")
  public void versioningOverridesRoundTripKnownShapes() {
    WorkerDeploymentVersion version = new WorkerDeploymentVersion("deployment", "build");
    VersioningOverride.PinnedVersioningOverride pinned =
        (VersioningOverride.PinnedVersioningOverride)
            roundTrip(new VersioningOverride.PinnedVersioningOverride(version));
    assertEquals(version, pinned.getVersion());
    VersioningOverride.OneTimeVersioningOverride oneTime =
        (VersioningOverride.OneTimeVersioningOverride)
            roundTrip(new VersioningOverride.OneTimeVersioningOverride(version));
    assertEquals(version, oneTime.getTargetVersion());
    assertTrue(
        roundTrip(new VersioningOverride.AutoUpgradeVersioningOverride())
            instanceof VersioningOverride.AutoUpgradeVersioningOverride);
    VersioningOverride.PinnedVersioningOverride legacy =
        (VersioningOverride.PinnedVersioningOverride)
            TemporalModelConverters.versioningOverrideFromProto(
                CONVERTER,
                io.temporal.api.workflow.v1.VersioningOverride.newBuilder()
                    .setPinnedVersion("deployment.build")
                    .build());
    assertEquals(version, legacy.getVersion());
  }

  @Test
  public void rejectsEmptyAndIncompleteVersioningOverrides() {
    for (io.temporal.api.workflow.v1.VersioningOverride value :
        new io.temporal.api.workflow.v1.VersioningOverride[] {
          io.temporal.api.workflow.v1.VersioningOverride.getDefaultInstance(),
          io.temporal.api.workflow.v1.VersioningOverride.newBuilder().setAutoUpgrade(false).build(),
          io.temporal.api.workflow.v1.VersioningOverride.newBuilder()
              .setPinned(
                  io.temporal.api.workflow.v1.VersioningOverride.PinnedOverride
                      .getDefaultInstance())
              .build(),
          io.temporal.api.workflow.v1.VersioningOverride.newBuilder()
              .setOneTime(
                  io.temporal.api.workflow.v1.VersioningOverride.OneTimeOverride
                      .getDefaultInstance())
              .build()
        }) {
      IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class,
              () -> TemporalModelConverters.versioningOverrideFromProto(CONVERTER, value));
      assertEquals("Unknown versioning override proto shape", error.getMessage());
    }
  }

  private static VersioningOverride roundTrip(VersioningOverride value) {
    return TemporalModelConverters.versioningOverrideFromProto(
        CONVERTER, TemporalModelConverters.versioningOverrideToProto(CONVERTER, value));
  }
}
