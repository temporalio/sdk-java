package io.temporal.testing;

/** Category hierarchy for tests excluded from Temporal Cloud execution. */
public interface CloudTestExclusion {

  /** The test requires one or more local Temporal Server instances. */
  interface RequiresLocalServer extends CloudTestExclusion {}

  /**
   * The test requires a Temporal Cloud capability that CI cannot provision or a feature that is
   * unavailable in Temporal Cloud.
   */
  interface RequiresCloudProvisioning extends CloudTestExclusion {}

  /** The test can run in Cloud after its setup or assertions are adapted. */
  interface NeedsCloudAdaptation extends CloudTestExclusion {}
}
