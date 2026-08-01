package io.temporal.releaseautomation;

import java.util.ArrayList;

public final class PublicationInput {
  public ReleaseIdentity release;
  public ApprovalEvidence approval;
  public String workflowId;
  public String runId;
  public String mavenGroup;
  public String mavenCentralBase;
  public java.util.List<String> mavenArtifacts = new ArrayList<>();
  public boolean emergencyHandoff;
  public ControlEvidence handoff;

  public PublicationInput() {}

  public PublicationInput(
      ReleaseIdentity release, ApprovalEvidence approval, String workflowId, String runId) {
    this.release = release;
    this.approval = approval;
    this.workflowId = workflowId;
    this.runId = runId;
    this.mavenGroup = ReleasePolicy.MAVEN_GROUP;
    this.mavenCentralBase = ReleasePolicy.MAVEN_CENTRAL_BASE;
    this.mavenArtifacts = new ArrayList<>(ReleasePolicy.MAVEN_ARTIFACTS);
  }

  public void validatePolicy() {
    if (!ReleasePolicy.MAVEN_GROUP.equals(mavenGroup)
        || !ReleasePolicy.MAVEN_CENTRAL_BASE.equals(mavenCentralBase)
        || !ReleasePolicy.MAVEN_ARTIFACTS.equals(mavenArtifacts)) {
      throw new IllegalArgumentException(
          "Publication input does not contain fixed sdk-java policy.");
    }
  }
}
