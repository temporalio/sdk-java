package io.temporal.opentelemetry.v2.internal;

import io.opentelemetry.api.common.AttributeKey;

final class TagKeys {
  static final AttributeKey<String> WORKFLOW_ID = AttributeKey.stringKey("temporalWorkflowID");
  static final AttributeKey<String> RUN_ID = AttributeKey.stringKey("temporalRunID");
  static final AttributeKey<String> ACTIVITY_ID = AttributeKey.stringKey("temporalActivityID");
  static final AttributeKey<String> UPDATE_ID = AttributeKey.stringKey("temporalUpdateID");
  static final AttributeKey<String> TERMINATE_REASON =
      AttributeKey.stringKey("temporalTerminateReason");
  static final AttributeKey<String> NEXUS_SERVICE = AttributeKey.stringKey("temporalNexusService");
  static final AttributeKey<String> NEXUS_OPERATION =
      AttributeKey.stringKey("temporalNexusOperation");
  static final AttributeKey<String> NEXUS_ENDPOINT =
      AttributeKey.stringKey("temporalNexusEndpoint");

  private TagKeys() {}
}
