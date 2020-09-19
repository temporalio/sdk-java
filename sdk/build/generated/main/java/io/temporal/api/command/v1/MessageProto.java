// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: temporal/api/command/v1/message.proto

package io.temporal.api.command.v1;

public final class MessageProto {
  private MessageProto() {}
  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistryLite registry) {
  }

  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistry registry) {
    registerAllExtensions(
        (com.google.protobuf.ExtensionRegistryLite) registry);
  }
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_temporal_api_command_v1_ScheduleActivityTaskCommandAttributes_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_temporal_api_command_v1_ScheduleActivityTaskCommandAttributes_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_temporal_api_command_v1_RequestCancelActivityTaskCommandAttributes_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_temporal_api_command_v1_RequestCancelActivityTaskCommandAttributes_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_temporal_api_command_v1_StartTimerCommandAttributes_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_temporal_api_command_v1_StartTimerCommandAttributes_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_temporal_api_command_v1_CompleteWorkflowExecutionCommandAttributes_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_temporal_api_command_v1_CompleteWorkflowExecutionCommandAttributes_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_temporal_api_command_v1_FailWorkflowExecutionCommandAttributes_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_temporal_api_command_v1_FailWorkflowExecutionCommandAttributes_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_temporal_api_command_v1_CancelTimerCommandAttributes_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_temporal_api_command_v1_CancelTimerCommandAttributes_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_temporal_api_command_v1_CancelWorkflowExecutionCommandAttributes_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_temporal_api_command_v1_CancelWorkflowExecutionCommandAttributes_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_temporal_api_command_v1_RequestCancelExternalWorkflowExecutionCommandAttributes_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_temporal_api_command_v1_RequestCancelExternalWorkflowExecutionCommandAttributes_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_temporal_api_command_v1_SignalExternalWorkflowExecutionCommandAttributes_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_temporal_api_command_v1_SignalExternalWorkflowExecutionCommandAttributes_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_temporal_api_command_v1_UpsertWorkflowSearchAttributesCommandAttributes_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_temporal_api_command_v1_UpsertWorkflowSearchAttributesCommandAttributes_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_temporal_api_command_v1_RecordMarkerCommandAttributes_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_temporal_api_command_v1_RecordMarkerCommandAttributes_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_temporal_api_command_v1_RecordMarkerCommandAttributes_DetailsEntry_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_temporal_api_command_v1_RecordMarkerCommandAttributes_DetailsEntry_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_temporal_api_command_v1_ContinueAsNewWorkflowExecutionCommandAttributes_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_temporal_api_command_v1_ContinueAsNewWorkflowExecutionCommandAttributes_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_temporal_api_command_v1_StartChildWorkflowExecutionCommandAttributes_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_temporal_api_command_v1_StartChildWorkflowExecutionCommandAttributes_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_temporal_api_command_v1_Command_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_temporal_api_command_v1_Command_fieldAccessorTable;

  public static com.google.protobuf.Descriptors.FileDescriptor
      getDescriptor() {
    return descriptor;
  }
  private static  com.google.protobuf.Descriptors.FileDescriptor
      descriptor;
  static {
    java.lang.String[] descriptorData = {
      "\n%temporal/api/command/v1/message.proto\022" +
      "\027temporal.api.command.v1\032\036google/protobu" +
      "f/duration.proto\032!dependencies/gogoproto" +
      "/gogo.proto\032$temporal/api/enums/v1/workf" +
      "low.proto\032(temporal/api/enums/v1/command" +
      "_type.proto\032$temporal/api/common/v1/mess" +
      "age.proto\032%temporal/api/failure/v1/messa" +
      "ge.proto\032\'temporal/api/taskqueue/v1/mess" +
      "age.proto\"\347\004\n%ScheduleActivityTaskComman" +
      "dAttributes\022\023\n\013activity_id\030\001 \001(\t\022;\n\racti" +
      "vity_type\030\002 \001(\0132$.temporal.api.common.v1" +
      ".ActivityType\022\021\n\tnamespace\030\003 \001(\t\0228\n\ntask" +
      "_queue\030\004 \001(\0132$.temporal.api.taskqueue.v1" +
      ".TaskQueue\022.\n\006header\030\005 \001(\0132\036.temporal.ap" +
      "i.common.v1.Header\022/\n\005input\030\006 \001(\0132 .temp" +
      "oral.api.common.v1.Payloads\022B\n\031schedule_" +
      "to_close_timeout\030\007 \001(\0132\031.google.protobuf" +
      ".DurationB\004\230\337\037\001\022B\n\031schedule_to_start_tim" +
      "eout\030\010 \001(\0132\031.google.protobuf.DurationB\004\230" +
      "\337\037\001\022?\n\026start_to_close_timeout\030\t \001(\0132\031.go" +
      "ogle.protobuf.DurationB\004\230\337\037\001\022:\n\021heartbea" +
      "t_timeout\030\n \001(\0132\031.google.protobuf.Durati" +
      "onB\004\230\337\037\001\0229\n\014retry_policy\030\013 \001(\0132#.tempora" +
      "l.api.common.v1.RetryPolicy\"H\n*RequestCa" +
      "ncelActivityTaskCommandAttributes\022\032\n\022sch" +
      "eduled_event_id\030\001 \001(\003\"o\n\033StartTimerComma" +
      "ndAttributes\022\020\n\010timer_id\030\001 \001(\t\022>\n\025start_" +
      "to_fire_timeout\030\002 \001(\0132\031.google.protobuf." +
      "DurationB\004\230\337\037\001\"^\n*CompleteWorkflowExecut" +
      "ionCommandAttributes\0220\n\006result\030\001 \001(\0132 .t" +
      "emporal.api.common.v1.Payloads\"[\n&FailWo" +
      "rkflowExecutionCommandAttributes\0221\n\007fail" +
      "ure\030\001 \001(\0132 .temporal.api.failure.v1.Fail" +
      "ure\"0\n\034CancelTimerCommandAttributes\022\020\n\010t" +
      "imer_id\030\001 \001(\t\"]\n(CancelWorkflowExecution" +
      "CommandAttributes\0221\n\007details\030\001 \001(\0132 .tem" +
      "poral.api.common.v1.Payloads\"\237\001\n7Request" +
      "CancelExternalWorkflowExecutionCommandAt" +
      "tributes\022\021\n\tnamespace\030\001 \001(\t\022\023\n\013workflow_" +
      "id\030\002 \001(\t\022\016\n\006run_id\030\003 \001(\t\022\017\n\007control\030\004 \001(" +
      "\t\022\033\n\023child_workflow_only\030\005 \001(\010\"\367\001\n0Signa" +
      "lExternalWorkflowExecutionCommandAttribu" +
      "tes\022\021\n\tnamespace\030\001 \001(\t\022<\n\texecution\030\002 \001(" +
      "\0132).temporal.api.common.v1.WorkflowExecu" +
      "tion\022\023\n\013signal_name\030\003 \001(\t\022/\n\005input\030\004 \001(\013" +
      "2 .temporal.api.common.v1.Payloads\022\017\n\007co" +
      "ntrol\030\005 \001(\t\022\033\n\023child_workflow_only\030\006 \001(\010" +
      "\"v\n/UpsertWorkflowSearchAttributesComman" +
      "dAttributes\022C\n\021search_attributes\030\001 \001(\0132(" +
      ".temporal.api.common.v1.SearchAttributes" +
      "\"\277\002\n\035RecordMarkerCommandAttributes\022\023\n\013ma" +
      "rker_name\030\001 \001(\t\022T\n\007details\030\002 \003(\0132C.tempo" +
      "ral.api.command.v1.RecordMarkerCommandAt" +
      "tributes.DetailsEntry\022.\n\006header\030\003 \001(\0132\036." +
      "temporal.api.common.v1.Header\0221\n\007failure" +
      "\030\004 \001(\0132 .temporal.api.failure.v1.Failure" +
      "\032P\n\014DetailsEntry\022\013\n\003key\030\001 \001(\t\022/\n\005value\030\002" +
      " \001(\0132 .temporal.api.common.v1.Payloads:\002" +
      "8\001\"\303\006\n/ContinueAsNewWorkflowExecutionCom" +
      "mandAttributes\022;\n\rworkflow_type\030\001 \001(\0132$." +
      "temporal.api.common.v1.WorkflowType\0228\n\nt" +
      "ask_queue\030\002 \001(\0132$.temporal.api.taskqueue" +
      ".v1.TaskQueue\022/\n\005input\030\003 \001(\0132 .temporal." +
      "api.common.v1.Payloads\022=\n\024workflow_run_t" +
      "imeout\030\004 \001(\0132\031.google.protobuf.DurationB" +
      "\004\230\337\037\001\022>\n\025workflow_task_timeout\030\005 \001(\0132\031.g" +
      "oogle.protobuf.DurationB\004\230\337\037\001\022?\n\026backoff" +
      "_start_interval\030\006 \001(\0132\031.google.protobuf." +
      "DurationB\004\230\337\037\001\0229\n\014retry_policy\030\007 \001(\0132#.t" +
      "emporal.api.common.v1.RetryPolicy\022@\n\tini" +
      "tiator\030\010 \001(\0162-.temporal.api.enums.v1.Con" +
      "tinueAsNewInitiator\0221\n\007failure\030\t \001(\0132 .t" +
      "emporal.api.failure.v1.Failure\022@\n\026last_c" +
      "ompletion_result\030\n \001(\0132 .temporal.api.co" +
      "mmon.v1.Payloads\022\025\n\rcron_schedule\030\013 \001(\t\022" +
      ".\n\006header\030\014 \001(\0132\036.temporal.api.common.v1" +
      ".Header\022*\n\004memo\030\r \001(\0132\034.temporal.api.com" +
      "mon.v1.Memo\022C\n\021search_attributes\030\016 \001(\0132(" +
      ".temporal.api.common.v1.SearchAttributes" +
      "\"\335\006\n,StartChildWorkflowExecutionCommandA" +
      "ttributes\022\021\n\tnamespace\030\001 \001(\t\022\023\n\013workflow" +
      "_id\030\002 \001(\t\022;\n\rworkflow_type\030\003 \001(\0132$.tempo" +
      "ral.api.common.v1.WorkflowType\0228\n\ntask_q" +
      "ueue\030\004 \001(\0132$.temporal.api.taskqueue.v1.T" +
      "askQueue\022/\n\005input\030\005 \001(\0132 .temporal.api.c" +
      "ommon.v1.Payloads\022C\n\032workflow_execution_" +
      "timeout\030\006 \001(\0132\031.google.protobuf.Duration" +
      "B\004\230\337\037\001\022=\n\024workflow_run_timeout\030\007 \001(\0132\031.g" +
      "oogle.protobuf.DurationB\004\230\337\037\001\022>\n\025workflo" +
      "w_task_timeout\030\010 \001(\0132\031.google.protobuf.D" +
      "urationB\004\230\337\037\001\022E\n\023parent_close_policy\030\t \001" +
      "(\0162(.temporal.api.enums.v1.ParentClosePo" +
      "licy\022\017\n\007control\030\n \001(\t\022N\n\030workflow_id_reu" +
      "se_policy\030\013 \001(\0162,.temporal.api.enums.v1." +
      "WorkflowIdReusePolicy\0229\n\014retry_policy\030\014 " +
      "\001(\0132#.temporal.api.common.v1.RetryPolicy" +
      "\022\025\n\rcron_schedule\030\r \001(\t\022.\n\006header\030\016 \001(\0132" +
      "\036.temporal.api.common.v1.Header\022*\n\004memo\030" +
      "\017 \001(\0132\034.temporal.api.common.v1.Memo\022C\n\021s" +
      "earch_attributes\030\020 \001(\0132(.temporal.api.co" +
      "mmon.v1.SearchAttributes\"\242\r\n\007Command\0228\n\014" +
      "command_type\030\001 \001(\0162\".temporal.api.enums." +
      "v1.CommandType\022s\n)schedule_activity_task" +
      "_command_attributes\030\002 \001(\0132>.temporal.api" +
      ".command.v1.ScheduleActivityTaskCommandA" +
      "ttributesH\000\022^\n\036start_timer_command_attri" +
      "butes\030\003 \001(\01324.temporal.api.command.v1.St" +
      "artTimerCommandAttributesH\000\022}\n.complete_" +
      "workflow_execution_command_attributes\030\004 " +
      "\001(\0132C.temporal.api.command.v1.CompleteWo" +
      "rkflowExecutionCommandAttributesH\000\022u\n*fa" +
      "il_workflow_execution_command_attributes" +
      "\030\005 \001(\0132?.temporal.api.command.v1.FailWor" +
      "kflowExecutionCommandAttributesH\000\022~\n/req" +
      "uest_cancel_activity_task_command_attrib" +
      "utes\030\006 \001(\0132C.temporal.api.command.v1.Req" +
      "uestCancelActivityTaskCommandAttributesH" +
      "\000\022`\n\037cancel_timer_command_attributes\030\007 \001" +
      "(\01325.temporal.api.command.v1.CancelTimer" +
      "CommandAttributesH\000\022y\n,cancel_workflow_e" +
      "xecution_command_attributes\030\010 \001(\0132A.temp" +
      "oral.api.command.v1.CancelWorkflowExecut" +
      "ionCommandAttributesH\000\022\231\001\n=request_cance" +
      "l_external_workflow_execution_command_at" +
      "tributes\030\t \001(\0132P.temporal.api.command.v1" +
      ".RequestCancelExternalWorkflowExecutionC" +
      "ommandAttributesH\000\022b\n record_marker_comm" +
      "and_attributes\030\n \001(\01326.temporal.api.comm" +
      "and.v1.RecordMarkerCommandAttributesH\000\022\211" +
      "\001\n5continue_as_new_workflow_execution_co" +
      "mmand_attributes\030\013 \001(\0132H.temporal.api.co" +
      "mmand.v1.ContinueAsNewWorkflowExecutionC" +
      "ommandAttributesH\000\022\202\001\n1start_child_workf" +
      "low_execution_command_attributes\030\014 \001(\0132E" +
      ".temporal.api.command.v1.StartChildWorkf" +
      "lowExecutionCommandAttributesH\000\022\212\001\n5sign" +
      "al_external_workflow_execution_command_a" +
      "ttributes\030\r \001(\0132I.temporal.api.command.v" +
      "1.SignalExternalWorkflowExecutionCommand" +
      "AttributesH\000\022\210\001\n4upsert_workflow_search_" +
      "attributes_command_attributes\030\016 \001(\0132H.te" +
      "mporal.api.command.v1.UpsertWorkflowSear" +
      "chAttributesCommandAttributesH\000B\014\n\nattri" +
      "butesBq\n\032io.temporal.api.command.v1B\014Mes" +
      "sageProtoP\001Z%go.temporal.io/api/command/" +
      "v1;command\352\002\033Temporal::Api::Decision::V1" +
      "b\006proto3"
    };
    descriptor = com.google.protobuf.Descriptors.FileDescriptor
      .internalBuildGeneratedFileFrom(descriptorData,
        new com.google.protobuf.Descriptors.FileDescriptor[] {
          com.google.protobuf.DurationProto.getDescriptor(),
          gogoproto.Gogo.getDescriptor(),
          io.temporal.api.enums.v1.WorkflowProto.getDescriptor(),
          io.temporal.api.enums.v1.CommandTypeProto.getDescriptor(),
          io.temporal.api.common.v1.MessageProto.getDescriptor(),
          io.temporal.api.failure.v1.MessageProto.getDescriptor(),
          io.temporal.api.taskqueue.v1.MessageProto.getDescriptor(),
        });
    internal_static_temporal_api_command_v1_ScheduleActivityTaskCommandAttributes_descriptor =
      getDescriptor().getMessageTypes().get(0);
    internal_static_temporal_api_command_v1_ScheduleActivityTaskCommandAttributes_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_temporal_api_command_v1_ScheduleActivityTaskCommandAttributes_descriptor,
        new java.lang.String[] { "ActivityId", "ActivityType", "Namespace", "TaskQueue", "Header", "Input", "ScheduleToCloseTimeout", "ScheduleToStartTimeout", "StartToCloseTimeout", "HeartbeatTimeout", "RetryPolicy", });
    internal_static_temporal_api_command_v1_RequestCancelActivityTaskCommandAttributes_descriptor =
      getDescriptor().getMessageTypes().get(1);
    internal_static_temporal_api_command_v1_RequestCancelActivityTaskCommandAttributes_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_temporal_api_command_v1_RequestCancelActivityTaskCommandAttributes_descriptor,
        new java.lang.String[] { "ScheduledEventId", });
    internal_static_temporal_api_command_v1_StartTimerCommandAttributes_descriptor =
      getDescriptor().getMessageTypes().get(2);
    internal_static_temporal_api_command_v1_StartTimerCommandAttributes_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_temporal_api_command_v1_StartTimerCommandAttributes_descriptor,
        new java.lang.String[] { "TimerId", "StartToFireTimeout", });
    internal_static_temporal_api_command_v1_CompleteWorkflowExecutionCommandAttributes_descriptor =
      getDescriptor().getMessageTypes().get(3);
    internal_static_temporal_api_command_v1_CompleteWorkflowExecutionCommandAttributes_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_temporal_api_command_v1_CompleteWorkflowExecutionCommandAttributes_descriptor,
        new java.lang.String[] { "Result", });
    internal_static_temporal_api_command_v1_FailWorkflowExecutionCommandAttributes_descriptor =
      getDescriptor().getMessageTypes().get(4);
    internal_static_temporal_api_command_v1_FailWorkflowExecutionCommandAttributes_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_temporal_api_command_v1_FailWorkflowExecutionCommandAttributes_descriptor,
        new java.lang.String[] { "Failure", });
    internal_static_temporal_api_command_v1_CancelTimerCommandAttributes_descriptor =
      getDescriptor().getMessageTypes().get(5);
    internal_static_temporal_api_command_v1_CancelTimerCommandAttributes_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_temporal_api_command_v1_CancelTimerCommandAttributes_descriptor,
        new java.lang.String[] { "TimerId", });
    internal_static_temporal_api_command_v1_CancelWorkflowExecutionCommandAttributes_descriptor =
      getDescriptor().getMessageTypes().get(6);
    internal_static_temporal_api_command_v1_CancelWorkflowExecutionCommandAttributes_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_temporal_api_command_v1_CancelWorkflowExecutionCommandAttributes_descriptor,
        new java.lang.String[] { "Details", });
    internal_static_temporal_api_command_v1_RequestCancelExternalWorkflowExecutionCommandAttributes_descriptor =
      getDescriptor().getMessageTypes().get(7);
    internal_static_temporal_api_command_v1_RequestCancelExternalWorkflowExecutionCommandAttributes_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_temporal_api_command_v1_RequestCancelExternalWorkflowExecutionCommandAttributes_descriptor,
        new java.lang.String[] { "Namespace", "WorkflowId", "RunId", "Control", "ChildWorkflowOnly", });
    internal_static_temporal_api_command_v1_SignalExternalWorkflowExecutionCommandAttributes_descriptor =
      getDescriptor().getMessageTypes().get(8);
    internal_static_temporal_api_command_v1_SignalExternalWorkflowExecutionCommandAttributes_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_temporal_api_command_v1_SignalExternalWorkflowExecutionCommandAttributes_descriptor,
        new java.lang.String[] { "Namespace", "Execution", "SignalName", "Input", "Control", "ChildWorkflowOnly", });
    internal_static_temporal_api_command_v1_UpsertWorkflowSearchAttributesCommandAttributes_descriptor =
      getDescriptor().getMessageTypes().get(9);
    internal_static_temporal_api_command_v1_UpsertWorkflowSearchAttributesCommandAttributes_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_temporal_api_command_v1_UpsertWorkflowSearchAttributesCommandAttributes_descriptor,
        new java.lang.String[] { "SearchAttributes", });
    internal_static_temporal_api_command_v1_RecordMarkerCommandAttributes_descriptor =
      getDescriptor().getMessageTypes().get(10);
    internal_static_temporal_api_command_v1_RecordMarkerCommandAttributes_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_temporal_api_command_v1_RecordMarkerCommandAttributes_descriptor,
        new java.lang.String[] { "MarkerName", "Details", "Header", "Failure", });
    internal_static_temporal_api_command_v1_RecordMarkerCommandAttributes_DetailsEntry_descriptor =
      internal_static_temporal_api_command_v1_RecordMarkerCommandAttributes_descriptor.getNestedTypes().get(0);
    internal_static_temporal_api_command_v1_RecordMarkerCommandAttributes_DetailsEntry_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_temporal_api_command_v1_RecordMarkerCommandAttributes_DetailsEntry_descriptor,
        new java.lang.String[] { "Key", "Value", });
    internal_static_temporal_api_command_v1_ContinueAsNewWorkflowExecutionCommandAttributes_descriptor =
      getDescriptor().getMessageTypes().get(11);
    internal_static_temporal_api_command_v1_ContinueAsNewWorkflowExecutionCommandAttributes_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_temporal_api_command_v1_ContinueAsNewWorkflowExecutionCommandAttributes_descriptor,
        new java.lang.String[] { "WorkflowType", "TaskQueue", "Input", "WorkflowRunTimeout", "WorkflowTaskTimeout", "BackoffStartInterval", "RetryPolicy", "Initiator", "Failure", "LastCompletionResult", "CronSchedule", "Header", "Memo", "SearchAttributes", });
    internal_static_temporal_api_command_v1_StartChildWorkflowExecutionCommandAttributes_descriptor =
      getDescriptor().getMessageTypes().get(12);
    internal_static_temporal_api_command_v1_StartChildWorkflowExecutionCommandAttributes_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_temporal_api_command_v1_StartChildWorkflowExecutionCommandAttributes_descriptor,
        new java.lang.String[] { "Namespace", "WorkflowId", "WorkflowType", "TaskQueue", "Input", "WorkflowExecutionTimeout", "WorkflowRunTimeout", "WorkflowTaskTimeout", "ParentClosePolicy", "Control", "WorkflowIdReusePolicy", "RetryPolicy", "CronSchedule", "Header", "Memo", "SearchAttributes", });
    internal_static_temporal_api_command_v1_Command_descriptor =
      getDescriptor().getMessageTypes().get(13);
    internal_static_temporal_api_command_v1_Command_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_temporal_api_command_v1_Command_descriptor,
        new java.lang.String[] { "CommandType", "ScheduleActivityTaskCommandAttributes", "StartTimerCommandAttributes", "CompleteWorkflowExecutionCommandAttributes", "FailWorkflowExecutionCommandAttributes", "RequestCancelActivityTaskCommandAttributes", "CancelTimerCommandAttributes", "CancelWorkflowExecutionCommandAttributes", "RequestCancelExternalWorkflowExecutionCommandAttributes", "RecordMarkerCommandAttributes", "ContinueAsNewWorkflowExecutionCommandAttributes", "StartChildWorkflowExecutionCommandAttributes", "SignalExternalWorkflowExecutionCommandAttributes", "UpsertWorkflowSearchAttributesCommandAttributes", "Attributes", });
    com.google.protobuf.ExtensionRegistry registry =
        com.google.protobuf.ExtensionRegistry.newInstance();
    registry.add(gogoproto.Gogo.stdduration);
    com.google.protobuf.Descriptors.FileDescriptor
        .internalUpdateFileDescriptor(descriptor, registry);
    com.google.protobuf.DurationProto.getDescriptor();
    gogoproto.Gogo.getDescriptor();
    io.temporal.api.enums.v1.WorkflowProto.getDescriptor();
    io.temporal.api.enums.v1.CommandTypeProto.getDescriptor();
    io.temporal.api.common.v1.MessageProto.getDescriptor();
    io.temporal.api.failure.v1.MessageProto.getDescriptor();
    io.temporal.api.taskqueue.v1.MessageProto.getDescriptor();
  }

  // @@protoc_insertion_point(outer_class_scope)
}
