/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.internal.sync;

import com.google.protobuf.MessageOrBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.temporal.internal.common.WorkflowExecutionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class LoggingInterceptor implements io.grpc.ServerInterceptor {
  private static final Logger log = LoggerFactory.getLogger(LoggingInterceptor.class);

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    ServerCall<ReqT, RespT> myCall =
        new ServerCall<ReqT, RespT>() {
          @Override
          public void request(int numMessages) {
            call.request(numMessages);
          }

          @Override
          public void sendHeaders(Metadata headers) {
            call.sendHeaders(headers);
          }

          @Override
          public void sendMessage(RespT message) {
            log.trace(
                "Reply to "
                    + call.getMethodDescriptor().getFullMethodName()
                    + " with output:\n"
                    + WorkflowExecutionUtils.prettyPrintObject((MessageOrBuilder) message));

            call.sendMessage(message);
          }

          @Override
          public void close(Status status, Metadata trailers) {
            call.close(status, trailers);
          }

          @Override
          public boolean isCancelled() {
            return call.isCancelled();
          }

          @Override
          public MethodDescriptor<ReqT, RespT> getMethodDescriptor() {
            return call.getMethodDescriptor();
          }
        };
    ServerCall.Listener<ReqT> listener = next.startCall(myCall, headers);

    return new ServerCall.Listener<ReqT>() {
      @Override
      public void onMessage(ReqT message) {
        log.trace(
            "Received request "
                + call.getMethodDescriptor().getFullMethodName()
                + " with input:\n"
                + WorkflowExecutionUtils.prettyPrintObject((MessageOrBuilder) message));
        listener.onMessage(message);
      }

      @Override
      public void onComplete() {
        listener.onComplete();
      }

      @Override
      public void onHalfClose() {
        listener.onHalfClose();
      }

      @Override
      public void onCancel() {
        listener.onCancel();
      }

      @Override
      public void onReady() {
        listener.onReady();
      }
    };
  }
}
