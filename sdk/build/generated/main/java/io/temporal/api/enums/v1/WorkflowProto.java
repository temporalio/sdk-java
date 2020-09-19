// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: temporal/api/enums/v1/workflow.proto

package io.temporal.api.enums.v1;

public final class WorkflowProto {
  private WorkflowProto() {}
  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistryLite registry) {
  }

  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistry registry) {
    registerAllExtensions(
        (com.google.protobuf.ExtensionRegistryLite) registry);
  }

  public static com.google.protobuf.Descriptors.FileDescriptor
      getDescriptor() {
    return descriptor;
  }
  private static  com.google.protobuf.Descriptors.FileDescriptor
      descriptor;
  static {
    java.lang.String[] descriptorData = {
      "\n$temporal/api/enums/v1/workflow.proto\022\025" +
      "temporal.api.enums.v1*\330\001\n\025WorkflowIdReus" +
      "ePolicy\022(\n$WORKFLOW_ID_REUSE_POLICY_UNSP" +
      "ECIFIED\020\000\022,\n(WORKFLOW_ID_REUSE_POLICY_AL" +
      "LOW_DUPLICATE\020\001\0228\n4WORKFLOW_ID_REUSE_POL" +
      "ICY_ALLOW_DUPLICATE_FAILED_ONLY\020\002\022-\n)WOR" +
      "KFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE\020\003" +
      "*\244\001\n\021ParentClosePolicy\022#\n\037PARENT_CLOSE_P" +
      "OLICY_UNSPECIFIED\020\000\022!\n\035PARENT_CLOSE_POLI" +
      "CY_TERMINATE\020\001\022\037\n\033PARENT_CLOSE_POLICY_AB" +
      "ANDON\020\002\022&\n\"PARENT_CLOSE_POLICY_REQUEST_C" +
      "ANCEL\020\003*\275\001\n\026ContinueAsNewInitiator\022)\n%CO" +
      "NTINUE_AS_NEW_INITIATOR_UNSPECIFIED\020\000\022&\n" +
      "\"CONTINUE_AS_NEW_INITIATOR_WORKFLOW\020\001\022#\n" +
      "\037CONTINUE_AS_NEW_INITIATOR_RETRY\020\002\022+\n\'CO" +
      "NTINUE_AS_NEW_INITIATOR_CRON_SCHEDULE\020\003*" +
      "\345\002\n\027WorkflowExecutionStatus\022)\n%WORKFLOW_" +
      "EXECUTION_STATUS_UNSPECIFIED\020\000\022%\n!WORKFL" +
      "OW_EXECUTION_STATUS_RUNNING\020\001\022\'\n#WORKFLO" +
      "W_EXECUTION_STATUS_COMPLETED\020\002\022$\n WORKFL" +
      "OW_EXECUTION_STATUS_FAILED\020\003\022&\n\"WORKFLOW" +
      "_EXECUTION_STATUS_CANCELED\020\004\022(\n$WORKFLOW" +
      "_EXECUTION_STATUS_TERMINATED\020\005\022.\n*WORKFL" +
      "OW_EXECUTION_STATUS_CONTINUED_AS_NEW\020\006\022\'" +
      "\n#WORKFLOW_EXECUTION_STATUS_TIMED_OUT\020\007*" +
      "\265\001\n\024PendingActivityState\022&\n\"PENDING_ACTI" +
      "VITY_STATE_UNSPECIFIED\020\000\022$\n PENDING_ACTI" +
      "VITY_STATE_SCHEDULED\020\001\022\"\n\036PENDING_ACTIVI" +
      "TY_STATE_STARTED\020\002\022+\n\'PENDING_ACTIVITY_S" +
      "TATE_CANCEL_REQUESTED\020\003*\227\001\n\026HistoryEvent" +
      "FilterType\022)\n%HISTORY_EVENT_FILTER_TYPE_" +
      "UNSPECIFIED\020\000\022\'\n#HISTORY_EVENT_FILTER_TY" +
      "PE_ALL_EVENT\020\001\022)\n%HISTORY_EVENT_FILTER_T" +
      "YPE_CLOSE_EVENT\020\002*\237\002\n\nRetryState\022\033\n\027RETR" +
      "Y_STATE_UNSPECIFIED\020\000\022\033\n\027RETRY_STATE_IN_" +
      "PROGRESS\020\001\022%\n!RETRY_STATE_NON_RETRYABLE_" +
      "FAILURE\020\002\022\027\n\023RETRY_STATE_TIMEOUT\020\003\022(\n$RE" +
      "TRY_STATE_MAXIMUM_ATTEMPTS_REACHED\020\004\022$\n " +
      "RETRY_STATE_RETRY_POLICY_NOT_SET\020\005\022%\n!RE" +
      "TRY_STATE_INTERNAL_SERVER_ERROR\020\006\022 \n\034RET" +
      "RY_STATE_CANCEL_REQUESTED\020\007*\260\001\n\013TimeoutT" +
      "ype\022\034\n\030TIMEOUT_TYPE_UNSPECIFIED\020\000\022\037\n\033TIM" +
      "EOUT_TYPE_START_TO_CLOSE\020\001\022\"\n\036TIMEOUT_TY" +
      "PE_SCHEDULE_TO_START\020\002\022\"\n\036TIMEOUT_TYPE_S" +
      "CHEDULE_TO_CLOSE\020\003\022\032\n\026TIMEOUT_TYPE_HEART" +
      "BEAT\020\004Bi\n\030io.temporal.api.enums.v1B\rWork" +
      "flowProtoP\001Z!go.temporal.io/api/enums/v1" +
      ";enums\352\002\030Temporal::Api::Enums::V1b\006proto" +
      "3"
    };
    descriptor = com.google.protobuf.Descriptors.FileDescriptor
      .internalBuildGeneratedFileFrom(descriptorData,
        new com.google.protobuf.Descriptors.FileDescriptor[] {
        });
  }

  // @@protoc_insertion_point(outer_class_scope)
}
