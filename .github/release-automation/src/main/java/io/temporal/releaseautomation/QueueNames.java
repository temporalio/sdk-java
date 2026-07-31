package io.temporal.releaseautomation;

import java.util.Locale;
import java.util.regex.Pattern;

public final class QueueNames {
  private static final Pattern PLATFORM = Pattern.compile("[a-z0-9-]+");

  private QueueNames() {}

  public static String candidateWorkflow(CandidateIdentity identity) {
    return candidateWorkflowFromDigest(identity.digest());
  }

  public static String build(CandidateIdentity identity, String platform) {
    return buildFromDigest(identity.digest(), platform);
  }

  static String candidateWorkflowFromDigest(String digest) {
    validateDigest(digest);
    return "sdk-java-release-candidate-" + shortDigest(digest) + "-workflow";
  }

  static String buildFromDigest(String digest, String platform) {
    validateDigest(digest);
    String normalized = platform.toLowerCase(Locale.ROOT);
    if (!PLATFORM.matcher(normalized).matches()) {
      throw new IllegalArgumentException("Invalid build platform.");
    }
    return "sdk-java-release-candidate-" + shortDigest(digest) + "-build-" + normalized;
  }

  public static String releaseWorkflow(ReleaseIdentity identity) {
    return "sdk-java-release-" + shortDigest(identity.digest()) + "-workflow";
  }

  public static String publication(ReleaseIdentity identity) {
    return "sdk-java-release-" + shortDigest(identity.digest()) + "-publication";
  }

  public static String candidateWorkflowId(CandidateIdentity identity) {
    return "sdk-java-release-candidate/" + identity.digest();
  }

  public static String releaseWorkflowId(ReleaseIdentity identity) {
    return "sdk-java-release/" + identity.digest();
  }

  private static String shortDigest(String digest) {
    return digest.substring(0, 32);
  }

  private static void validateDigest(String digest) {
    if (digest == null || !digest.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Invalid sdk-java release digest.");
    }
  }
}
