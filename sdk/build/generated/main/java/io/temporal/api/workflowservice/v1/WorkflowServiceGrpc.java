package io.temporal.api.workflowservice.v1;

import static io.grpc.MethodDescriptor.generateFullMethodName;
import static io.grpc.stub.ClientCalls.asyncBidiStreamingCall;
import static io.grpc.stub.ClientCalls.asyncClientStreamingCall;
import static io.grpc.stub.ClientCalls.asyncServerStreamingCall;
import static io.grpc.stub.ClientCalls.asyncUnaryCall;
import static io.grpc.stub.ClientCalls.blockingServerStreamingCall;
import static io.grpc.stub.ClientCalls.blockingUnaryCall;
import static io.grpc.stub.ClientCalls.futureUnaryCall;
import static io.grpc.stub.ServerCalls.asyncBidiStreamingCall;
import static io.grpc.stub.ServerCalls.asyncClientStreamingCall;
import static io.grpc.stub.ServerCalls.asyncServerStreamingCall;
import static io.grpc.stub.ServerCalls.asyncUnaryCall;
import static io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall;
import static io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall;

/**
 * <pre>
 * WorkflowService API is exposed to provide support for long running applications.  Application is expected to call
 * StartWorkflowExecution to create an instance for each instance of long running workflow.  Such applications are expected
 * to have a worker which regularly polls for WorkflowTask and ActivityTask from the WorkflowService.  For each
 * WorkflowTask, application is expected to process the history of events for that session and respond back with next
 * commands.  For each ActivityTask, application is expected to execute the actual logic for that task and respond back
 * with completion or failure.  Worker is expected to regularly heartbeat while activity task is running.
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.32.1)",
    comments = "Source: temporal/api/workflowservice/v1/service.proto")
public final class WorkflowServiceGrpc {

  private WorkflowServiceGrpc() {}

  public static final String SERVICE_NAME = "temporal.api.workflowservice.v1.WorkflowService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RegisterNamespaceRequest,
      io.temporal.api.workflowservice.v1.RegisterNamespaceResponse> getRegisterNamespaceMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RegisterNamespace",
      requestType = io.temporal.api.workflowservice.v1.RegisterNamespaceRequest.class,
      responseType = io.temporal.api.workflowservice.v1.RegisterNamespaceResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RegisterNamespaceRequest,
      io.temporal.api.workflowservice.v1.RegisterNamespaceResponse> getRegisterNamespaceMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RegisterNamespaceRequest, io.temporal.api.workflowservice.v1.RegisterNamespaceResponse> getRegisterNamespaceMethod;
    if ((getRegisterNamespaceMethod = WorkflowServiceGrpc.getRegisterNamespaceMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getRegisterNamespaceMethod = WorkflowServiceGrpc.getRegisterNamespaceMethod) == null) {
          WorkflowServiceGrpc.getRegisterNamespaceMethod = getRegisterNamespaceMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.RegisterNamespaceRequest, io.temporal.api.workflowservice.v1.RegisterNamespaceResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RegisterNamespace"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.RegisterNamespaceRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.RegisterNamespaceResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("RegisterNamespace"))
              .build();
        }
      }
    }
    return getRegisterNamespaceMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.DescribeNamespaceRequest,
      io.temporal.api.workflowservice.v1.DescribeNamespaceResponse> getDescribeNamespaceMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DescribeNamespace",
      requestType = io.temporal.api.workflowservice.v1.DescribeNamespaceRequest.class,
      responseType = io.temporal.api.workflowservice.v1.DescribeNamespaceResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.DescribeNamespaceRequest,
      io.temporal.api.workflowservice.v1.DescribeNamespaceResponse> getDescribeNamespaceMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.DescribeNamespaceRequest, io.temporal.api.workflowservice.v1.DescribeNamespaceResponse> getDescribeNamespaceMethod;
    if ((getDescribeNamespaceMethod = WorkflowServiceGrpc.getDescribeNamespaceMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getDescribeNamespaceMethod = WorkflowServiceGrpc.getDescribeNamespaceMethod) == null) {
          WorkflowServiceGrpc.getDescribeNamespaceMethod = getDescribeNamespaceMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.DescribeNamespaceRequest, io.temporal.api.workflowservice.v1.DescribeNamespaceResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DescribeNamespace"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.DescribeNamespaceRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.DescribeNamespaceResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("DescribeNamespace"))
              .build();
        }
      }
    }
    return getDescribeNamespaceMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.ListNamespacesRequest,
      io.temporal.api.workflowservice.v1.ListNamespacesResponse> getListNamespacesMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListNamespaces",
      requestType = io.temporal.api.workflowservice.v1.ListNamespacesRequest.class,
      responseType = io.temporal.api.workflowservice.v1.ListNamespacesResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.ListNamespacesRequest,
      io.temporal.api.workflowservice.v1.ListNamespacesResponse> getListNamespacesMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.ListNamespacesRequest, io.temporal.api.workflowservice.v1.ListNamespacesResponse> getListNamespacesMethod;
    if ((getListNamespacesMethod = WorkflowServiceGrpc.getListNamespacesMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getListNamespacesMethod = WorkflowServiceGrpc.getListNamespacesMethod) == null) {
          WorkflowServiceGrpc.getListNamespacesMethod = getListNamespacesMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.ListNamespacesRequest, io.temporal.api.workflowservice.v1.ListNamespacesResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListNamespaces"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.ListNamespacesRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.ListNamespacesResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("ListNamespaces"))
              .build();
        }
      }
    }
    return getListNamespacesMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.UpdateNamespaceRequest,
      io.temporal.api.workflowservice.v1.UpdateNamespaceResponse> getUpdateNamespaceMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "UpdateNamespace",
      requestType = io.temporal.api.workflowservice.v1.UpdateNamespaceRequest.class,
      responseType = io.temporal.api.workflowservice.v1.UpdateNamespaceResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.UpdateNamespaceRequest,
      io.temporal.api.workflowservice.v1.UpdateNamespaceResponse> getUpdateNamespaceMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.UpdateNamespaceRequest, io.temporal.api.workflowservice.v1.UpdateNamespaceResponse> getUpdateNamespaceMethod;
    if ((getUpdateNamespaceMethod = WorkflowServiceGrpc.getUpdateNamespaceMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getUpdateNamespaceMethod = WorkflowServiceGrpc.getUpdateNamespaceMethod) == null) {
          WorkflowServiceGrpc.getUpdateNamespaceMethod = getUpdateNamespaceMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.UpdateNamespaceRequest, io.temporal.api.workflowservice.v1.UpdateNamespaceResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "UpdateNamespace"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.UpdateNamespaceRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.UpdateNamespaceResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("UpdateNamespace"))
              .build();
        }
      }
    }
    return getUpdateNamespaceMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.DeprecateNamespaceRequest,
      io.temporal.api.workflowservice.v1.DeprecateNamespaceResponse> getDeprecateNamespaceMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DeprecateNamespace",
      requestType = io.temporal.api.workflowservice.v1.DeprecateNamespaceRequest.class,
      responseType = io.temporal.api.workflowservice.v1.DeprecateNamespaceResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.DeprecateNamespaceRequest,
      io.temporal.api.workflowservice.v1.DeprecateNamespaceResponse> getDeprecateNamespaceMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.DeprecateNamespaceRequest, io.temporal.api.workflowservice.v1.DeprecateNamespaceResponse> getDeprecateNamespaceMethod;
    if ((getDeprecateNamespaceMethod = WorkflowServiceGrpc.getDeprecateNamespaceMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getDeprecateNamespaceMethod = WorkflowServiceGrpc.getDeprecateNamespaceMethod) == null) {
          WorkflowServiceGrpc.getDeprecateNamespaceMethod = getDeprecateNamespaceMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.DeprecateNamespaceRequest, io.temporal.api.workflowservice.v1.DeprecateNamespaceResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DeprecateNamespace"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.DeprecateNamespaceRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.DeprecateNamespaceResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("DeprecateNamespace"))
              .build();
        }
      }
    }
    return getDeprecateNamespaceMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest,
      io.temporal.api.workflowservice.v1.StartWorkflowExecutionResponse> getStartWorkflowExecutionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "StartWorkflowExecution",
      requestType = io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest.class,
      responseType = io.temporal.api.workflowservice.v1.StartWorkflowExecutionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest,
      io.temporal.api.workflowservice.v1.StartWorkflowExecutionResponse> getStartWorkflowExecutionMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest, io.temporal.api.workflowservice.v1.StartWorkflowExecutionResponse> getStartWorkflowExecutionMethod;
    if ((getStartWorkflowExecutionMethod = WorkflowServiceGrpc.getStartWorkflowExecutionMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getStartWorkflowExecutionMethod = WorkflowServiceGrpc.getStartWorkflowExecutionMethod) == null) {
          WorkflowServiceGrpc.getStartWorkflowExecutionMethod = getStartWorkflowExecutionMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest, io.temporal.api.workflowservice.v1.StartWorkflowExecutionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "StartWorkflowExecution"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.StartWorkflowExecutionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("StartWorkflowExecution"))
              .build();
        }
      }
    }
    return getStartWorkflowExecutionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest,
      io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse> getGetWorkflowExecutionHistoryMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetWorkflowExecutionHistory",
      requestType = io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest.class,
      responseType = io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest,
      io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse> getGetWorkflowExecutionHistoryMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest, io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse> getGetWorkflowExecutionHistoryMethod;
    if ((getGetWorkflowExecutionHistoryMethod = WorkflowServiceGrpc.getGetWorkflowExecutionHistoryMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getGetWorkflowExecutionHistoryMethod = WorkflowServiceGrpc.getGetWorkflowExecutionHistoryMethod) == null) {
          WorkflowServiceGrpc.getGetWorkflowExecutionHistoryMethod = getGetWorkflowExecutionHistoryMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest, io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetWorkflowExecutionHistory"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("GetWorkflowExecutionHistory"))
              .build();
        }
      }
    }
    return getGetWorkflowExecutionHistoryMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueRequest,
      io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse> getPollWorkflowTaskQueueMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "PollWorkflowTaskQueue",
      requestType = io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueRequest.class,
      responseType = io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueRequest,
      io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse> getPollWorkflowTaskQueueMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueRequest, io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse> getPollWorkflowTaskQueueMethod;
    if ((getPollWorkflowTaskQueueMethod = WorkflowServiceGrpc.getPollWorkflowTaskQueueMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getPollWorkflowTaskQueueMethod = WorkflowServiceGrpc.getPollWorkflowTaskQueueMethod) == null) {
          WorkflowServiceGrpc.getPollWorkflowTaskQueueMethod = getPollWorkflowTaskQueueMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueRequest, io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "PollWorkflowTaskQueue"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("PollWorkflowTaskQueue"))
              .build();
        }
      }
    }
    return getPollWorkflowTaskQueueMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest,
      io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse> getRespondWorkflowTaskCompletedMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RespondWorkflowTaskCompleted",
      requestType = io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest.class,
      responseType = io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest,
      io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse> getRespondWorkflowTaskCompletedMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest, io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse> getRespondWorkflowTaskCompletedMethod;
    if ((getRespondWorkflowTaskCompletedMethod = WorkflowServiceGrpc.getRespondWorkflowTaskCompletedMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getRespondWorkflowTaskCompletedMethod = WorkflowServiceGrpc.getRespondWorkflowTaskCompletedMethod) == null) {
          WorkflowServiceGrpc.getRespondWorkflowTaskCompletedMethod = getRespondWorkflowTaskCompletedMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest, io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RespondWorkflowTaskCompleted"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("RespondWorkflowTaskCompleted"))
              .build();
        }
      }
    }
    return getRespondWorkflowTaskCompletedMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest,
      io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedResponse> getRespondWorkflowTaskFailedMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RespondWorkflowTaskFailed",
      requestType = io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest.class,
      responseType = io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest,
      io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedResponse> getRespondWorkflowTaskFailedMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest, io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedResponse> getRespondWorkflowTaskFailedMethod;
    if ((getRespondWorkflowTaskFailedMethod = WorkflowServiceGrpc.getRespondWorkflowTaskFailedMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getRespondWorkflowTaskFailedMethod = WorkflowServiceGrpc.getRespondWorkflowTaskFailedMethod) == null) {
          WorkflowServiceGrpc.getRespondWorkflowTaskFailedMethod = getRespondWorkflowTaskFailedMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest, io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RespondWorkflowTaskFailed"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("RespondWorkflowTaskFailed"))
              .build();
        }
      }
    }
    return getRespondWorkflowTaskFailedMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.PollActivityTaskQueueRequest,
      io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse> getPollActivityTaskQueueMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "PollActivityTaskQueue",
      requestType = io.temporal.api.workflowservice.v1.PollActivityTaskQueueRequest.class,
      responseType = io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.PollActivityTaskQueueRequest,
      io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse> getPollActivityTaskQueueMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.PollActivityTaskQueueRequest, io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse> getPollActivityTaskQueueMethod;
    if ((getPollActivityTaskQueueMethod = WorkflowServiceGrpc.getPollActivityTaskQueueMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getPollActivityTaskQueueMethod = WorkflowServiceGrpc.getPollActivityTaskQueueMethod) == null) {
          WorkflowServiceGrpc.getPollActivityTaskQueueMethod = getPollActivityTaskQueueMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.PollActivityTaskQueueRequest, io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "PollActivityTaskQueue"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.PollActivityTaskQueueRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("PollActivityTaskQueue"))
              .build();
        }
      }
    }
    return getPollActivityTaskQueueMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatRequest,
      io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatResponse> getRecordActivityTaskHeartbeatMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RecordActivityTaskHeartbeat",
      requestType = io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatRequest.class,
      responseType = io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatRequest,
      io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatResponse> getRecordActivityTaskHeartbeatMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatRequest, io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatResponse> getRecordActivityTaskHeartbeatMethod;
    if ((getRecordActivityTaskHeartbeatMethod = WorkflowServiceGrpc.getRecordActivityTaskHeartbeatMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getRecordActivityTaskHeartbeatMethod = WorkflowServiceGrpc.getRecordActivityTaskHeartbeatMethod) == null) {
          WorkflowServiceGrpc.getRecordActivityTaskHeartbeatMethod = getRecordActivityTaskHeartbeatMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatRequest, io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RecordActivityTaskHeartbeat"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("RecordActivityTaskHeartbeat"))
              .build();
        }
      }
    }
    return getRecordActivityTaskHeartbeatMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdRequest,
      io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdResponse> getRecordActivityTaskHeartbeatByIdMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RecordActivityTaskHeartbeatById",
      requestType = io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdRequest.class,
      responseType = io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdRequest,
      io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdResponse> getRecordActivityTaskHeartbeatByIdMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdRequest, io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdResponse> getRecordActivityTaskHeartbeatByIdMethod;
    if ((getRecordActivityTaskHeartbeatByIdMethod = WorkflowServiceGrpc.getRecordActivityTaskHeartbeatByIdMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getRecordActivityTaskHeartbeatByIdMethod = WorkflowServiceGrpc.getRecordActivityTaskHeartbeatByIdMethod) == null) {
          WorkflowServiceGrpc.getRecordActivityTaskHeartbeatByIdMethod = getRecordActivityTaskHeartbeatByIdMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdRequest, io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RecordActivityTaskHeartbeatById"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("RecordActivityTaskHeartbeatById"))
              .build();
        }
      }
    }
    return getRecordActivityTaskHeartbeatByIdMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedRequest,
      io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedResponse> getRespondActivityTaskCompletedMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RespondActivityTaskCompleted",
      requestType = io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedRequest.class,
      responseType = io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedRequest,
      io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedResponse> getRespondActivityTaskCompletedMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedRequest, io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedResponse> getRespondActivityTaskCompletedMethod;
    if ((getRespondActivityTaskCompletedMethod = WorkflowServiceGrpc.getRespondActivityTaskCompletedMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getRespondActivityTaskCompletedMethod = WorkflowServiceGrpc.getRespondActivityTaskCompletedMethod) == null) {
          WorkflowServiceGrpc.getRespondActivityTaskCompletedMethod = getRespondActivityTaskCompletedMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedRequest, io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RespondActivityTaskCompleted"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("RespondActivityTaskCompleted"))
              .build();
        }
      }
    }
    return getRespondActivityTaskCompletedMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedByIdRequest,
      io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedByIdResponse> getRespondActivityTaskCompletedByIdMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RespondActivityTaskCompletedById",
      requestType = io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedByIdRequest.class,
      responseType = io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedByIdResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedByIdRequest,
      io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedByIdResponse> getRespondActivityTaskCompletedByIdMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedByIdRequest, io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedByIdResponse> getRespondActivityTaskCompletedByIdMethod;
    if ((getRespondActivityTaskCompletedByIdMethod = WorkflowServiceGrpc.getRespondActivityTaskCompletedByIdMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getRespondActivityTaskCompletedByIdMethod = WorkflowServiceGrpc.getRespondActivityTaskCompletedByIdMethod) == null) {
          WorkflowServiceGrpc.getRespondActivityTaskCompletedByIdMethod = getRespondActivityTaskCompletedByIdMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedByIdRequest, io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedByIdResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RespondActivityTaskCompletedById"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedByIdRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedByIdResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("RespondActivityTaskCompletedById"))
              .build();
        }
      }
    }
    return getRespondActivityTaskCompletedByIdMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RespondActivityTaskFailedRequest,
      io.temporal.api.workflowservice.v1.RespondActivityTaskFailedResponse> getRespondActivityTaskFailedMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RespondActivityTaskFailed",
      requestType = io.temporal.api.workflowservice.v1.RespondActivityTaskFailedRequest.class,
      responseType = io.temporal.api.workflowservice.v1.RespondActivityTaskFailedResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RespondActivityTaskFailedRequest,
      io.temporal.api.workflowservice.v1.RespondActivityTaskFailedResponse> getRespondActivityTaskFailedMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RespondActivityTaskFailedRequest, io.temporal.api.workflowservice.v1.RespondActivityTaskFailedResponse> getRespondActivityTaskFailedMethod;
    if ((getRespondActivityTaskFailedMethod = WorkflowServiceGrpc.getRespondActivityTaskFailedMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getRespondActivityTaskFailedMethod = WorkflowServiceGrpc.getRespondActivityTaskFailedMethod) == null) {
          WorkflowServiceGrpc.getRespondActivityTaskFailedMethod = getRespondActivityTaskFailedMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.RespondActivityTaskFailedRequest, io.temporal.api.workflowservice.v1.RespondActivityTaskFailedResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RespondActivityTaskFailed"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.RespondActivityTaskFailedRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.RespondActivityTaskFailedResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("RespondActivityTaskFailed"))
              .build();
        }
      }
    }
    return getRespondActivityTaskFailedMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RespondActivityTaskFailedByIdRequest,
      io.temporal.api.workflowservice.v1.RespondActivityTaskFailedByIdResponse> getRespondActivityTaskFailedByIdMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RespondActivityTaskFailedById",
      requestType = io.temporal.api.workflowservice.v1.RespondActivityTaskFailedByIdRequest.class,
      responseType = io.temporal.api.workflowservice.v1.RespondActivityTaskFailedByIdResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RespondActivityTaskFailedByIdRequest,
      io.temporal.api.workflowservice.v1.RespondActivityTaskFailedByIdResponse> getRespondActivityTaskFailedByIdMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RespondActivityTaskFailedByIdRequest, io.temporal.api.workflowservice.v1.RespondActivityTaskFailedByIdResponse> getRespondActivityTaskFailedByIdMethod;
    if ((getRespondActivityTaskFailedByIdMethod = WorkflowServiceGrpc.getRespondActivityTaskFailedByIdMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getRespondActivityTaskFailedByIdMethod = WorkflowServiceGrpc.getRespondActivityTaskFailedByIdMethod) == null) {
          WorkflowServiceGrpc.getRespondActivityTaskFailedByIdMethod = getRespondActivityTaskFailedByIdMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.RespondActivityTaskFailedByIdRequest, io.temporal.api.workflowservice.v1.RespondActivityTaskFailedByIdResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RespondActivityTaskFailedById"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.RespondActivityTaskFailedByIdRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.RespondActivityTaskFailedByIdResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("RespondActivityTaskFailedById"))
              .build();
        }
      }
    }
    return getRespondActivityTaskFailedByIdMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledRequest,
      io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledResponse> getRespondActivityTaskCanceledMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RespondActivityTaskCanceled",
      requestType = io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledRequest.class,
      responseType = io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledRequest,
      io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledResponse> getRespondActivityTaskCanceledMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledRequest, io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledResponse> getRespondActivityTaskCanceledMethod;
    if ((getRespondActivityTaskCanceledMethod = WorkflowServiceGrpc.getRespondActivityTaskCanceledMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getRespondActivityTaskCanceledMethod = WorkflowServiceGrpc.getRespondActivityTaskCanceledMethod) == null) {
          WorkflowServiceGrpc.getRespondActivityTaskCanceledMethod = getRespondActivityTaskCanceledMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledRequest, io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RespondActivityTaskCanceled"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("RespondActivityTaskCanceled"))
              .build();
        }
      }
    }
    return getRespondActivityTaskCanceledMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledByIdRequest,
      io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledByIdResponse> getRespondActivityTaskCanceledByIdMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RespondActivityTaskCanceledById",
      requestType = io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledByIdRequest.class,
      responseType = io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledByIdResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledByIdRequest,
      io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledByIdResponse> getRespondActivityTaskCanceledByIdMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledByIdRequest, io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledByIdResponse> getRespondActivityTaskCanceledByIdMethod;
    if ((getRespondActivityTaskCanceledByIdMethod = WorkflowServiceGrpc.getRespondActivityTaskCanceledByIdMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getRespondActivityTaskCanceledByIdMethod = WorkflowServiceGrpc.getRespondActivityTaskCanceledByIdMethod) == null) {
          WorkflowServiceGrpc.getRespondActivityTaskCanceledByIdMethod = getRespondActivityTaskCanceledByIdMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledByIdRequest, io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledByIdResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RespondActivityTaskCanceledById"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledByIdRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledByIdResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("RespondActivityTaskCanceledById"))
              .build();
        }
      }
    }
    return getRespondActivityTaskCanceledByIdMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionRequest,
      io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse> getRequestCancelWorkflowExecutionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RequestCancelWorkflowExecution",
      requestType = io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionRequest.class,
      responseType = io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionRequest,
      io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse> getRequestCancelWorkflowExecutionMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionRequest, io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse> getRequestCancelWorkflowExecutionMethod;
    if ((getRequestCancelWorkflowExecutionMethod = WorkflowServiceGrpc.getRequestCancelWorkflowExecutionMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getRequestCancelWorkflowExecutionMethod = WorkflowServiceGrpc.getRequestCancelWorkflowExecutionMethod) == null) {
          WorkflowServiceGrpc.getRequestCancelWorkflowExecutionMethod = getRequestCancelWorkflowExecutionMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionRequest, io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RequestCancelWorkflowExecution"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("RequestCancelWorkflowExecution"))
              .build();
        }
      }
    }
    return getRequestCancelWorkflowExecutionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest,
      io.temporal.api.workflowservice.v1.SignalWorkflowExecutionResponse> getSignalWorkflowExecutionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SignalWorkflowExecution",
      requestType = io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest.class,
      responseType = io.temporal.api.workflowservice.v1.SignalWorkflowExecutionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest,
      io.temporal.api.workflowservice.v1.SignalWorkflowExecutionResponse> getSignalWorkflowExecutionMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest, io.temporal.api.workflowservice.v1.SignalWorkflowExecutionResponse> getSignalWorkflowExecutionMethod;
    if ((getSignalWorkflowExecutionMethod = WorkflowServiceGrpc.getSignalWorkflowExecutionMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getSignalWorkflowExecutionMethod = WorkflowServiceGrpc.getSignalWorkflowExecutionMethod) == null) {
          WorkflowServiceGrpc.getSignalWorkflowExecutionMethod = getSignalWorkflowExecutionMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest, io.temporal.api.workflowservice.v1.SignalWorkflowExecutionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SignalWorkflowExecution"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.SignalWorkflowExecutionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("SignalWorkflowExecution"))
              .build();
        }
      }
    }
    return getSignalWorkflowExecutionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionRequest,
      io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionResponse> getSignalWithStartWorkflowExecutionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SignalWithStartWorkflowExecution",
      requestType = io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionRequest.class,
      responseType = io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionRequest,
      io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionResponse> getSignalWithStartWorkflowExecutionMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionRequest, io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionResponse> getSignalWithStartWorkflowExecutionMethod;
    if ((getSignalWithStartWorkflowExecutionMethod = WorkflowServiceGrpc.getSignalWithStartWorkflowExecutionMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getSignalWithStartWorkflowExecutionMethod = WorkflowServiceGrpc.getSignalWithStartWorkflowExecutionMethod) == null) {
          WorkflowServiceGrpc.getSignalWithStartWorkflowExecutionMethod = getSignalWithStartWorkflowExecutionMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionRequest, io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SignalWithStartWorkflowExecution"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("SignalWithStartWorkflowExecution"))
              .build();
        }
      }
    }
    return getSignalWithStartWorkflowExecutionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.ResetWorkflowExecutionRequest,
      io.temporal.api.workflowservice.v1.ResetWorkflowExecutionResponse> getResetWorkflowExecutionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ResetWorkflowExecution",
      requestType = io.temporal.api.workflowservice.v1.ResetWorkflowExecutionRequest.class,
      responseType = io.temporal.api.workflowservice.v1.ResetWorkflowExecutionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.ResetWorkflowExecutionRequest,
      io.temporal.api.workflowservice.v1.ResetWorkflowExecutionResponse> getResetWorkflowExecutionMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.ResetWorkflowExecutionRequest, io.temporal.api.workflowservice.v1.ResetWorkflowExecutionResponse> getResetWorkflowExecutionMethod;
    if ((getResetWorkflowExecutionMethod = WorkflowServiceGrpc.getResetWorkflowExecutionMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getResetWorkflowExecutionMethod = WorkflowServiceGrpc.getResetWorkflowExecutionMethod) == null) {
          WorkflowServiceGrpc.getResetWorkflowExecutionMethod = getResetWorkflowExecutionMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.ResetWorkflowExecutionRequest, io.temporal.api.workflowservice.v1.ResetWorkflowExecutionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ResetWorkflowExecution"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.ResetWorkflowExecutionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.ResetWorkflowExecutionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("ResetWorkflowExecution"))
              .build();
        }
      }
    }
    return getResetWorkflowExecutionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionRequest,
      io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionResponse> getTerminateWorkflowExecutionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "TerminateWorkflowExecution",
      requestType = io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionRequest.class,
      responseType = io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionRequest,
      io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionResponse> getTerminateWorkflowExecutionMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionRequest, io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionResponse> getTerminateWorkflowExecutionMethod;
    if ((getTerminateWorkflowExecutionMethod = WorkflowServiceGrpc.getTerminateWorkflowExecutionMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getTerminateWorkflowExecutionMethod = WorkflowServiceGrpc.getTerminateWorkflowExecutionMethod) == null) {
          WorkflowServiceGrpc.getTerminateWorkflowExecutionMethod = getTerminateWorkflowExecutionMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionRequest, io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "TerminateWorkflowExecution"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("TerminateWorkflowExecution"))
              .build();
        }
      }
    }
    return getTerminateWorkflowExecutionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsRequest,
      io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsResponse> getListOpenWorkflowExecutionsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListOpenWorkflowExecutions",
      requestType = io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsRequest.class,
      responseType = io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsRequest,
      io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsResponse> getListOpenWorkflowExecutionsMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsRequest, io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsResponse> getListOpenWorkflowExecutionsMethod;
    if ((getListOpenWorkflowExecutionsMethod = WorkflowServiceGrpc.getListOpenWorkflowExecutionsMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getListOpenWorkflowExecutionsMethod = WorkflowServiceGrpc.getListOpenWorkflowExecutionsMethod) == null) {
          WorkflowServiceGrpc.getListOpenWorkflowExecutionsMethod = getListOpenWorkflowExecutionsMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsRequest, io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListOpenWorkflowExecutions"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("ListOpenWorkflowExecutions"))
              .build();
        }
      }
    }
    return getListOpenWorkflowExecutionsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsRequest,
      io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsResponse> getListClosedWorkflowExecutionsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListClosedWorkflowExecutions",
      requestType = io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsRequest.class,
      responseType = io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsRequest,
      io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsResponse> getListClosedWorkflowExecutionsMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsRequest, io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsResponse> getListClosedWorkflowExecutionsMethod;
    if ((getListClosedWorkflowExecutionsMethod = WorkflowServiceGrpc.getListClosedWorkflowExecutionsMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getListClosedWorkflowExecutionsMethod = WorkflowServiceGrpc.getListClosedWorkflowExecutionsMethod) == null) {
          WorkflowServiceGrpc.getListClosedWorkflowExecutionsMethod = getListClosedWorkflowExecutionsMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsRequest, io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListClosedWorkflowExecutions"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("ListClosedWorkflowExecutions"))
              .build();
        }
      }
    }
    return getListClosedWorkflowExecutionsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest,
      io.temporal.api.workflowservice.v1.ListWorkflowExecutionsResponse> getListWorkflowExecutionsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListWorkflowExecutions",
      requestType = io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest.class,
      responseType = io.temporal.api.workflowservice.v1.ListWorkflowExecutionsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest,
      io.temporal.api.workflowservice.v1.ListWorkflowExecutionsResponse> getListWorkflowExecutionsMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest, io.temporal.api.workflowservice.v1.ListWorkflowExecutionsResponse> getListWorkflowExecutionsMethod;
    if ((getListWorkflowExecutionsMethod = WorkflowServiceGrpc.getListWorkflowExecutionsMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getListWorkflowExecutionsMethod = WorkflowServiceGrpc.getListWorkflowExecutionsMethod) == null) {
          WorkflowServiceGrpc.getListWorkflowExecutionsMethod = getListWorkflowExecutionsMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest, io.temporal.api.workflowservice.v1.ListWorkflowExecutionsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListWorkflowExecutions"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.ListWorkflowExecutionsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("ListWorkflowExecutions"))
              .build();
        }
      }
    }
    return getListWorkflowExecutionsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.ListArchivedWorkflowExecutionsRequest,
      io.temporal.api.workflowservice.v1.ListArchivedWorkflowExecutionsResponse> getListArchivedWorkflowExecutionsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListArchivedWorkflowExecutions",
      requestType = io.temporal.api.workflowservice.v1.ListArchivedWorkflowExecutionsRequest.class,
      responseType = io.temporal.api.workflowservice.v1.ListArchivedWorkflowExecutionsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.ListArchivedWorkflowExecutionsRequest,
      io.temporal.api.workflowservice.v1.ListArchivedWorkflowExecutionsResponse> getListArchivedWorkflowExecutionsMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.ListArchivedWorkflowExecutionsRequest, io.temporal.api.workflowservice.v1.ListArchivedWorkflowExecutionsResponse> getListArchivedWorkflowExecutionsMethod;
    if ((getListArchivedWorkflowExecutionsMethod = WorkflowServiceGrpc.getListArchivedWorkflowExecutionsMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getListArchivedWorkflowExecutionsMethod = WorkflowServiceGrpc.getListArchivedWorkflowExecutionsMethod) == null) {
          WorkflowServiceGrpc.getListArchivedWorkflowExecutionsMethod = getListArchivedWorkflowExecutionsMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.ListArchivedWorkflowExecutionsRequest, io.temporal.api.workflowservice.v1.ListArchivedWorkflowExecutionsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListArchivedWorkflowExecutions"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.ListArchivedWorkflowExecutionsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.ListArchivedWorkflowExecutionsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("ListArchivedWorkflowExecutions"))
              .build();
        }
      }
    }
    return getListArchivedWorkflowExecutionsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.ScanWorkflowExecutionsRequest,
      io.temporal.api.workflowservice.v1.ScanWorkflowExecutionsResponse> getScanWorkflowExecutionsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ScanWorkflowExecutions",
      requestType = io.temporal.api.workflowservice.v1.ScanWorkflowExecutionsRequest.class,
      responseType = io.temporal.api.workflowservice.v1.ScanWorkflowExecutionsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.ScanWorkflowExecutionsRequest,
      io.temporal.api.workflowservice.v1.ScanWorkflowExecutionsResponse> getScanWorkflowExecutionsMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.ScanWorkflowExecutionsRequest, io.temporal.api.workflowservice.v1.ScanWorkflowExecutionsResponse> getScanWorkflowExecutionsMethod;
    if ((getScanWorkflowExecutionsMethod = WorkflowServiceGrpc.getScanWorkflowExecutionsMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getScanWorkflowExecutionsMethod = WorkflowServiceGrpc.getScanWorkflowExecutionsMethod) == null) {
          WorkflowServiceGrpc.getScanWorkflowExecutionsMethod = getScanWorkflowExecutionsMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.ScanWorkflowExecutionsRequest, io.temporal.api.workflowservice.v1.ScanWorkflowExecutionsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ScanWorkflowExecutions"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.ScanWorkflowExecutionsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.ScanWorkflowExecutionsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("ScanWorkflowExecutions"))
              .build();
        }
      }
    }
    return getScanWorkflowExecutionsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.CountWorkflowExecutionsRequest,
      io.temporal.api.workflowservice.v1.CountWorkflowExecutionsResponse> getCountWorkflowExecutionsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CountWorkflowExecutions",
      requestType = io.temporal.api.workflowservice.v1.CountWorkflowExecutionsRequest.class,
      responseType = io.temporal.api.workflowservice.v1.CountWorkflowExecutionsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.CountWorkflowExecutionsRequest,
      io.temporal.api.workflowservice.v1.CountWorkflowExecutionsResponse> getCountWorkflowExecutionsMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.CountWorkflowExecutionsRequest, io.temporal.api.workflowservice.v1.CountWorkflowExecutionsResponse> getCountWorkflowExecutionsMethod;
    if ((getCountWorkflowExecutionsMethod = WorkflowServiceGrpc.getCountWorkflowExecutionsMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getCountWorkflowExecutionsMethod = WorkflowServiceGrpc.getCountWorkflowExecutionsMethod) == null) {
          WorkflowServiceGrpc.getCountWorkflowExecutionsMethod = getCountWorkflowExecutionsMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.CountWorkflowExecutionsRequest, io.temporal.api.workflowservice.v1.CountWorkflowExecutionsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CountWorkflowExecutions"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.CountWorkflowExecutionsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.CountWorkflowExecutionsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("CountWorkflowExecutions"))
              .build();
        }
      }
    }
    return getCountWorkflowExecutionsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.GetSearchAttributesRequest,
      io.temporal.api.workflowservice.v1.GetSearchAttributesResponse> getGetSearchAttributesMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetSearchAttributes",
      requestType = io.temporal.api.workflowservice.v1.GetSearchAttributesRequest.class,
      responseType = io.temporal.api.workflowservice.v1.GetSearchAttributesResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.GetSearchAttributesRequest,
      io.temporal.api.workflowservice.v1.GetSearchAttributesResponse> getGetSearchAttributesMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.GetSearchAttributesRequest, io.temporal.api.workflowservice.v1.GetSearchAttributesResponse> getGetSearchAttributesMethod;
    if ((getGetSearchAttributesMethod = WorkflowServiceGrpc.getGetSearchAttributesMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getGetSearchAttributesMethod = WorkflowServiceGrpc.getGetSearchAttributesMethod) == null) {
          WorkflowServiceGrpc.getGetSearchAttributesMethod = getGetSearchAttributesMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.GetSearchAttributesRequest, io.temporal.api.workflowservice.v1.GetSearchAttributesResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetSearchAttributes"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.GetSearchAttributesRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.GetSearchAttributesResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("GetSearchAttributes"))
              .build();
        }
      }
    }
    return getGetSearchAttributesMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest,
      io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedResponse> getRespondQueryTaskCompletedMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RespondQueryTaskCompleted",
      requestType = io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest.class,
      responseType = io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest,
      io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedResponse> getRespondQueryTaskCompletedMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest, io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedResponse> getRespondQueryTaskCompletedMethod;
    if ((getRespondQueryTaskCompletedMethod = WorkflowServiceGrpc.getRespondQueryTaskCompletedMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getRespondQueryTaskCompletedMethod = WorkflowServiceGrpc.getRespondQueryTaskCompletedMethod) == null) {
          WorkflowServiceGrpc.getRespondQueryTaskCompletedMethod = getRespondQueryTaskCompletedMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest, io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RespondQueryTaskCompleted"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("RespondQueryTaskCompleted"))
              .build();
        }
      }
    }
    return getRespondQueryTaskCompletedMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.ResetStickyTaskQueueRequest,
      io.temporal.api.workflowservice.v1.ResetStickyTaskQueueResponse> getResetStickyTaskQueueMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ResetStickyTaskQueue",
      requestType = io.temporal.api.workflowservice.v1.ResetStickyTaskQueueRequest.class,
      responseType = io.temporal.api.workflowservice.v1.ResetStickyTaskQueueResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.ResetStickyTaskQueueRequest,
      io.temporal.api.workflowservice.v1.ResetStickyTaskQueueResponse> getResetStickyTaskQueueMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.ResetStickyTaskQueueRequest, io.temporal.api.workflowservice.v1.ResetStickyTaskQueueResponse> getResetStickyTaskQueueMethod;
    if ((getResetStickyTaskQueueMethod = WorkflowServiceGrpc.getResetStickyTaskQueueMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getResetStickyTaskQueueMethod = WorkflowServiceGrpc.getResetStickyTaskQueueMethod) == null) {
          WorkflowServiceGrpc.getResetStickyTaskQueueMethod = getResetStickyTaskQueueMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.ResetStickyTaskQueueRequest, io.temporal.api.workflowservice.v1.ResetStickyTaskQueueResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ResetStickyTaskQueue"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.ResetStickyTaskQueueRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.ResetStickyTaskQueueResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("ResetStickyTaskQueue"))
              .build();
        }
      }
    }
    return getResetStickyTaskQueueMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.QueryWorkflowRequest,
      io.temporal.api.workflowservice.v1.QueryWorkflowResponse> getQueryWorkflowMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "QueryWorkflow",
      requestType = io.temporal.api.workflowservice.v1.QueryWorkflowRequest.class,
      responseType = io.temporal.api.workflowservice.v1.QueryWorkflowResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.QueryWorkflowRequest,
      io.temporal.api.workflowservice.v1.QueryWorkflowResponse> getQueryWorkflowMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.QueryWorkflowRequest, io.temporal.api.workflowservice.v1.QueryWorkflowResponse> getQueryWorkflowMethod;
    if ((getQueryWorkflowMethod = WorkflowServiceGrpc.getQueryWorkflowMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getQueryWorkflowMethod = WorkflowServiceGrpc.getQueryWorkflowMethod) == null) {
          WorkflowServiceGrpc.getQueryWorkflowMethod = getQueryWorkflowMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.QueryWorkflowRequest, io.temporal.api.workflowservice.v1.QueryWorkflowResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "QueryWorkflow"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.QueryWorkflowRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.QueryWorkflowResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("QueryWorkflow"))
              .build();
        }
      }
    }
    return getQueryWorkflowMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest,
      io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse> getDescribeWorkflowExecutionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DescribeWorkflowExecution",
      requestType = io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest.class,
      responseType = io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest,
      io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse> getDescribeWorkflowExecutionMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest, io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse> getDescribeWorkflowExecutionMethod;
    if ((getDescribeWorkflowExecutionMethod = WorkflowServiceGrpc.getDescribeWorkflowExecutionMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getDescribeWorkflowExecutionMethod = WorkflowServiceGrpc.getDescribeWorkflowExecutionMethod) == null) {
          WorkflowServiceGrpc.getDescribeWorkflowExecutionMethod = getDescribeWorkflowExecutionMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest, io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DescribeWorkflowExecution"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("DescribeWorkflowExecution"))
              .build();
        }
      }
    }
    return getDescribeWorkflowExecutionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.DescribeTaskQueueRequest,
      io.temporal.api.workflowservice.v1.DescribeTaskQueueResponse> getDescribeTaskQueueMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DescribeTaskQueue",
      requestType = io.temporal.api.workflowservice.v1.DescribeTaskQueueRequest.class,
      responseType = io.temporal.api.workflowservice.v1.DescribeTaskQueueResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.DescribeTaskQueueRequest,
      io.temporal.api.workflowservice.v1.DescribeTaskQueueResponse> getDescribeTaskQueueMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.DescribeTaskQueueRequest, io.temporal.api.workflowservice.v1.DescribeTaskQueueResponse> getDescribeTaskQueueMethod;
    if ((getDescribeTaskQueueMethod = WorkflowServiceGrpc.getDescribeTaskQueueMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getDescribeTaskQueueMethod = WorkflowServiceGrpc.getDescribeTaskQueueMethod) == null) {
          WorkflowServiceGrpc.getDescribeTaskQueueMethod = getDescribeTaskQueueMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.DescribeTaskQueueRequest, io.temporal.api.workflowservice.v1.DescribeTaskQueueResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DescribeTaskQueue"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.DescribeTaskQueueRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.DescribeTaskQueueResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("DescribeTaskQueue"))
              .build();
        }
      }
    }
    return getDescribeTaskQueueMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.GetClusterInfoRequest,
      io.temporal.api.workflowservice.v1.GetClusterInfoResponse> getGetClusterInfoMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetClusterInfo",
      requestType = io.temporal.api.workflowservice.v1.GetClusterInfoRequest.class,
      responseType = io.temporal.api.workflowservice.v1.GetClusterInfoResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.GetClusterInfoRequest,
      io.temporal.api.workflowservice.v1.GetClusterInfoResponse> getGetClusterInfoMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.GetClusterInfoRequest, io.temporal.api.workflowservice.v1.GetClusterInfoResponse> getGetClusterInfoMethod;
    if ((getGetClusterInfoMethod = WorkflowServiceGrpc.getGetClusterInfoMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getGetClusterInfoMethod = WorkflowServiceGrpc.getGetClusterInfoMethod) == null) {
          WorkflowServiceGrpc.getGetClusterInfoMethod = getGetClusterInfoMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.GetClusterInfoRequest, io.temporal.api.workflowservice.v1.GetClusterInfoResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetClusterInfo"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.GetClusterInfoRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.GetClusterInfoResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("GetClusterInfo"))
              .build();
        }
      }
    }
    return getGetClusterInfoMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.ListTaskQueuePartitionsRequest,
      io.temporal.api.workflowservice.v1.ListTaskQueuePartitionsResponse> getListTaskQueuePartitionsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListTaskQueuePartitions",
      requestType = io.temporal.api.workflowservice.v1.ListTaskQueuePartitionsRequest.class,
      responseType = io.temporal.api.workflowservice.v1.ListTaskQueuePartitionsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.ListTaskQueuePartitionsRequest,
      io.temporal.api.workflowservice.v1.ListTaskQueuePartitionsResponse> getListTaskQueuePartitionsMethod() {
    io.grpc.MethodDescriptor<io.temporal.api.workflowservice.v1.ListTaskQueuePartitionsRequest, io.temporal.api.workflowservice.v1.ListTaskQueuePartitionsResponse> getListTaskQueuePartitionsMethod;
    if ((getListTaskQueuePartitionsMethod = WorkflowServiceGrpc.getListTaskQueuePartitionsMethod) == null) {
      synchronized (WorkflowServiceGrpc.class) {
        if ((getListTaskQueuePartitionsMethod = WorkflowServiceGrpc.getListTaskQueuePartitionsMethod) == null) {
          WorkflowServiceGrpc.getListTaskQueuePartitionsMethod = getListTaskQueuePartitionsMethod =
              io.grpc.MethodDescriptor.<io.temporal.api.workflowservice.v1.ListTaskQueuePartitionsRequest, io.temporal.api.workflowservice.v1.ListTaskQueuePartitionsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListTaskQueuePartitions"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.ListTaskQueuePartitionsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.temporal.api.workflowservice.v1.ListTaskQueuePartitionsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkflowServiceMethodDescriptorSupplier("ListTaskQueuePartitions"))
              .build();
        }
      }
    }
    return getListTaskQueuePartitionsMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static WorkflowServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<WorkflowServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<WorkflowServiceStub>() {
        @java.lang.Override
        public WorkflowServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new WorkflowServiceStub(channel, callOptions);
        }
      };
    return WorkflowServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static WorkflowServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<WorkflowServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<WorkflowServiceBlockingStub>() {
        @java.lang.Override
        public WorkflowServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new WorkflowServiceBlockingStub(channel, callOptions);
        }
      };
    return WorkflowServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static WorkflowServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<WorkflowServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<WorkflowServiceFutureStub>() {
        @java.lang.Override
        public WorkflowServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new WorkflowServiceFutureStub(channel, callOptions);
        }
      };
    return WorkflowServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * WorkflowService API is exposed to provide support for long running applications.  Application is expected to call
   * StartWorkflowExecution to create an instance for each instance of long running workflow.  Such applications are expected
   * to have a worker which regularly polls for WorkflowTask and ActivityTask from the WorkflowService.  For each
   * WorkflowTask, application is expected to process the history of events for that session and respond back with next
   * commands.  For each ActivityTask, application is expected to execute the actual logic for that task and respond back
   * with completion or failure.  Worker is expected to regularly heartbeat while activity task is running.
   * </pre>
   */
  public static abstract class WorkflowServiceImplBase implements io.grpc.BindableService {

    /**
     * <pre>
     * RegisterNamespace creates a new namespace which can be used as a container for all resources.  Namespace is a top level
     * entity within Temporal, used as a container for all resources like workflow executions, task queues, etc.  Namespace
     * acts as a sandbox and provides isolation for all resources within the namespace.  All resources belongs to exactly one
     * namespace.
     * </pre>
     */
    public void registerNamespace(io.temporal.api.workflowservice.v1.RegisterNamespaceRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RegisterNamespaceResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getRegisterNamespaceMethod(), responseObserver);
    }

    /**
     * <pre>
     * DescribeNamespace returns the information and configuration for a registered namespace.
     * </pre>
     */
    public void describeNamespace(io.temporal.api.workflowservice.v1.DescribeNamespaceRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.DescribeNamespaceResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getDescribeNamespaceMethod(), responseObserver);
    }

    /**
     * <pre>
     * ListNamespaces returns the information and configuration for all namespaces.
     * </pre>
     */
    public void listNamespaces(io.temporal.api.workflowservice.v1.ListNamespacesRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.ListNamespacesResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getListNamespacesMethod(), responseObserver);
    }

    /**
     * <pre>
     * (-- api-linter: core::0134::method-signature=disabled
     *     aip.dev/not-precedent: UpdateNamespace RPC doesn't follow Google API format. --)
     * (-- api-linter: core::0134::response-message-name=disabled
     *     aip.dev/not-precedent: UpdateNamespace RPC doesn't follow Google API format. --)
     * UpdateNamespace is used to update the information and configuration for a registered namespace.
     * </pre>
     */
    public void updateNamespace(io.temporal.api.workflowservice.v1.UpdateNamespaceRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.UpdateNamespaceResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getUpdateNamespaceMethod(), responseObserver);
    }

    /**
     * <pre>
     * DeprecateNamespace is used to update state of a registered namespace to DEPRECATED.  Once the namespace is deprecated
     * it cannot be used to start new workflow executions.  Existing workflow executions will continue to run on
     * deprecated namespaces.
     * </pre>
     */
    public void deprecateNamespace(io.temporal.api.workflowservice.v1.DeprecateNamespaceRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.DeprecateNamespaceResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getDeprecateNamespaceMethod(), responseObserver);
    }

    /**
     * <pre>
     * StartWorkflowExecution starts a new long running workflow instance.  It will create the instance with
     * 'WorkflowExecutionStarted' event in history and also schedule the first WorkflowTask for the worker to make the
     * first command for this instance.  It will return 'WorkflowExecutionAlreadyStartedFailure', if an instance already
     * exists with same workflowId.
     * </pre>
     */
    public void startWorkflowExecution(io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.StartWorkflowExecutionResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getStartWorkflowExecutionMethod(), responseObserver);
    }

    /**
     * <pre>
     * GetWorkflowExecutionHistory returns the history of specified workflow execution.  It fails with 'NotFoundFailure' if specified workflow
     * execution in unknown to the service.
     * </pre>
     */
    public void getWorkflowExecutionHistory(io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getGetWorkflowExecutionHistoryMethod(), responseObserver);
    }

    /**
     * <pre>
     * PollWorkflowTaskQueue is called by application worker to process WorkflowTask from a specific task queue.  A
     * WorkflowTask is dispatched to callers for active workflow executions, with pending workflow tasks.
     * Application is then expected to call 'RespondWorkflowTaskCompleted' API when it is done processing the WorkflowTask.
     * It will also create a 'WorkflowTaskStarted' event in the history for that session before handing off WorkflowTask to
     * application worker.
     * </pre>
     */
    public void pollWorkflowTaskQueue(io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getPollWorkflowTaskQueueMethod(), responseObserver);
    }

    /**
     * <pre>
     * RespondWorkflowTaskCompleted is called by application worker to complete a WorkflowTask handed as a result of
     * 'PollWorkflowTaskQueue' API call.  Completing a WorkflowTask will result in new events for the workflow execution and
     * potentially new ActivityTask being created for corresponding commands.  It will also create a WorkflowTaskCompleted
     * event in the history for that session.  Use the 'taskToken' provided as response of PollWorkflowTaskQueue API call
     * for completing the WorkflowTask.
     * The response could contain a new workflow task if there is one or if the request asking for one.
     * </pre>
     */
    public void respondWorkflowTaskCompleted(io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getRespondWorkflowTaskCompletedMethod(), responseObserver);
    }

    /**
     * <pre>
     * RespondWorkflowTaskFailed is called by application worker to indicate failure.  This results in
     * WorkflowTaskFailedEvent written to the history and a new WorkflowTask created.  This API can be used by client to
     * either clear sticky task queue or report any panics during WorkflowTask processing.  Temporal will only append first
     * WorkflowTaskFailed event to the history of workflow execution for consecutive failures.
     * </pre>
     */
    public void respondWorkflowTaskFailed(io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getRespondWorkflowTaskFailedMethod(), responseObserver);
    }

    /**
     * <pre>
     * PollActivityTaskQueue is called by application worker to process ActivityTask from a specific task queue.  ActivityTask
     * is dispatched to callers whenever a ScheduleTask command is made for a workflow execution.
     * Application is expected to call 'RespondActivityTaskCompleted' or 'RespondActivityTaskFailed' once it is done
     * processing the task.
     * Application also needs to call 'RecordActivityTaskHeartbeat' API within 'heartbeatTimeoutSeconds' interval to
     * prevent the task from getting timed out.  An event 'ActivityTaskStarted' event is also written to workflow execution
     * history before the ActivityTask is dispatched to application worker.
     * </pre>
     */
    public void pollActivityTaskQueue(io.temporal.api.workflowservice.v1.PollActivityTaskQueueRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getPollActivityTaskQueueMethod(), responseObserver);
    }

    /**
     * <pre>
     * RecordActivityTaskHeartbeat is called by application worker while it is processing an ActivityTask.  If worker fails
     * to heartbeat within 'heartbeatTimeoutSeconds' interval for the ActivityTask, then it will be marked as timedout and
     * 'ActivityTaskTimedOut' event will be written to the workflow history.  Calling 'RecordActivityTaskHeartbeat' will
     * fail with 'NotFoundFailure' in such situations.  Use the 'taskToken' provided as response of
     * PollActivityTaskQueue API call for heart beating.
     * </pre>
     */
    public void recordActivityTaskHeartbeat(io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getRecordActivityTaskHeartbeatMethod(), responseObserver);
    }

    /**
     * <pre>
     * (-- api-linter: core::0136::prepositions=disabled
     *     aip.dev/not-precedent: "By" is used to indicate request type. --)
     * RecordActivityTaskHeartbeatById is called by application worker while it is processing an ActivityTask.  If worker fails
     * to heartbeat within 'heartbeatTimeoutSeconds' interval for the ActivityTask, then it will be marked as timed out and
     * 'ActivityTaskTimedOut' event will be written to the workflow history.  Calling 'RecordActivityTaskHeartbeatById' will
     * fail with 'NotFoundFailure' in such situations.  Instead of using 'taskToken' like in RecordActivityTaskHeartbeat,
     * use Namespace, WorkflowId and ActivityId
     * </pre>
     */
    public void recordActivityTaskHeartbeatById(io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getRecordActivityTaskHeartbeatByIdMethod(), responseObserver);
    }

    /**
     * <pre>
     * RespondActivityTaskCompleted is called by application worker when it is done processing an ActivityTask.  It will
     * result in a new 'ActivityTaskCompleted' event being written to the workflow history and a new WorkflowTask
     * created for the workflow so new commands could be made.  Use the 'taskToken' provided as response of
     * PollActivityTaskQueue API call for completion. It fails with 'NotFoundFailure' if the taskToken is not valid
     * anymore due to activity timeout.
     * </pre>
     */
    public void respondActivityTaskCompleted(io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getRespondActivityTaskCompletedMethod(), responseObserver);
    }

    /**
     * <pre>
     * (-- api-linter: core::0136::prepositions=disabled
     *     aip.dev/not-precedent: "By" is used to indicate request type. --)
     * RespondActivityTaskCompletedById is called by application worker when it is done processing an ActivityTask.
     * It will result in a new 'ActivityTaskCompleted' event being written to the workflow history and a new WorkflowTask
     * created for the workflow so new commands could be made.  Similar to RespondActivityTaskCompleted but use Namespace,
     * WorkflowId and ActivityId instead of 'taskToken' for completion. It fails with 'NotFoundFailure'
     * if the these Ids are not valid anymore due to activity timeout.
     * </pre>
     */
    public void respondActivityTaskCompletedById(io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedByIdRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedByIdResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getRespondActivityTaskCompletedByIdMethod(), responseObserver);
    }

    /**
     * <pre>
     * RespondActivityTaskFailed is called by application worker when it is done processing an ActivityTask.  It will
     * result in a new 'ActivityTaskFailed' event being written to the workflow history and a new WorkflowTask
     * created for the workflow instance so new commands could be made.  Use the 'taskToken' provided as response of
     * PollActivityTaskQueue API call for completion. It fails with 'NotFoundFailure' if the taskToken is not valid
     * anymore due to activity timeout.
     * </pre>
     */
    public void respondActivityTaskFailed(io.temporal.api.workflowservice.v1.RespondActivityTaskFailedRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RespondActivityTaskFailedResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getRespondActivityTaskFailedMethod(), responseObserver);
    }

    /**
     * <pre>
     * (-- api-linter: core::0136::prepositions=disabled
     *     aip.dev/not-precedent: "By" is used to indicate request type. --)
     * RespondActivityTaskFailedById is called by application worker when it is done processing an ActivityTask.
     * It will result in a new 'ActivityTaskFailed' event being written to the workflow history and a new WorkflowTask
     * created for the workflow instance so new commands could be made.  Similar to RespondActivityTaskFailed but use
     * Namespace, WorkflowId and ActivityId instead of 'taskToken' for completion. It fails with 'NotFoundFailure'
     * if the these Ids are not valid anymore due to activity timeout.
     * </pre>
     */
    public void respondActivityTaskFailedById(io.temporal.api.workflowservice.v1.RespondActivityTaskFailedByIdRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RespondActivityTaskFailedByIdResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getRespondActivityTaskFailedByIdMethod(), responseObserver);
    }

    /**
     * <pre>
     * RespondActivityTaskCanceled is called by application worker when it is successfully canceled an ActivityTask.  It will
     * result in a new 'ActivityTaskCanceled' event being written to the workflow history and a new WorkflowTask
     * created for the workflow instance so new commands could be made.  Use the 'taskToken' provided as response of
     * PollActivityTaskQueue API call for completion. It fails with 'NotFoundFailure' if the taskToken is not valid
     * anymore due to activity timeout.
     * </pre>
     */
    public void respondActivityTaskCanceled(io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getRespondActivityTaskCanceledMethod(), responseObserver);
    }

    /**
     * <pre>
     * (-- api-linter: core::0136::prepositions=disabled
     *     aip.dev/not-precedent: "By" is used to indicate request type. --)
     * RespondActivityTaskCanceledById is called by application worker when it is successfully canceled an ActivityTask.
     * It will result in a new 'ActivityTaskCanceled' event being written to the workflow history and a new WorkflowTask
     * created for the workflow instance so new commands could be made.  Similar to RespondActivityTaskCanceled but use
     * Namespace, WorkflowId and ActivityId instead of 'taskToken' for completion. It fails with 'NotFoundFailure'
     * if the these Ids are not valid anymore due to activity timeout.
     * </pre>
     */
    public void respondActivityTaskCanceledById(io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledByIdRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledByIdResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getRespondActivityTaskCanceledByIdMethod(), responseObserver);
    }

    /**
     * <pre>
     * RequestCancelWorkflowExecution is called by application worker when it wants to request cancellation of a workflow instance.
     * It will result in a new 'WorkflowExecutionCancelRequested' event being written to the workflow history and a new WorkflowTask
     * created for the workflow instance so new commands could be made. It fails with 'NotFoundFailure' if the workflow is not valid
     * anymore due to completion or doesn't exist.
     * </pre>
     */
    public void requestCancelWorkflowExecution(io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getRequestCancelWorkflowExecutionMethod(), responseObserver);
    }

    /**
     * <pre>
     * SignalWorkflowExecution is used to send a signal event to running workflow execution.  This results in
     * WorkflowExecutionSignaled event recorded in the history and a workflow task being created for the execution.
     * </pre>
     */
    public void signalWorkflowExecution(io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.SignalWorkflowExecutionResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getSignalWorkflowExecutionMethod(), responseObserver);
    }

    /**
     * <pre>
     * (-- api-linter: core::0136::prepositions=disabled
     *     aip.dev/not-precedent: "With" is used to indicate combined operation. --)
     * SignalWithStartWorkflowExecution is used to ensure sending signal to a workflow.
     * If the workflow is running, this results in WorkflowExecutionSignaled event being recorded in the history
     * and a workflow task being created for the execution.
     * If the workflow is not running or not found, this results in WorkflowExecutionStarted and WorkflowExecutionSignaled
     * events being recorded in history, and a workflow task being created for the execution
     * </pre>
     */
    public void signalWithStartWorkflowExecution(io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getSignalWithStartWorkflowExecutionMethod(), responseObserver);
    }

    /**
     * <pre>
     * ResetWorkflowExecution reset an existing workflow execution to WorkflowTaskCompleted event(exclusive).
     * And it will immediately terminating the current execution instance.
     * </pre>
     */
    public void resetWorkflowExecution(io.temporal.api.workflowservice.v1.ResetWorkflowExecutionRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.ResetWorkflowExecutionResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getResetWorkflowExecutionMethod(), responseObserver);
    }

    /**
     * <pre>
     * TerminateWorkflowExecution terminates an existing workflow execution by recording WorkflowExecutionTerminated event
     * in the history and immediately terminating the execution instance.
     * </pre>
     */
    public void terminateWorkflowExecution(io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getTerminateWorkflowExecutionMethod(), responseObserver);
    }

    /**
     * <pre>
     * ListOpenWorkflowExecutions is a visibility API to list the open executions in a specific namespace.
     * </pre>
     */
    public void listOpenWorkflowExecutions(io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getListOpenWorkflowExecutionsMethod(), responseObserver);
    }

    /**
     * <pre>
     * ListClosedWorkflowExecutions is a visibility API to list the closed executions in a specific namespace.
     * </pre>
     */
    public void listClosedWorkflowExecutions(io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getListClosedWorkflowExecutionsMethod(), responseObserver);
    }

    /**
     * <pre>
     * ListWorkflowExecutions is a visibility API to list workflow executions in a specific namespace.
     * </pre>
     */
    public void listWorkflowExecutions(io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.ListWorkflowExecutionsResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getListWorkflowExecutionsMethod(), responseObserver);
    }

    /**
     * <pre>
     * ListArchivedWorkflowExecutions is a visibility API to list archived workflow executions in a specific namespace.
     * </pre>
     */
    public void listArchivedWorkflowExecutions(io.temporal.api.workflowservice.v1.ListArchivedWorkflowExecutionsRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.ListArchivedWorkflowExecutionsResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getListArchivedWorkflowExecutionsMethod(), responseObserver);
    }

    /**
     * <pre>
     * ScanWorkflowExecutions is a visibility API to list large amount of workflow executions in a specific namespace without order.
     * </pre>
     */
    public void scanWorkflowExecutions(io.temporal.api.workflowservice.v1.ScanWorkflowExecutionsRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.ScanWorkflowExecutionsResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getScanWorkflowExecutionsMethod(), responseObserver);
    }

    /**
     * <pre>
     * CountWorkflowExecutions is a visibility API to count of workflow executions in a specific namespace.
     * </pre>
     */
    public void countWorkflowExecutions(io.temporal.api.workflowservice.v1.CountWorkflowExecutionsRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.CountWorkflowExecutionsResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getCountWorkflowExecutionsMethod(), responseObserver);
    }

    /**
     * <pre>
     * GetSearchAttributes is a visibility API to get all legal keys that could be used in list APIs
     * </pre>
     */
    public void getSearchAttributes(io.temporal.api.workflowservice.v1.GetSearchAttributesRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.GetSearchAttributesResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getGetSearchAttributesMethod(), responseObserver);
    }

    /**
     * <pre>
     * RespondQueryTaskCompleted is called by application worker to complete a QueryTask (which is a WorkflowTask for query)
     * as a result of 'PollWorkflowTaskQueue' API call. Completing a QueryTask will unblock the client call to 'QueryWorkflow'
     * API and return the query result to client as a response to 'QueryWorkflow' API call.
     * </pre>
     */
    public void respondQueryTaskCompleted(io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getRespondQueryTaskCompletedMethod(), responseObserver);
    }

    /**
     * <pre>
     * ResetStickyTaskQueue resets the sticky task queue related information in mutable state of a given workflow.
     * Things cleared are:
     * 1. StickyTaskQueue
     * 2. StickyScheduleToStartTimeout
     * 3. ClientLibraryVersion
     * 4. ClientFeatureVersion
     * 5. ClientImpl
     * </pre>
     */
    public void resetStickyTaskQueue(io.temporal.api.workflowservice.v1.ResetStickyTaskQueueRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.ResetStickyTaskQueueResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getResetStickyTaskQueueMethod(), responseObserver);
    }

    /**
     * <pre>
     * QueryWorkflow returns query result for a specified workflow execution
     * </pre>
     */
    public void queryWorkflow(io.temporal.api.workflowservice.v1.QueryWorkflowRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.QueryWorkflowResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getQueryWorkflowMethod(), responseObserver);
    }

    /**
     * <pre>
     * DescribeWorkflowExecution returns information about the specified workflow execution.
     * </pre>
     */
    public void describeWorkflowExecution(io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getDescribeWorkflowExecutionMethod(), responseObserver);
    }

    /**
     * <pre>
     * DescribeTaskQueue returns information about the target task queue, right now this API returns the
     * pollers which polled this task queue in last few minutes.
     * </pre>
     */
    public void describeTaskQueue(io.temporal.api.workflowservice.v1.DescribeTaskQueueRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.DescribeTaskQueueResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getDescribeTaskQueueMethod(), responseObserver);
    }

    /**
     * <pre>
     * GetClusterInfo returns information about temporal cluster
     * </pre>
     */
    public void getClusterInfo(io.temporal.api.workflowservice.v1.GetClusterInfoRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.GetClusterInfoResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getGetClusterInfoMethod(), responseObserver);
    }

    /**
     */
    public void listTaskQueuePartitions(io.temporal.api.workflowservice.v1.ListTaskQueuePartitionsRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.ListTaskQueuePartitionsResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getListTaskQueuePartitionsMethod(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getRegisterNamespaceMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.RegisterNamespaceRequest,
                io.temporal.api.workflowservice.v1.RegisterNamespaceResponse>(
                  this, METHODID_REGISTER_NAMESPACE)))
          .addMethod(
            getDescribeNamespaceMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.DescribeNamespaceRequest,
                io.temporal.api.workflowservice.v1.DescribeNamespaceResponse>(
                  this, METHODID_DESCRIBE_NAMESPACE)))
          .addMethod(
            getListNamespacesMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.ListNamespacesRequest,
                io.temporal.api.workflowservice.v1.ListNamespacesResponse>(
                  this, METHODID_LIST_NAMESPACES)))
          .addMethod(
            getUpdateNamespaceMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.UpdateNamespaceRequest,
                io.temporal.api.workflowservice.v1.UpdateNamespaceResponse>(
                  this, METHODID_UPDATE_NAMESPACE)))
          .addMethod(
            getDeprecateNamespaceMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.DeprecateNamespaceRequest,
                io.temporal.api.workflowservice.v1.DeprecateNamespaceResponse>(
                  this, METHODID_DEPRECATE_NAMESPACE)))
          .addMethod(
            getStartWorkflowExecutionMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest,
                io.temporal.api.workflowservice.v1.StartWorkflowExecutionResponse>(
                  this, METHODID_START_WORKFLOW_EXECUTION)))
          .addMethod(
            getGetWorkflowExecutionHistoryMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest,
                io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse>(
                  this, METHODID_GET_WORKFLOW_EXECUTION_HISTORY)))
          .addMethod(
            getPollWorkflowTaskQueueMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueRequest,
                io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse>(
                  this, METHODID_POLL_WORKFLOW_TASK_QUEUE)))
          .addMethod(
            getRespondWorkflowTaskCompletedMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest,
                io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse>(
                  this, METHODID_RESPOND_WORKFLOW_TASK_COMPLETED)))
          .addMethod(
            getRespondWorkflowTaskFailedMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest,
                io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedResponse>(
                  this, METHODID_RESPOND_WORKFLOW_TASK_FAILED)))
          .addMethod(
            getPollActivityTaskQueueMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.PollActivityTaskQueueRequest,
                io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse>(
                  this, METHODID_POLL_ACTIVITY_TASK_QUEUE)))
          .addMethod(
            getRecordActivityTaskHeartbeatMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatRequest,
                io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatResponse>(
                  this, METHODID_RECORD_ACTIVITY_TASK_HEARTBEAT)))
          .addMethod(
            getRecordActivityTaskHeartbeatByIdMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdRequest,
                io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdResponse>(
                  this, METHODID_RECORD_ACTIVITY_TASK_HEARTBEAT_BY_ID)))
          .addMethod(
            getRespondActivityTaskCompletedMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedRequest,
                io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedResponse>(
                  this, METHODID_RESPOND_ACTIVITY_TASK_COMPLETED)))
          .addMethod(
            getRespondActivityTaskCompletedByIdMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedByIdRequest,
                io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedByIdResponse>(
                  this, METHODID_RESPOND_ACTIVITY_TASK_COMPLETED_BY_ID)))
          .addMethod(
            getRespondActivityTaskFailedMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.RespondActivityTaskFailedRequest,
                io.temporal.api.workflowservice.v1.RespondActivityTaskFailedResponse>(
                  this, METHODID_RESPOND_ACTIVITY_TASK_FAILED)))
          .addMethod(
            getRespondActivityTaskFailedByIdMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.RespondActivityTaskFailedByIdRequest,
                io.temporal.api.workflowservice.v1.RespondActivityTaskFailedByIdResponse>(
                  this, METHODID_RESPOND_ACTIVITY_TASK_FAILED_BY_ID)))
          .addMethod(
            getRespondActivityTaskCanceledMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledRequest,
                io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledResponse>(
                  this, METHODID_RESPOND_ACTIVITY_TASK_CANCELED)))
          .addMethod(
            getRespondActivityTaskCanceledByIdMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledByIdRequest,
                io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledByIdResponse>(
                  this, METHODID_RESPOND_ACTIVITY_TASK_CANCELED_BY_ID)))
          .addMethod(
            getRequestCancelWorkflowExecutionMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionRequest,
                io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse>(
                  this, METHODID_REQUEST_CANCEL_WORKFLOW_EXECUTION)))
          .addMethod(
            getSignalWorkflowExecutionMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest,
                io.temporal.api.workflowservice.v1.SignalWorkflowExecutionResponse>(
                  this, METHODID_SIGNAL_WORKFLOW_EXECUTION)))
          .addMethod(
            getSignalWithStartWorkflowExecutionMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionRequest,
                io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionResponse>(
                  this, METHODID_SIGNAL_WITH_START_WORKFLOW_EXECUTION)))
          .addMethod(
            getResetWorkflowExecutionMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.ResetWorkflowExecutionRequest,
                io.temporal.api.workflowservice.v1.ResetWorkflowExecutionResponse>(
                  this, METHODID_RESET_WORKFLOW_EXECUTION)))
          .addMethod(
            getTerminateWorkflowExecutionMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionRequest,
                io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionResponse>(
                  this, METHODID_TERMINATE_WORKFLOW_EXECUTION)))
          .addMethod(
            getListOpenWorkflowExecutionsMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsRequest,
                io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsResponse>(
                  this, METHODID_LIST_OPEN_WORKFLOW_EXECUTIONS)))
          .addMethod(
            getListClosedWorkflowExecutionsMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsRequest,
                io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsResponse>(
                  this, METHODID_LIST_CLOSED_WORKFLOW_EXECUTIONS)))
          .addMethod(
            getListWorkflowExecutionsMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest,
                io.temporal.api.workflowservice.v1.ListWorkflowExecutionsResponse>(
                  this, METHODID_LIST_WORKFLOW_EXECUTIONS)))
          .addMethod(
            getListArchivedWorkflowExecutionsMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.ListArchivedWorkflowExecutionsRequest,
                io.temporal.api.workflowservice.v1.ListArchivedWorkflowExecutionsResponse>(
                  this, METHODID_LIST_ARCHIVED_WORKFLOW_EXECUTIONS)))
          .addMethod(
            getScanWorkflowExecutionsMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.ScanWorkflowExecutionsRequest,
                io.temporal.api.workflowservice.v1.ScanWorkflowExecutionsResponse>(
                  this, METHODID_SCAN_WORKFLOW_EXECUTIONS)))
          .addMethod(
            getCountWorkflowExecutionsMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.CountWorkflowExecutionsRequest,
                io.temporal.api.workflowservice.v1.CountWorkflowExecutionsResponse>(
                  this, METHODID_COUNT_WORKFLOW_EXECUTIONS)))
          .addMethod(
            getGetSearchAttributesMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.GetSearchAttributesRequest,
                io.temporal.api.workflowservice.v1.GetSearchAttributesResponse>(
                  this, METHODID_GET_SEARCH_ATTRIBUTES)))
          .addMethod(
            getRespondQueryTaskCompletedMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest,
                io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedResponse>(
                  this, METHODID_RESPOND_QUERY_TASK_COMPLETED)))
          .addMethod(
            getResetStickyTaskQueueMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.ResetStickyTaskQueueRequest,
                io.temporal.api.workflowservice.v1.ResetStickyTaskQueueResponse>(
                  this, METHODID_RESET_STICKY_TASK_QUEUE)))
          .addMethod(
            getQueryWorkflowMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.QueryWorkflowRequest,
                io.temporal.api.workflowservice.v1.QueryWorkflowResponse>(
                  this, METHODID_QUERY_WORKFLOW)))
          .addMethod(
            getDescribeWorkflowExecutionMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest,
                io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse>(
                  this, METHODID_DESCRIBE_WORKFLOW_EXECUTION)))
          .addMethod(
            getDescribeTaskQueueMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.DescribeTaskQueueRequest,
                io.temporal.api.workflowservice.v1.DescribeTaskQueueResponse>(
                  this, METHODID_DESCRIBE_TASK_QUEUE)))
          .addMethod(
            getGetClusterInfoMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.GetClusterInfoRequest,
                io.temporal.api.workflowservice.v1.GetClusterInfoResponse>(
                  this, METHODID_GET_CLUSTER_INFO)))
          .addMethod(
            getListTaskQueuePartitionsMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.temporal.api.workflowservice.v1.ListTaskQueuePartitionsRequest,
                io.temporal.api.workflowservice.v1.ListTaskQueuePartitionsResponse>(
                  this, METHODID_LIST_TASK_QUEUE_PARTITIONS)))
          .build();
    }
  }

  /**
   * <pre>
   * WorkflowService API is exposed to provide support for long running applications.  Application is expected to call
   * StartWorkflowExecution to create an instance for each instance of long running workflow.  Such applications are expected
   * to have a worker which regularly polls for WorkflowTask and ActivityTask from the WorkflowService.  For each
   * WorkflowTask, application is expected to process the history of events for that session and respond back with next
   * commands.  For each ActivityTask, application is expected to execute the actual logic for that task and respond back
   * with completion or failure.  Worker is expected to regularly heartbeat while activity task is running.
   * </pre>
   */
  public static final class WorkflowServiceStub extends io.grpc.stub.AbstractAsyncStub<WorkflowServiceStub> {
    private WorkflowServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected WorkflowServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new WorkflowServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * RegisterNamespace creates a new namespace which can be used as a container for all resources.  Namespace is a top level
     * entity within Temporal, used as a container for all resources like workflow executions, task queues, etc.  Namespace
     * acts as a sandbox and provides isolation for all resources within the namespace.  All resources belongs to exactly one
     * namespace.
     * </pre>
     */
    public void registerNamespace(io.temporal.api.workflowservice.v1.RegisterNamespaceRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RegisterNamespaceResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getRegisterNamespaceMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * DescribeNamespace returns the information and configuration for a registered namespace.
     * </pre>
     */
    public void describeNamespace(io.temporal.api.workflowservice.v1.DescribeNamespaceRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.DescribeNamespaceResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getDescribeNamespaceMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * ListNamespaces returns the information and configuration for all namespaces.
     * </pre>
     */
    public void listNamespaces(io.temporal.api.workflowservice.v1.ListNamespacesRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.ListNamespacesResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getListNamespacesMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * (-- api-linter: core::0134::method-signature=disabled
     *     aip.dev/not-precedent: UpdateNamespace RPC doesn't follow Google API format. --)
     * (-- api-linter: core::0134::response-message-name=disabled
     *     aip.dev/not-precedent: UpdateNamespace RPC doesn't follow Google API format. --)
     * UpdateNamespace is used to update the information and configuration for a registered namespace.
     * </pre>
     */
    public void updateNamespace(io.temporal.api.workflowservice.v1.UpdateNamespaceRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.UpdateNamespaceResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getUpdateNamespaceMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * DeprecateNamespace is used to update state of a registered namespace to DEPRECATED.  Once the namespace is deprecated
     * it cannot be used to start new workflow executions.  Existing workflow executions will continue to run on
     * deprecated namespaces.
     * </pre>
     */
    public void deprecateNamespace(io.temporal.api.workflowservice.v1.DeprecateNamespaceRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.DeprecateNamespaceResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getDeprecateNamespaceMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * StartWorkflowExecution starts a new long running workflow instance.  It will create the instance with
     * 'WorkflowExecutionStarted' event in history and also schedule the first WorkflowTask for the worker to make the
     * first command for this instance.  It will return 'WorkflowExecutionAlreadyStartedFailure', if an instance already
     * exists with same workflowId.
     * </pre>
     */
    public void startWorkflowExecution(io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.StartWorkflowExecutionResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getStartWorkflowExecutionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * GetWorkflowExecutionHistory returns the history of specified workflow execution.  It fails with 'NotFoundFailure' if specified workflow
     * execution in unknown to the service.
     * </pre>
     */
    public void getWorkflowExecutionHistory(io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getGetWorkflowExecutionHistoryMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * PollWorkflowTaskQueue is called by application worker to process WorkflowTask from a specific task queue.  A
     * WorkflowTask is dispatched to callers for active workflow executions, with pending workflow tasks.
     * Application is then expected to call 'RespondWorkflowTaskCompleted' API when it is done processing the WorkflowTask.
     * It will also create a 'WorkflowTaskStarted' event in the history for that session before handing off WorkflowTask to
     * application worker.
     * </pre>
     */
    public void pollWorkflowTaskQueue(io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getPollWorkflowTaskQueueMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * RespondWorkflowTaskCompleted is called by application worker to complete a WorkflowTask handed as a result of
     * 'PollWorkflowTaskQueue' API call.  Completing a WorkflowTask will result in new events for the workflow execution and
     * potentially new ActivityTask being created for corresponding commands.  It will also create a WorkflowTaskCompleted
     * event in the history for that session.  Use the 'taskToken' provided as response of PollWorkflowTaskQueue API call
     * for completing the WorkflowTask.
     * The response could contain a new workflow task if there is one or if the request asking for one.
     * </pre>
     */
    public void respondWorkflowTaskCompleted(io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getRespondWorkflowTaskCompletedMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * RespondWorkflowTaskFailed is called by application worker to indicate failure.  This results in
     * WorkflowTaskFailedEvent written to the history and a new WorkflowTask created.  This API can be used by client to
     * either clear sticky task queue or report any panics during WorkflowTask processing.  Temporal will only append first
     * WorkflowTaskFailed event to the history of workflow execution for consecutive failures.
     * </pre>
     */
    public void respondWorkflowTaskFailed(io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getRespondWorkflowTaskFailedMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * PollActivityTaskQueue is called by application worker to process ActivityTask from a specific task queue.  ActivityTask
     * is dispatched to callers whenever a ScheduleTask command is made for a workflow execution.
     * Application is expected to call 'RespondActivityTaskCompleted' or 'RespondActivityTaskFailed' once it is done
     * processing the task.
     * Application also needs to call 'RecordActivityTaskHeartbeat' API within 'heartbeatTimeoutSeconds' interval to
     * prevent the task from getting timed out.  An event 'ActivityTaskStarted' event is also written to workflow execution
     * history before the ActivityTask is dispatched to application worker.
     * </pre>
     */
    public void pollActivityTaskQueue(io.temporal.api.workflowservice.v1.PollActivityTaskQueueRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getPollActivityTaskQueueMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * RecordActivityTaskHeartbeat is called by application worker while it is processing an ActivityTask.  If worker fails
     * to heartbeat within 'heartbeatTimeoutSeconds' interval for the ActivityTask, then it will be marked as timedout and
     * 'ActivityTaskTimedOut' event will be written to the workflow history.  Calling 'RecordActivityTaskHeartbeat' will
     * fail with 'NotFoundFailure' in such situations.  Use the 'taskToken' provided as response of
     * PollActivityTaskQueue API call for heart beating.
     * </pre>
     */
    public void recordActivityTaskHeartbeat(io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getRecordActivityTaskHeartbeatMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * (-- api-linter: core::0136::prepositions=disabled
     *     aip.dev/not-precedent: "By" is used to indicate request type. --)
     * RecordActivityTaskHeartbeatById is called by application worker while it is processing an ActivityTask.  If worker fails
     * to heartbeat within 'heartbeatTimeoutSeconds' interval for the ActivityTask, then it will be marked as timed out and
     * 'ActivityTaskTimedOut' event will be written to the workflow history.  Calling 'RecordActivityTaskHeartbeatById' will
     * fail with 'NotFoundFailure' in such situations.  Instead of using 'taskToken' like in RecordActivityTaskHeartbeat,
     * use Namespace, WorkflowId and ActivityId
     * </pre>
     */
    public void recordActivityTaskHeartbeatById(io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getRecordActivityTaskHeartbeatByIdMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * RespondActivityTaskCompleted is called by application worker when it is done processing an ActivityTask.  It will
     * result in a new 'ActivityTaskCompleted' event being written to the workflow history and a new WorkflowTask
     * created for the workflow so new commands could be made.  Use the 'taskToken' provided as response of
     * PollActivityTaskQueue API call for completion. It fails with 'NotFoundFailure' if the taskToken is not valid
     * anymore due to activity timeout.
     * </pre>
     */
    public void respondActivityTaskCompleted(io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getRespondActivityTaskCompletedMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * (-- api-linter: core::0136::prepositions=disabled
     *     aip.dev/not-precedent: "By" is used to indicate request type. --)
     * RespondActivityTaskCompletedById is called by application worker when it is done processing an ActivityTask.
     * It will result in a new 'ActivityTaskCompleted' event being written to the workflow history and a new WorkflowTask
     * created for the workflow so new commands could be made.  Similar to RespondActivityTaskCompleted but use Namespace,
     * WorkflowId and ActivityId instead of 'taskToken' for completion. It fails with 'NotFoundFailure'
     * if the these Ids are not valid anymore due to activity timeout.
     * </pre>
     */
    public void respondActivityTaskCompletedById(io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedByIdRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedByIdResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getRespondActivityTaskCompletedByIdMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * RespondActivityTaskFailed is called by application worker when it is done processing an ActivityTask.  It will
     * result in a new 'ActivityTaskFailed' event being written to the workflow history and a new WorkflowTask
     * created for the workflow instance so new commands could be made.  Use the 'taskToken' provided as response of
     * PollActivityTaskQueue API call for completion. It fails with 'NotFoundFailure' if the taskToken is not valid
     * anymore due to activity timeout.
     * </pre>
     */
    public void respondActivityTaskFailed(io.temporal.api.workflowservice.v1.RespondActivityTaskFailedRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RespondActivityTaskFailedResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getRespondActivityTaskFailedMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * (-- api-linter: core::0136::prepositions=disabled
     *     aip.dev/not-precedent: "By" is used to indicate request type. --)
     * RespondActivityTaskFailedById is called by application worker when it is done processing an ActivityTask.
     * It will result in a new 'ActivityTaskFailed' event being written to the workflow history and a new WorkflowTask
     * created for the workflow instance so new commands could be made.  Similar to RespondActivityTaskFailed but use
     * Namespace, WorkflowId and ActivityId instead of 'taskToken' for completion. It fails with 'NotFoundFailure'
     * if the these Ids are not valid anymore due to activity timeout.
     * </pre>
     */
    public void respondActivityTaskFailedById(io.temporal.api.workflowservice.v1.RespondActivityTaskFailedByIdRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RespondActivityTaskFailedByIdResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getRespondActivityTaskFailedByIdMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * RespondActivityTaskCanceled is called by application worker when it is successfully canceled an ActivityTask.  It will
     * result in a new 'ActivityTaskCanceled' event being written to the workflow history and a new WorkflowTask
     * created for the workflow instance so new commands could be made.  Use the 'taskToken' provided as response of
     * PollActivityTaskQueue API call for completion. It fails with 'NotFoundFailure' if the taskToken is not valid
     * anymore due to activity timeout.
     * </pre>
     */
    public void respondActivityTaskCanceled(io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getRespondActivityTaskCanceledMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * (-- api-linter: core::0136::prepositions=disabled
     *     aip.dev/not-precedent: "By" is used to indicate request type. --)
     * RespondActivityTaskCanceledById is called by application worker when it is successfully canceled an ActivityTask.
     * It will result in a new 'ActivityTaskCanceled' event being written to the workflow history and a new WorkflowTask
     * created for the workflow instance so new commands could be made.  Similar to RespondActivityTaskCanceled but use
     * Namespace, WorkflowId and ActivityId instead of 'taskToken' for completion. It fails with 'NotFoundFailure'
     * if the these Ids are not valid anymore due to activity timeout.
     * </pre>
     */
    public void respondActivityTaskCanceledById(io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledByIdRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledByIdResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getRespondActivityTaskCanceledByIdMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * RequestCancelWorkflowExecution is called by application worker when it wants to request cancellation of a workflow instance.
     * It will result in a new 'WorkflowExecutionCancelRequested' event being written to the workflow history and a new WorkflowTask
     * created for the workflow instance so new commands could be made. It fails with 'NotFoundFailure' if the workflow is not valid
     * anymore due to completion or doesn't exist.
     * </pre>
     */
    public void requestCancelWorkflowExecution(io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getRequestCancelWorkflowExecutionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * SignalWorkflowExecution is used to send a signal event to running workflow execution.  This results in
     * WorkflowExecutionSignaled event recorded in the history and a workflow task being created for the execution.
     * </pre>
     */
    public void signalWorkflowExecution(io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.SignalWorkflowExecutionResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getSignalWorkflowExecutionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * (-- api-linter: core::0136::prepositions=disabled
     *     aip.dev/not-precedent: "With" is used to indicate combined operation. --)
     * SignalWithStartWorkflowExecution is used to ensure sending signal to a workflow.
     * If the workflow is running, this results in WorkflowExecutionSignaled event being recorded in the history
     * and a workflow task being created for the execution.
     * If the workflow is not running or not found, this results in WorkflowExecutionStarted and WorkflowExecutionSignaled
     * events being recorded in history, and a workflow task being created for the execution
     * </pre>
     */
    public void signalWithStartWorkflowExecution(io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getSignalWithStartWorkflowExecutionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * ResetWorkflowExecution reset an existing workflow execution to WorkflowTaskCompleted event(exclusive).
     * And it will immediately terminating the current execution instance.
     * </pre>
     */
    public void resetWorkflowExecution(io.temporal.api.workflowservice.v1.ResetWorkflowExecutionRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.ResetWorkflowExecutionResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getResetWorkflowExecutionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * TerminateWorkflowExecution terminates an existing workflow execution by recording WorkflowExecutionTerminated event
     * in the history and immediately terminating the execution instance.
     * </pre>
     */
    public void terminateWorkflowExecution(io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getTerminateWorkflowExecutionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * ListOpenWorkflowExecutions is a visibility API to list the open executions in a specific namespace.
     * </pre>
     */
    public void listOpenWorkflowExecutions(io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getListOpenWorkflowExecutionsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * ListClosedWorkflowExecutions is a visibility API to list the closed executions in a specific namespace.
     * </pre>
     */
    public void listClosedWorkflowExecutions(io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getListClosedWorkflowExecutionsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * ListWorkflowExecutions is a visibility API to list workflow executions in a specific namespace.
     * </pre>
     */
    public void listWorkflowExecutions(io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.ListWorkflowExecutionsResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getListWorkflowExecutionsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * ListArchivedWorkflowExecutions is a visibility API to list archived workflow executions in a specific namespace.
     * </pre>
     */
    public void listArchivedWorkflowExecutions(io.temporal.api.workflowservice.v1.ListArchivedWorkflowExecutionsRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.ListArchivedWorkflowExecutionsResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getListArchivedWorkflowExecutionsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * ScanWorkflowExecutions is a visibility API to list large amount of workflow executions in a specific namespace without order.
     * </pre>
     */
    public void scanWorkflowExecutions(io.temporal.api.workflowservice.v1.ScanWorkflowExecutionsRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.ScanWorkflowExecutionsResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getScanWorkflowExecutionsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * CountWorkflowExecutions is a visibility API to count of workflow executions in a specific namespace.
     * </pre>
     */
    public void countWorkflowExecutions(io.temporal.api.workflowservice.v1.CountWorkflowExecutionsRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.CountWorkflowExecutionsResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getCountWorkflowExecutionsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * GetSearchAttributes is a visibility API to get all legal keys that could be used in list APIs
     * </pre>
     */
    public void getSearchAttributes(io.temporal.api.workflowservice.v1.GetSearchAttributesRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.GetSearchAttributesResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getGetSearchAttributesMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * RespondQueryTaskCompleted is called by application worker to complete a QueryTask (which is a WorkflowTask for query)
     * as a result of 'PollWorkflowTaskQueue' API call. Completing a QueryTask will unblock the client call to 'QueryWorkflow'
     * API and return the query result to client as a response to 'QueryWorkflow' API call.
     * </pre>
     */
    public void respondQueryTaskCompleted(io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getRespondQueryTaskCompletedMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * ResetStickyTaskQueue resets the sticky task queue related information in mutable state of a given workflow.
     * Things cleared are:
     * 1. StickyTaskQueue
     * 2. StickyScheduleToStartTimeout
     * 3. ClientLibraryVersion
     * 4. ClientFeatureVersion
     * 5. ClientImpl
     * </pre>
     */
    public void resetStickyTaskQueue(io.temporal.api.workflowservice.v1.ResetStickyTaskQueueRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.ResetStickyTaskQueueResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getResetStickyTaskQueueMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * QueryWorkflow returns query result for a specified workflow execution
     * </pre>
     */
    public void queryWorkflow(io.temporal.api.workflowservice.v1.QueryWorkflowRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.QueryWorkflowResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getQueryWorkflowMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * DescribeWorkflowExecution returns information about the specified workflow execution.
     * </pre>
     */
    public void describeWorkflowExecution(io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getDescribeWorkflowExecutionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * DescribeTaskQueue returns information about the target task queue, right now this API returns the
     * pollers which polled this task queue in last few minutes.
     * </pre>
     */
    public void describeTaskQueue(io.temporal.api.workflowservice.v1.DescribeTaskQueueRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.DescribeTaskQueueResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getDescribeTaskQueueMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * GetClusterInfo returns information about temporal cluster
     * </pre>
     */
    public void getClusterInfo(io.temporal.api.workflowservice.v1.GetClusterInfoRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.GetClusterInfoResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getGetClusterInfoMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void listTaskQueuePartitions(io.temporal.api.workflowservice.v1.ListTaskQueuePartitionsRequest request,
        io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.ListTaskQueuePartitionsResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getListTaskQueuePartitionsMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * <pre>
   * WorkflowService API is exposed to provide support for long running applications.  Application is expected to call
   * StartWorkflowExecution to create an instance for each instance of long running workflow.  Such applications are expected
   * to have a worker which regularly polls for WorkflowTask and ActivityTask from the WorkflowService.  For each
   * WorkflowTask, application is expected to process the history of events for that session and respond back with next
   * commands.  For each ActivityTask, application is expected to execute the actual logic for that task and respond back
   * with completion or failure.  Worker is expected to regularly heartbeat while activity task is running.
   * </pre>
   */
  public static final class WorkflowServiceBlockingStub extends io.grpc.stub.AbstractBlockingStub<WorkflowServiceBlockingStub> {
    private WorkflowServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected WorkflowServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new WorkflowServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * RegisterNamespace creates a new namespace which can be used as a container for all resources.  Namespace is a top level
     * entity within Temporal, used as a container for all resources like workflow executions, task queues, etc.  Namespace
     * acts as a sandbox and provides isolation for all resources within the namespace.  All resources belongs to exactly one
     * namespace.
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.RegisterNamespaceResponse registerNamespace(io.temporal.api.workflowservice.v1.RegisterNamespaceRequest request) {
      return blockingUnaryCall(
          getChannel(), getRegisterNamespaceMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * DescribeNamespace returns the information and configuration for a registered namespace.
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.DescribeNamespaceResponse describeNamespace(io.temporal.api.workflowservice.v1.DescribeNamespaceRequest request) {
      return blockingUnaryCall(
          getChannel(), getDescribeNamespaceMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * ListNamespaces returns the information and configuration for all namespaces.
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.ListNamespacesResponse listNamespaces(io.temporal.api.workflowservice.v1.ListNamespacesRequest request) {
      return blockingUnaryCall(
          getChannel(), getListNamespacesMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * (-- api-linter: core::0134::method-signature=disabled
     *     aip.dev/not-precedent: UpdateNamespace RPC doesn't follow Google API format. --)
     * (-- api-linter: core::0134::response-message-name=disabled
     *     aip.dev/not-precedent: UpdateNamespace RPC doesn't follow Google API format. --)
     * UpdateNamespace is used to update the information and configuration for a registered namespace.
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.UpdateNamespaceResponse updateNamespace(io.temporal.api.workflowservice.v1.UpdateNamespaceRequest request) {
      return blockingUnaryCall(
          getChannel(), getUpdateNamespaceMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * DeprecateNamespace is used to update state of a registered namespace to DEPRECATED.  Once the namespace is deprecated
     * it cannot be used to start new workflow executions.  Existing workflow executions will continue to run on
     * deprecated namespaces.
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.DeprecateNamespaceResponse deprecateNamespace(io.temporal.api.workflowservice.v1.DeprecateNamespaceRequest request) {
      return blockingUnaryCall(
          getChannel(), getDeprecateNamespaceMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * StartWorkflowExecution starts a new long running workflow instance.  It will create the instance with
     * 'WorkflowExecutionStarted' event in history and also schedule the first WorkflowTask for the worker to make the
     * first command for this instance.  It will return 'WorkflowExecutionAlreadyStartedFailure', if an instance already
     * exists with same workflowId.
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.StartWorkflowExecutionResponse startWorkflowExecution(io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest request) {
      return blockingUnaryCall(
          getChannel(), getStartWorkflowExecutionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * GetWorkflowExecutionHistory returns the history of specified workflow execution.  It fails with 'NotFoundFailure' if specified workflow
     * execution in unknown to the service.
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse getWorkflowExecutionHistory(io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest request) {
      return blockingUnaryCall(
          getChannel(), getGetWorkflowExecutionHistoryMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * PollWorkflowTaskQueue is called by application worker to process WorkflowTask from a specific task queue.  A
     * WorkflowTask is dispatched to callers for active workflow executions, with pending workflow tasks.
     * Application is then expected to call 'RespondWorkflowTaskCompleted' API when it is done processing the WorkflowTask.
     * It will also create a 'WorkflowTaskStarted' event in the history for that session before handing off WorkflowTask to
     * application worker.
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse pollWorkflowTaskQueue(io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueRequest request) {
      return blockingUnaryCall(
          getChannel(), getPollWorkflowTaskQueueMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * RespondWorkflowTaskCompleted is called by application worker to complete a WorkflowTask handed as a result of
     * 'PollWorkflowTaskQueue' API call.  Completing a WorkflowTask will result in new events for the workflow execution and
     * potentially new ActivityTask being created for corresponding commands.  It will also create a WorkflowTaskCompleted
     * event in the history for that session.  Use the 'taskToken' provided as response of PollWorkflowTaskQueue API call
     * for completing the WorkflowTask.
     * The response could contain a new workflow task if there is one or if the request asking for one.
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse respondWorkflowTaskCompleted(io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest request) {
      return blockingUnaryCall(
          getChannel(), getRespondWorkflowTaskCompletedMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * RespondWorkflowTaskFailed is called by application worker to indicate failure.  This results in
     * WorkflowTaskFailedEvent written to the history and a new WorkflowTask created.  This API can be used by client to
     * either clear sticky task queue or report any panics during WorkflowTask processing.  Temporal will only append first
     * WorkflowTaskFailed event to the history of workflow execution for consecutive failures.
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedResponse respondWorkflowTaskFailed(io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest request) {
      return blockingUnaryCall(
          getChannel(), getRespondWorkflowTaskFailedMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * PollActivityTaskQueue is called by application worker to process ActivityTask from a specific task queue.  ActivityTask
     * is dispatched to callers whenever a ScheduleTask command is made for a workflow execution.
     * Application is expected to call 'RespondActivityTaskCompleted' or 'RespondActivityTaskFailed' once it is done
     * processing the task.
     * Application also needs to call 'RecordActivityTaskHeartbeat' API within 'heartbeatTimeoutSeconds' interval to
     * prevent the task from getting timed out.  An event 'ActivityTaskStarted' event is also written to workflow execution
     * history before the ActivityTask is dispatched to application worker.
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse pollActivityTaskQueue(io.temporal.api.workflowservice.v1.PollActivityTaskQueueRequest request) {
      return blockingUnaryCall(
          getChannel(), getPollActivityTaskQueueMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * RecordActivityTaskHeartbeat is called by application worker while it is processing an ActivityTask.  If worker fails
     * to heartbeat within 'heartbeatTimeoutSeconds' interval for the ActivityTask, then it will be marked as timedout and
     * 'ActivityTaskTimedOut' event will be written to the workflow history.  Calling 'RecordActivityTaskHeartbeat' will
     * fail with 'NotFoundFailure' in such situations.  Use the 'taskToken' provided as response of
     * PollActivityTaskQueue API call for heart beating.
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatResponse recordActivityTaskHeartbeat(io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatRequest request) {
      return blockingUnaryCall(
          getChannel(), getRecordActivityTaskHeartbeatMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * (-- api-linter: core::0136::prepositions=disabled
     *     aip.dev/not-precedent: "By" is used to indicate request type. --)
     * RecordActivityTaskHeartbeatById is called by application worker while it is processing an ActivityTask.  If worker fails
     * to heartbeat within 'heartbeatTimeoutSeconds' interval for the ActivityTask, then it will be marked as timed out and
     * 'ActivityTaskTimedOut' event will be written to the workflow history.  Calling 'RecordActivityTaskHeartbeatById' will
     * fail with 'NotFoundFailure' in such situations.  Instead of using 'taskToken' like in RecordActivityTaskHeartbeat,
     * use Namespace, WorkflowId and ActivityId
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdResponse recordActivityTaskHeartbeatById(io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdRequest request) {
      return blockingUnaryCall(
          getChannel(), getRecordActivityTaskHeartbeatByIdMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * RespondActivityTaskCompleted is called by application worker when it is done processing an ActivityTask.  It will
     * result in a new 'ActivityTaskCompleted' event being written to the workflow history and a new WorkflowTask
     * created for the workflow so new commands could be made.  Use the 'taskToken' provided as response of
     * PollActivityTaskQueue API call for completion. It fails with 'NotFoundFailure' if the taskToken is not valid
     * anymore due to activity timeout.
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedResponse respondActivityTaskCompleted(io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedRequest request) {
      return blockingUnaryCall(
          getChannel(), getRespondActivityTaskCompletedMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * (-- api-linter: core::0136::prepositions=disabled
     *     aip.dev/not-precedent: "By" is used to indicate request type. --)
     * RespondActivityTaskCompletedById is called by application worker when it is done processing an ActivityTask.
     * It will result in a new 'ActivityTaskCompleted' event being written to the workflow history and a new WorkflowTask
     * created for the workflow so new commands could be made.  Similar to RespondActivityTaskCompleted but use Namespace,
     * WorkflowId and ActivityId instead of 'taskToken' for completion. It fails with 'NotFoundFailure'
     * if the these Ids are not valid anymore due to activity timeout.
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedByIdResponse respondActivityTaskCompletedById(io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedByIdRequest request) {
      return blockingUnaryCall(
          getChannel(), getRespondActivityTaskCompletedByIdMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * RespondActivityTaskFailed is called by application worker when it is done processing an ActivityTask.  It will
     * result in a new 'ActivityTaskFailed' event being written to the workflow history and a new WorkflowTask
     * created for the workflow instance so new commands could be made.  Use the 'taskToken' provided as response of
     * PollActivityTaskQueue API call for completion. It fails with 'NotFoundFailure' if the taskToken is not valid
     * anymore due to activity timeout.
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.RespondActivityTaskFailedResponse respondActivityTaskFailed(io.temporal.api.workflowservice.v1.RespondActivityTaskFailedRequest request) {
      return blockingUnaryCall(
          getChannel(), getRespondActivityTaskFailedMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * (-- api-linter: core::0136::prepositions=disabled
     *     aip.dev/not-precedent: "By" is used to indicate request type. --)
     * RespondActivityTaskFailedById is called by application worker when it is done processing an ActivityTask.
     * It will result in a new 'ActivityTaskFailed' event being written to the workflow history and a new WorkflowTask
     * created for the workflow instance so new commands could be made.  Similar to RespondActivityTaskFailed but use
     * Namespace, WorkflowId and ActivityId instead of 'taskToken' for completion. It fails with 'NotFoundFailure'
     * if the these Ids are not valid anymore due to activity timeout.
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.RespondActivityTaskFailedByIdResponse respondActivityTaskFailedById(io.temporal.api.workflowservice.v1.RespondActivityTaskFailedByIdRequest request) {
      return blockingUnaryCall(
          getChannel(), getRespondActivityTaskFailedByIdMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * RespondActivityTaskCanceled is called by application worker when it is successfully canceled an ActivityTask.  It will
     * result in a new 'ActivityTaskCanceled' event being written to the workflow history and a new WorkflowTask
     * created for the workflow instance so new commands could be made.  Use the 'taskToken' provided as response of
     * PollActivityTaskQueue API call for completion. It fails with 'NotFoundFailure' if the taskToken is not valid
     * anymore due to activity timeout.
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledResponse respondActivityTaskCanceled(io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledRequest request) {
      return blockingUnaryCall(
          getChannel(), getRespondActivityTaskCanceledMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * (-- api-linter: core::0136::prepositions=disabled
     *     aip.dev/not-precedent: "By" is used to indicate request type. --)
     * RespondActivityTaskCanceledById is called by application worker when it is successfully canceled an ActivityTask.
     * It will result in a new 'ActivityTaskCanceled' event being written to the workflow history and a new WorkflowTask
     * created for the workflow instance so new commands could be made.  Similar to RespondActivityTaskCanceled but use
     * Namespace, WorkflowId and ActivityId instead of 'taskToken' for completion. It fails with 'NotFoundFailure'
     * if the these Ids are not valid anymore due to activity timeout.
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledByIdResponse respondActivityTaskCanceledById(io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledByIdRequest request) {
      return blockingUnaryCall(
          getChannel(), getRespondActivityTaskCanceledByIdMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * RequestCancelWorkflowExecution is called by application worker when it wants to request cancellation of a workflow instance.
     * It will result in a new 'WorkflowExecutionCancelRequested' event being written to the workflow history and a new WorkflowTask
     * created for the workflow instance so new commands could be made. It fails with 'NotFoundFailure' if the workflow is not valid
     * anymore due to completion or doesn't exist.
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse requestCancelWorkflowExecution(io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionRequest request) {
      return blockingUnaryCall(
          getChannel(), getRequestCancelWorkflowExecutionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * SignalWorkflowExecution is used to send a signal event to running workflow execution.  This results in
     * WorkflowExecutionSignaled event recorded in the history and a workflow task being created for the execution.
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.SignalWorkflowExecutionResponse signalWorkflowExecution(io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest request) {
      return blockingUnaryCall(
          getChannel(), getSignalWorkflowExecutionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * (-- api-linter: core::0136::prepositions=disabled
     *     aip.dev/not-precedent: "With" is used to indicate combined operation. --)
     * SignalWithStartWorkflowExecution is used to ensure sending signal to a workflow.
     * If the workflow is running, this results in WorkflowExecutionSignaled event being recorded in the history
     * and a workflow task being created for the execution.
     * If the workflow is not running or not found, this results in WorkflowExecutionStarted and WorkflowExecutionSignaled
     * events being recorded in history, and a workflow task being created for the execution
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionResponse signalWithStartWorkflowExecution(io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionRequest request) {
      return blockingUnaryCall(
          getChannel(), getSignalWithStartWorkflowExecutionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * ResetWorkflowExecution reset an existing workflow execution to WorkflowTaskCompleted event(exclusive).
     * And it will immediately terminating the current execution instance.
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.ResetWorkflowExecutionResponse resetWorkflowExecution(io.temporal.api.workflowservice.v1.ResetWorkflowExecutionRequest request) {
      return blockingUnaryCall(
          getChannel(), getResetWorkflowExecutionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * TerminateWorkflowExecution terminates an existing workflow execution by recording WorkflowExecutionTerminated event
     * in the history and immediately terminating the execution instance.
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionResponse terminateWorkflowExecution(io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionRequest request) {
      return blockingUnaryCall(
          getChannel(), getTerminateWorkflowExecutionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * ListOpenWorkflowExecutions is a visibility API to list the open executions in a specific namespace.
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsResponse listOpenWorkflowExecutions(io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsRequest request) {
      return blockingUnaryCall(
          getChannel(), getListOpenWorkflowExecutionsMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * ListClosedWorkflowExecutions is a visibility API to list the closed executions in a specific namespace.
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsResponse listClosedWorkflowExecutions(io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsRequest request) {
      return blockingUnaryCall(
          getChannel(), getListClosedWorkflowExecutionsMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * ListWorkflowExecutions is a visibility API to list workflow executions in a specific namespace.
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.ListWorkflowExecutionsResponse listWorkflowExecutions(io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest request) {
      return blockingUnaryCall(
          getChannel(), getListWorkflowExecutionsMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * ListArchivedWorkflowExecutions is a visibility API to list archived workflow executions in a specific namespace.
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.ListArchivedWorkflowExecutionsResponse listArchivedWorkflowExecutions(io.temporal.api.workflowservice.v1.ListArchivedWorkflowExecutionsRequest request) {
      return blockingUnaryCall(
          getChannel(), getListArchivedWorkflowExecutionsMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * ScanWorkflowExecutions is a visibility API to list large amount of workflow executions in a specific namespace without order.
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.ScanWorkflowExecutionsResponse scanWorkflowExecutions(io.temporal.api.workflowservice.v1.ScanWorkflowExecutionsRequest request) {
      return blockingUnaryCall(
          getChannel(), getScanWorkflowExecutionsMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * CountWorkflowExecutions is a visibility API to count of workflow executions in a specific namespace.
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.CountWorkflowExecutionsResponse countWorkflowExecutions(io.temporal.api.workflowservice.v1.CountWorkflowExecutionsRequest request) {
      return blockingUnaryCall(
          getChannel(), getCountWorkflowExecutionsMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * GetSearchAttributes is a visibility API to get all legal keys that could be used in list APIs
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.GetSearchAttributesResponse getSearchAttributes(io.temporal.api.workflowservice.v1.GetSearchAttributesRequest request) {
      return blockingUnaryCall(
          getChannel(), getGetSearchAttributesMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * RespondQueryTaskCompleted is called by application worker to complete a QueryTask (which is a WorkflowTask for query)
     * as a result of 'PollWorkflowTaskQueue' API call. Completing a QueryTask will unblock the client call to 'QueryWorkflow'
     * API and return the query result to client as a response to 'QueryWorkflow' API call.
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedResponse respondQueryTaskCompleted(io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest request) {
      return blockingUnaryCall(
          getChannel(), getRespondQueryTaskCompletedMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * ResetStickyTaskQueue resets the sticky task queue related information in mutable state of a given workflow.
     * Things cleared are:
     * 1. StickyTaskQueue
     * 2. StickyScheduleToStartTimeout
     * 3. ClientLibraryVersion
     * 4. ClientFeatureVersion
     * 5. ClientImpl
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.ResetStickyTaskQueueResponse resetStickyTaskQueue(io.temporal.api.workflowservice.v1.ResetStickyTaskQueueRequest request) {
      return blockingUnaryCall(
          getChannel(), getResetStickyTaskQueueMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * QueryWorkflow returns query result for a specified workflow execution
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.QueryWorkflowResponse queryWorkflow(io.temporal.api.workflowservice.v1.QueryWorkflowRequest request) {
      return blockingUnaryCall(
          getChannel(), getQueryWorkflowMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * DescribeWorkflowExecution returns information about the specified workflow execution.
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse describeWorkflowExecution(io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest request) {
      return blockingUnaryCall(
          getChannel(), getDescribeWorkflowExecutionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * DescribeTaskQueue returns information about the target task queue, right now this API returns the
     * pollers which polled this task queue in last few minutes.
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.DescribeTaskQueueResponse describeTaskQueue(io.temporal.api.workflowservice.v1.DescribeTaskQueueRequest request) {
      return blockingUnaryCall(
          getChannel(), getDescribeTaskQueueMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * GetClusterInfo returns information about temporal cluster
     * </pre>
     */
    public io.temporal.api.workflowservice.v1.GetClusterInfoResponse getClusterInfo(io.temporal.api.workflowservice.v1.GetClusterInfoRequest request) {
      return blockingUnaryCall(
          getChannel(), getGetClusterInfoMethod(), getCallOptions(), request);
    }

    /**
     */
    public io.temporal.api.workflowservice.v1.ListTaskQueuePartitionsResponse listTaskQueuePartitions(io.temporal.api.workflowservice.v1.ListTaskQueuePartitionsRequest request) {
      return blockingUnaryCall(
          getChannel(), getListTaskQueuePartitionsMethod(), getCallOptions(), request);
    }
  }

  /**
   * <pre>
   * WorkflowService API is exposed to provide support for long running applications.  Application is expected to call
   * StartWorkflowExecution to create an instance for each instance of long running workflow.  Such applications are expected
   * to have a worker which regularly polls for WorkflowTask and ActivityTask from the WorkflowService.  For each
   * WorkflowTask, application is expected to process the history of events for that session and respond back with next
   * commands.  For each ActivityTask, application is expected to execute the actual logic for that task and respond back
   * with completion or failure.  Worker is expected to regularly heartbeat while activity task is running.
   * </pre>
   */
  public static final class WorkflowServiceFutureStub extends io.grpc.stub.AbstractFutureStub<WorkflowServiceFutureStub> {
    private WorkflowServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected WorkflowServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new WorkflowServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * RegisterNamespace creates a new namespace which can be used as a container for all resources.  Namespace is a top level
     * entity within Temporal, used as a container for all resources like workflow executions, task queues, etc.  Namespace
     * acts as a sandbox and provides isolation for all resources within the namespace.  All resources belongs to exactly one
     * namespace.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.RegisterNamespaceResponse> registerNamespace(
        io.temporal.api.workflowservice.v1.RegisterNamespaceRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getRegisterNamespaceMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * DescribeNamespace returns the information and configuration for a registered namespace.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.DescribeNamespaceResponse> describeNamespace(
        io.temporal.api.workflowservice.v1.DescribeNamespaceRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getDescribeNamespaceMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * ListNamespaces returns the information and configuration for all namespaces.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.ListNamespacesResponse> listNamespaces(
        io.temporal.api.workflowservice.v1.ListNamespacesRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getListNamespacesMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * (-- api-linter: core::0134::method-signature=disabled
     *     aip.dev/not-precedent: UpdateNamespace RPC doesn't follow Google API format. --)
     * (-- api-linter: core::0134::response-message-name=disabled
     *     aip.dev/not-precedent: UpdateNamespace RPC doesn't follow Google API format. --)
     * UpdateNamespace is used to update the information and configuration for a registered namespace.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.UpdateNamespaceResponse> updateNamespace(
        io.temporal.api.workflowservice.v1.UpdateNamespaceRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getUpdateNamespaceMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * DeprecateNamespace is used to update state of a registered namespace to DEPRECATED.  Once the namespace is deprecated
     * it cannot be used to start new workflow executions.  Existing workflow executions will continue to run on
     * deprecated namespaces.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.DeprecateNamespaceResponse> deprecateNamespace(
        io.temporal.api.workflowservice.v1.DeprecateNamespaceRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getDeprecateNamespaceMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * StartWorkflowExecution starts a new long running workflow instance.  It will create the instance with
     * 'WorkflowExecutionStarted' event in history and also schedule the first WorkflowTask for the worker to make the
     * first command for this instance.  It will return 'WorkflowExecutionAlreadyStartedFailure', if an instance already
     * exists with same workflowId.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.StartWorkflowExecutionResponse> startWorkflowExecution(
        io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getStartWorkflowExecutionMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * GetWorkflowExecutionHistory returns the history of specified workflow execution.  It fails with 'NotFoundFailure' if specified workflow
     * execution in unknown to the service.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse> getWorkflowExecutionHistory(
        io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getGetWorkflowExecutionHistoryMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * PollWorkflowTaskQueue is called by application worker to process WorkflowTask from a specific task queue.  A
     * WorkflowTask is dispatched to callers for active workflow executions, with pending workflow tasks.
     * Application is then expected to call 'RespondWorkflowTaskCompleted' API when it is done processing the WorkflowTask.
     * It will also create a 'WorkflowTaskStarted' event in the history for that session before handing off WorkflowTask to
     * application worker.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse> pollWorkflowTaskQueue(
        io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getPollWorkflowTaskQueueMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * RespondWorkflowTaskCompleted is called by application worker to complete a WorkflowTask handed as a result of
     * 'PollWorkflowTaskQueue' API call.  Completing a WorkflowTask will result in new events for the workflow execution and
     * potentially new ActivityTask being created for corresponding commands.  It will also create a WorkflowTaskCompleted
     * event in the history for that session.  Use the 'taskToken' provided as response of PollWorkflowTaskQueue API call
     * for completing the WorkflowTask.
     * The response could contain a new workflow task if there is one or if the request asking for one.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse> respondWorkflowTaskCompleted(
        io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getRespondWorkflowTaskCompletedMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * RespondWorkflowTaskFailed is called by application worker to indicate failure.  This results in
     * WorkflowTaskFailedEvent written to the history and a new WorkflowTask created.  This API can be used by client to
     * either clear sticky task queue or report any panics during WorkflowTask processing.  Temporal will only append first
     * WorkflowTaskFailed event to the history of workflow execution for consecutive failures.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedResponse> respondWorkflowTaskFailed(
        io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getRespondWorkflowTaskFailedMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * PollActivityTaskQueue is called by application worker to process ActivityTask from a specific task queue.  ActivityTask
     * is dispatched to callers whenever a ScheduleTask command is made for a workflow execution.
     * Application is expected to call 'RespondActivityTaskCompleted' or 'RespondActivityTaskFailed' once it is done
     * processing the task.
     * Application also needs to call 'RecordActivityTaskHeartbeat' API within 'heartbeatTimeoutSeconds' interval to
     * prevent the task from getting timed out.  An event 'ActivityTaskStarted' event is also written to workflow execution
     * history before the ActivityTask is dispatched to application worker.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse> pollActivityTaskQueue(
        io.temporal.api.workflowservice.v1.PollActivityTaskQueueRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getPollActivityTaskQueueMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * RecordActivityTaskHeartbeat is called by application worker while it is processing an ActivityTask.  If worker fails
     * to heartbeat within 'heartbeatTimeoutSeconds' interval for the ActivityTask, then it will be marked as timedout and
     * 'ActivityTaskTimedOut' event will be written to the workflow history.  Calling 'RecordActivityTaskHeartbeat' will
     * fail with 'NotFoundFailure' in such situations.  Use the 'taskToken' provided as response of
     * PollActivityTaskQueue API call for heart beating.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatResponse> recordActivityTaskHeartbeat(
        io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getRecordActivityTaskHeartbeatMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * (-- api-linter: core::0136::prepositions=disabled
     *     aip.dev/not-precedent: "By" is used to indicate request type. --)
     * RecordActivityTaskHeartbeatById is called by application worker while it is processing an ActivityTask.  If worker fails
     * to heartbeat within 'heartbeatTimeoutSeconds' interval for the ActivityTask, then it will be marked as timed out and
     * 'ActivityTaskTimedOut' event will be written to the workflow history.  Calling 'RecordActivityTaskHeartbeatById' will
     * fail with 'NotFoundFailure' in such situations.  Instead of using 'taskToken' like in RecordActivityTaskHeartbeat,
     * use Namespace, WorkflowId and ActivityId
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdResponse> recordActivityTaskHeartbeatById(
        io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getRecordActivityTaskHeartbeatByIdMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * RespondActivityTaskCompleted is called by application worker when it is done processing an ActivityTask.  It will
     * result in a new 'ActivityTaskCompleted' event being written to the workflow history and a new WorkflowTask
     * created for the workflow so new commands could be made.  Use the 'taskToken' provided as response of
     * PollActivityTaskQueue API call for completion. It fails with 'NotFoundFailure' if the taskToken is not valid
     * anymore due to activity timeout.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedResponse> respondActivityTaskCompleted(
        io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getRespondActivityTaskCompletedMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * (-- api-linter: core::0136::prepositions=disabled
     *     aip.dev/not-precedent: "By" is used to indicate request type. --)
     * RespondActivityTaskCompletedById is called by application worker when it is done processing an ActivityTask.
     * It will result in a new 'ActivityTaskCompleted' event being written to the workflow history and a new WorkflowTask
     * created for the workflow so new commands could be made.  Similar to RespondActivityTaskCompleted but use Namespace,
     * WorkflowId and ActivityId instead of 'taskToken' for completion. It fails with 'NotFoundFailure'
     * if the these Ids are not valid anymore due to activity timeout.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedByIdResponse> respondActivityTaskCompletedById(
        io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedByIdRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getRespondActivityTaskCompletedByIdMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * RespondActivityTaskFailed is called by application worker when it is done processing an ActivityTask.  It will
     * result in a new 'ActivityTaskFailed' event being written to the workflow history and a new WorkflowTask
     * created for the workflow instance so new commands could be made.  Use the 'taskToken' provided as response of
     * PollActivityTaskQueue API call for completion. It fails with 'NotFoundFailure' if the taskToken is not valid
     * anymore due to activity timeout.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.RespondActivityTaskFailedResponse> respondActivityTaskFailed(
        io.temporal.api.workflowservice.v1.RespondActivityTaskFailedRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getRespondActivityTaskFailedMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * (-- api-linter: core::0136::prepositions=disabled
     *     aip.dev/not-precedent: "By" is used to indicate request type. --)
     * RespondActivityTaskFailedById is called by application worker when it is done processing an ActivityTask.
     * It will result in a new 'ActivityTaskFailed' event being written to the workflow history and a new WorkflowTask
     * created for the workflow instance so new commands could be made.  Similar to RespondActivityTaskFailed but use
     * Namespace, WorkflowId and ActivityId instead of 'taskToken' for completion. It fails with 'NotFoundFailure'
     * if the these Ids are not valid anymore due to activity timeout.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.RespondActivityTaskFailedByIdResponse> respondActivityTaskFailedById(
        io.temporal.api.workflowservice.v1.RespondActivityTaskFailedByIdRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getRespondActivityTaskFailedByIdMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * RespondActivityTaskCanceled is called by application worker when it is successfully canceled an ActivityTask.  It will
     * result in a new 'ActivityTaskCanceled' event being written to the workflow history and a new WorkflowTask
     * created for the workflow instance so new commands could be made.  Use the 'taskToken' provided as response of
     * PollActivityTaskQueue API call for completion. It fails with 'NotFoundFailure' if the taskToken is not valid
     * anymore due to activity timeout.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledResponse> respondActivityTaskCanceled(
        io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getRespondActivityTaskCanceledMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * (-- api-linter: core::0136::prepositions=disabled
     *     aip.dev/not-precedent: "By" is used to indicate request type. --)
     * RespondActivityTaskCanceledById is called by application worker when it is successfully canceled an ActivityTask.
     * It will result in a new 'ActivityTaskCanceled' event being written to the workflow history and a new WorkflowTask
     * created for the workflow instance so new commands could be made.  Similar to RespondActivityTaskCanceled but use
     * Namespace, WorkflowId and ActivityId instead of 'taskToken' for completion. It fails with 'NotFoundFailure'
     * if the these Ids are not valid anymore due to activity timeout.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledByIdResponse> respondActivityTaskCanceledById(
        io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledByIdRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getRespondActivityTaskCanceledByIdMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * RequestCancelWorkflowExecution is called by application worker when it wants to request cancellation of a workflow instance.
     * It will result in a new 'WorkflowExecutionCancelRequested' event being written to the workflow history and a new WorkflowTask
     * created for the workflow instance so new commands could be made. It fails with 'NotFoundFailure' if the workflow is not valid
     * anymore due to completion or doesn't exist.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse> requestCancelWorkflowExecution(
        io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getRequestCancelWorkflowExecutionMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * SignalWorkflowExecution is used to send a signal event to running workflow execution.  This results in
     * WorkflowExecutionSignaled event recorded in the history and a workflow task being created for the execution.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.SignalWorkflowExecutionResponse> signalWorkflowExecution(
        io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getSignalWorkflowExecutionMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * (-- api-linter: core::0136::prepositions=disabled
     *     aip.dev/not-precedent: "With" is used to indicate combined operation. --)
     * SignalWithStartWorkflowExecution is used to ensure sending signal to a workflow.
     * If the workflow is running, this results in WorkflowExecutionSignaled event being recorded in the history
     * and a workflow task being created for the execution.
     * If the workflow is not running or not found, this results in WorkflowExecutionStarted and WorkflowExecutionSignaled
     * events being recorded in history, and a workflow task being created for the execution
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionResponse> signalWithStartWorkflowExecution(
        io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getSignalWithStartWorkflowExecutionMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * ResetWorkflowExecution reset an existing workflow execution to WorkflowTaskCompleted event(exclusive).
     * And it will immediately terminating the current execution instance.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.ResetWorkflowExecutionResponse> resetWorkflowExecution(
        io.temporal.api.workflowservice.v1.ResetWorkflowExecutionRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getResetWorkflowExecutionMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * TerminateWorkflowExecution terminates an existing workflow execution by recording WorkflowExecutionTerminated event
     * in the history and immediately terminating the execution instance.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionResponse> terminateWorkflowExecution(
        io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getTerminateWorkflowExecutionMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * ListOpenWorkflowExecutions is a visibility API to list the open executions in a specific namespace.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsResponse> listOpenWorkflowExecutions(
        io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getListOpenWorkflowExecutionsMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * ListClosedWorkflowExecutions is a visibility API to list the closed executions in a specific namespace.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsResponse> listClosedWorkflowExecutions(
        io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getListClosedWorkflowExecutionsMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * ListWorkflowExecutions is a visibility API to list workflow executions in a specific namespace.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.ListWorkflowExecutionsResponse> listWorkflowExecutions(
        io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getListWorkflowExecutionsMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * ListArchivedWorkflowExecutions is a visibility API to list archived workflow executions in a specific namespace.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.ListArchivedWorkflowExecutionsResponse> listArchivedWorkflowExecutions(
        io.temporal.api.workflowservice.v1.ListArchivedWorkflowExecutionsRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getListArchivedWorkflowExecutionsMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * ScanWorkflowExecutions is a visibility API to list large amount of workflow executions in a specific namespace without order.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.ScanWorkflowExecutionsResponse> scanWorkflowExecutions(
        io.temporal.api.workflowservice.v1.ScanWorkflowExecutionsRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getScanWorkflowExecutionsMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * CountWorkflowExecutions is a visibility API to count of workflow executions in a specific namespace.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.CountWorkflowExecutionsResponse> countWorkflowExecutions(
        io.temporal.api.workflowservice.v1.CountWorkflowExecutionsRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getCountWorkflowExecutionsMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * GetSearchAttributes is a visibility API to get all legal keys that could be used in list APIs
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.GetSearchAttributesResponse> getSearchAttributes(
        io.temporal.api.workflowservice.v1.GetSearchAttributesRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getGetSearchAttributesMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * RespondQueryTaskCompleted is called by application worker to complete a QueryTask (which is a WorkflowTask for query)
     * as a result of 'PollWorkflowTaskQueue' API call. Completing a QueryTask will unblock the client call to 'QueryWorkflow'
     * API and return the query result to client as a response to 'QueryWorkflow' API call.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedResponse> respondQueryTaskCompleted(
        io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getRespondQueryTaskCompletedMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * ResetStickyTaskQueue resets the sticky task queue related information in mutable state of a given workflow.
     * Things cleared are:
     * 1. StickyTaskQueue
     * 2. StickyScheduleToStartTimeout
     * 3. ClientLibraryVersion
     * 4. ClientFeatureVersion
     * 5. ClientImpl
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.ResetStickyTaskQueueResponse> resetStickyTaskQueue(
        io.temporal.api.workflowservice.v1.ResetStickyTaskQueueRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getResetStickyTaskQueueMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * QueryWorkflow returns query result for a specified workflow execution
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.QueryWorkflowResponse> queryWorkflow(
        io.temporal.api.workflowservice.v1.QueryWorkflowRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getQueryWorkflowMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * DescribeWorkflowExecution returns information about the specified workflow execution.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse> describeWorkflowExecution(
        io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getDescribeWorkflowExecutionMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * DescribeTaskQueue returns information about the target task queue, right now this API returns the
     * pollers which polled this task queue in last few minutes.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.DescribeTaskQueueResponse> describeTaskQueue(
        io.temporal.api.workflowservice.v1.DescribeTaskQueueRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getDescribeTaskQueueMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * GetClusterInfo returns information about temporal cluster
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.GetClusterInfoResponse> getClusterInfo(
        io.temporal.api.workflowservice.v1.GetClusterInfoRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getGetClusterInfoMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<io.temporal.api.workflowservice.v1.ListTaskQueuePartitionsResponse> listTaskQueuePartitions(
        io.temporal.api.workflowservice.v1.ListTaskQueuePartitionsRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getListTaskQueuePartitionsMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_REGISTER_NAMESPACE = 0;
  private static final int METHODID_DESCRIBE_NAMESPACE = 1;
  private static final int METHODID_LIST_NAMESPACES = 2;
  private static final int METHODID_UPDATE_NAMESPACE = 3;
  private static final int METHODID_DEPRECATE_NAMESPACE = 4;
  private static final int METHODID_START_WORKFLOW_EXECUTION = 5;
  private static final int METHODID_GET_WORKFLOW_EXECUTION_HISTORY = 6;
  private static final int METHODID_POLL_WORKFLOW_TASK_QUEUE = 7;
  private static final int METHODID_RESPOND_WORKFLOW_TASK_COMPLETED = 8;
  private static final int METHODID_RESPOND_WORKFLOW_TASK_FAILED = 9;
  private static final int METHODID_POLL_ACTIVITY_TASK_QUEUE = 10;
  private static final int METHODID_RECORD_ACTIVITY_TASK_HEARTBEAT = 11;
  private static final int METHODID_RECORD_ACTIVITY_TASK_HEARTBEAT_BY_ID = 12;
  private static final int METHODID_RESPOND_ACTIVITY_TASK_COMPLETED = 13;
  private static final int METHODID_RESPOND_ACTIVITY_TASK_COMPLETED_BY_ID = 14;
  private static final int METHODID_RESPOND_ACTIVITY_TASK_FAILED = 15;
  private static final int METHODID_RESPOND_ACTIVITY_TASK_FAILED_BY_ID = 16;
  private static final int METHODID_RESPOND_ACTIVITY_TASK_CANCELED = 17;
  private static final int METHODID_RESPOND_ACTIVITY_TASK_CANCELED_BY_ID = 18;
  private static final int METHODID_REQUEST_CANCEL_WORKFLOW_EXECUTION = 19;
  private static final int METHODID_SIGNAL_WORKFLOW_EXECUTION = 20;
  private static final int METHODID_SIGNAL_WITH_START_WORKFLOW_EXECUTION = 21;
  private static final int METHODID_RESET_WORKFLOW_EXECUTION = 22;
  private static final int METHODID_TERMINATE_WORKFLOW_EXECUTION = 23;
  private static final int METHODID_LIST_OPEN_WORKFLOW_EXECUTIONS = 24;
  private static final int METHODID_LIST_CLOSED_WORKFLOW_EXECUTIONS = 25;
  private static final int METHODID_LIST_WORKFLOW_EXECUTIONS = 26;
  private static final int METHODID_LIST_ARCHIVED_WORKFLOW_EXECUTIONS = 27;
  private static final int METHODID_SCAN_WORKFLOW_EXECUTIONS = 28;
  private static final int METHODID_COUNT_WORKFLOW_EXECUTIONS = 29;
  private static final int METHODID_GET_SEARCH_ATTRIBUTES = 30;
  private static final int METHODID_RESPOND_QUERY_TASK_COMPLETED = 31;
  private static final int METHODID_RESET_STICKY_TASK_QUEUE = 32;
  private static final int METHODID_QUERY_WORKFLOW = 33;
  private static final int METHODID_DESCRIBE_WORKFLOW_EXECUTION = 34;
  private static final int METHODID_DESCRIBE_TASK_QUEUE = 35;
  private static final int METHODID_GET_CLUSTER_INFO = 36;
  private static final int METHODID_LIST_TASK_QUEUE_PARTITIONS = 37;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final WorkflowServiceImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(WorkflowServiceImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_REGISTER_NAMESPACE:
          serviceImpl.registerNamespace((io.temporal.api.workflowservice.v1.RegisterNamespaceRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RegisterNamespaceResponse>) responseObserver);
          break;
        case METHODID_DESCRIBE_NAMESPACE:
          serviceImpl.describeNamespace((io.temporal.api.workflowservice.v1.DescribeNamespaceRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.DescribeNamespaceResponse>) responseObserver);
          break;
        case METHODID_LIST_NAMESPACES:
          serviceImpl.listNamespaces((io.temporal.api.workflowservice.v1.ListNamespacesRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.ListNamespacesResponse>) responseObserver);
          break;
        case METHODID_UPDATE_NAMESPACE:
          serviceImpl.updateNamespace((io.temporal.api.workflowservice.v1.UpdateNamespaceRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.UpdateNamespaceResponse>) responseObserver);
          break;
        case METHODID_DEPRECATE_NAMESPACE:
          serviceImpl.deprecateNamespace((io.temporal.api.workflowservice.v1.DeprecateNamespaceRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.DeprecateNamespaceResponse>) responseObserver);
          break;
        case METHODID_START_WORKFLOW_EXECUTION:
          serviceImpl.startWorkflowExecution((io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.StartWorkflowExecutionResponse>) responseObserver);
          break;
        case METHODID_GET_WORKFLOW_EXECUTION_HISTORY:
          serviceImpl.getWorkflowExecutionHistory((io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse>) responseObserver);
          break;
        case METHODID_POLL_WORKFLOW_TASK_QUEUE:
          serviceImpl.pollWorkflowTaskQueue((io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse>) responseObserver);
          break;
        case METHODID_RESPOND_WORKFLOW_TASK_COMPLETED:
          serviceImpl.respondWorkflowTaskCompleted((io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedResponse>) responseObserver);
          break;
        case METHODID_RESPOND_WORKFLOW_TASK_FAILED:
          serviceImpl.respondWorkflowTaskFailed((io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedResponse>) responseObserver);
          break;
        case METHODID_POLL_ACTIVITY_TASK_QUEUE:
          serviceImpl.pollActivityTaskQueue((io.temporal.api.workflowservice.v1.PollActivityTaskQueueRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse>) responseObserver);
          break;
        case METHODID_RECORD_ACTIVITY_TASK_HEARTBEAT:
          serviceImpl.recordActivityTaskHeartbeat((io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatResponse>) responseObserver);
          break;
        case METHODID_RECORD_ACTIVITY_TASK_HEARTBEAT_BY_ID:
          serviceImpl.recordActivityTaskHeartbeatById((io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatByIdResponse>) responseObserver);
          break;
        case METHODID_RESPOND_ACTIVITY_TASK_COMPLETED:
          serviceImpl.respondActivityTaskCompleted((io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedResponse>) responseObserver);
          break;
        case METHODID_RESPOND_ACTIVITY_TASK_COMPLETED_BY_ID:
          serviceImpl.respondActivityTaskCompletedById((io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedByIdRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedByIdResponse>) responseObserver);
          break;
        case METHODID_RESPOND_ACTIVITY_TASK_FAILED:
          serviceImpl.respondActivityTaskFailed((io.temporal.api.workflowservice.v1.RespondActivityTaskFailedRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RespondActivityTaskFailedResponse>) responseObserver);
          break;
        case METHODID_RESPOND_ACTIVITY_TASK_FAILED_BY_ID:
          serviceImpl.respondActivityTaskFailedById((io.temporal.api.workflowservice.v1.RespondActivityTaskFailedByIdRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RespondActivityTaskFailedByIdResponse>) responseObserver);
          break;
        case METHODID_RESPOND_ACTIVITY_TASK_CANCELED:
          serviceImpl.respondActivityTaskCanceled((io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledResponse>) responseObserver);
          break;
        case METHODID_RESPOND_ACTIVITY_TASK_CANCELED_BY_ID:
          serviceImpl.respondActivityTaskCanceledById((io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledByIdRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledByIdResponse>) responseObserver);
          break;
        case METHODID_REQUEST_CANCEL_WORKFLOW_EXECUTION:
          serviceImpl.requestCancelWorkflowExecution((io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse>) responseObserver);
          break;
        case METHODID_SIGNAL_WORKFLOW_EXECUTION:
          serviceImpl.signalWorkflowExecution((io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.SignalWorkflowExecutionResponse>) responseObserver);
          break;
        case METHODID_SIGNAL_WITH_START_WORKFLOW_EXECUTION:
          serviceImpl.signalWithStartWorkflowExecution((io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionResponse>) responseObserver);
          break;
        case METHODID_RESET_WORKFLOW_EXECUTION:
          serviceImpl.resetWorkflowExecution((io.temporal.api.workflowservice.v1.ResetWorkflowExecutionRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.ResetWorkflowExecutionResponse>) responseObserver);
          break;
        case METHODID_TERMINATE_WORKFLOW_EXECUTION:
          serviceImpl.terminateWorkflowExecution((io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionResponse>) responseObserver);
          break;
        case METHODID_LIST_OPEN_WORKFLOW_EXECUTIONS:
          serviceImpl.listOpenWorkflowExecutions((io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsResponse>) responseObserver);
          break;
        case METHODID_LIST_CLOSED_WORKFLOW_EXECUTIONS:
          serviceImpl.listClosedWorkflowExecutions((io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsResponse>) responseObserver);
          break;
        case METHODID_LIST_WORKFLOW_EXECUTIONS:
          serviceImpl.listWorkflowExecutions((io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.ListWorkflowExecutionsResponse>) responseObserver);
          break;
        case METHODID_LIST_ARCHIVED_WORKFLOW_EXECUTIONS:
          serviceImpl.listArchivedWorkflowExecutions((io.temporal.api.workflowservice.v1.ListArchivedWorkflowExecutionsRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.ListArchivedWorkflowExecutionsResponse>) responseObserver);
          break;
        case METHODID_SCAN_WORKFLOW_EXECUTIONS:
          serviceImpl.scanWorkflowExecutions((io.temporal.api.workflowservice.v1.ScanWorkflowExecutionsRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.ScanWorkflowExecutionsResponse>) responseObserver);
          break;
        case METHODID_COUNT_WORKFLOW_EXECUTIONS:
          serviceImpl.countWorkflowExecutions((io.temporal.api.workflowservice.v1.CountWorkflowExecutionsRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.CountWorkflowExecutionsResponse>) responseObserver);
          break;
        case METHODID_GET_SEARCH_ATTRIBUTES:
          serviceImpl.getSearchAttributes((io.temporal.api.workflowservice.v1.GetSearchAttributesRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.GetSearchAttributesResponse>) responseObserver);
          break;
        case METHODID_RESPOND_QUERY_TASK_COMPLETED:
          serviceImpl.respondQueryTaskCompleted((io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedResponse>) responseObserver);
          break;
        case METHODID_RESET_STICKY_TASK_QUEUE:
          serviceImpl.resetStickyTaskQueue((io.temporal.api.workflowservice.v1.ResetStickyTaskQueueRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.ResetStickyTaskQueueResponse>) responseObserver);
          break;
        case METHODID_QUERY_WORKFLOW:
          serviceImpl.queryWorkflow((io.temporal.api.workflowservice.v1.QueryWorkflowRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.QueryWorkflowResponse>) responseObserver);
          break;
        case METHODID_DESCRIBE_WORKFLOW_EXECUTION:
          serviceImpl.describeWorkflowExecution((io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse>) responseObserver);
          break;
        case METHODID_DESCRIBE_TASK_QUEUE:
          serviceImpl.describeTaskQueue((io.temporal.api.workflowservice.v1.DescribeTaskQueueRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.DescribeTaskQueueResponse>) responseObserver);
          break;
        case METHODID_GET_CLUSTER_INFO:
          serviceImpl.getClusterInfo((io.temporal.api.workflowservice.v1.GetClusterInfoRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.GetClusterInfoResponse>) responseObserver);
          break;
        case METHODID_LIST_TASK_QUEUE_PARTITIONS:
          serviceImpl.listTaskQueuePartitions((io.temporal.api.workflowservice.v1.ListTaskQueuePartitionsRequest) request,
              (io.grpc.stub.StreamObserver<io.temporal.api.workflowservice.v1.ListTaskQueuePartitionsResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  private static abstract class WorkflowServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    WorkflowServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.temporal.api.workflowservice.v1.ServiceProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("WorkflowService");
    }
  }

  private static final class WorkflowServiceFileDescriptorSupplier
      extends WorkflowServiceBaseDescriptorSupplier {
    WorkflowServiceFileDescriptorSupplier() {}
  }

  private static final class WorkflowServiceMethodDescriptorSupplier
      extends WorkflowServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final String methodName;

    WorkflowServiceMethodDescriptorSupplier(String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (WorkflowServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new WorkflowServiceFileDescriptorSupplier())
              .addMethod(getRegisterNamespaceMethod())
              .addMethod(getDescribeNamespaceMethod())
              .addMethod(getListNamespacesMethod())
              .addMethod(getUpdateNamespaceMethod())
              .addMethod(getDeprecateNamespaceMethod())
              .addMethod(getStartWorkflowExecutionMethod())
              .addMethod(getGetWorkflowExecutionHistoryMethod())
              .addMethod(getPollWorkflowTaskQueueMethod())
              .addMethod(getRespondWorkflowTaskCompletedMethod())
              .addMethod(getRespondWorkflowTaskFailedMethod())
              .addMethod(getPollActivityTaskQueueMethod())
              .addMethod(getRecordActivityTaskHeartbeatMethod())
              .addMethod(getRecordActivityTaskHeartbeatByIdMethod())
              .addMethod(getRespondActivityTaskCompletedMethod())
              .addMethod(getRespondActivityTaskCompletedByIdMethod())
              .addMethod(getRespondActivityTaskFailedMethod())
              .addMethod(getRespondActivityTaskFailedByIdMethod())
              .addMethod(getRespondActivityTaskCanceledMethod())
              .addMethod(getRespondActivityTaskCanceledByIdMethod())
              .addMethod(getRequestCancelWorkflowExecutionMethod())
              .addMethod(getSignalWorkflowExecutionMethod())
              .addMethod(getSignalWithStartWorkflowExecutionMethod())
              .addMethod(getResetWorkflowExecutionMethod())
              .addMethod(getTerminateWorkflowExecutionMethod())
              .addMethod(getListOpenWorkflowExecutionsMethod())
              .addMethod(getListClosedWorkflowExecutionsMethod())
              .addMethod(getListWorkflowExecutionsMethod())
              .addMethod(getListArchivedWorkflowExecutionsMethod())
              .addMethod(getScanWorkflowExecutionsMethod())
              .addMethod(getCountWorkflowExecutionsMethod())
              .addMethod(getGetSearchAttributesMethod())
              .addMethod(getRespondQueryTaskCompletedMethod())
              .addMethod(getResetStickyTaskQueueMethod())
              .addMethod(getQueryWorkflowMethod())
              .addMethod(getDescribeWorkflowExecutionMethod())
              .addMethod(getDescribeTaskQueueMethod())
              .addMethod(getGetClusterInfoMethod())
              .addMethod(getListTaskQueuePartitionsMethod())
              .build();
        }
      }
    }
    return result;
  }
}
