package io.temporal.internal.payload.gen;

import static org.junit.Assert.*;

import com.google.protobuf.DescriptorProtos.*;
import com.google.protobuf.Descriptors.*;
import io.temporal.api.common.v1.Payload;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class ProtoClosureRootTest {
  @Test
  public void explicitRootsDiscoverTheirChildrenWithoutServiceSeeds() throws Exception {
    String payloadFile = Payload.getDescriptor().getFile().getName();
    FileDescriptor file =
        FileDescriptor.buildFrom(
            FileDescriptorProto.newBuilder()
                .setName("explicit-roots.proto")
                .setPackage("temporal.api.test.v1")
                .setSyntax("proto3")
                .addDependency(payloadFile)
                .addMessageType(DescriptorProto.newBuilder().setName("EmptyResponse"))
                .addMessageType(
                    DescriptorProto.newBuilder()
                        .setName("Request")
                        .addField(
                            FieldDescriptorProto.newBuilder()
                                .setName("child")
                                .setNumber(1)
                                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                                .setTypeName(".temporal.api.test.v1.Child")))
                .addMessageType(
                    DescriptorProto.newBuilder()
                        .setName("Child")
                        .addField(
                            FieldDescriptorProto.newBuilder()
                                .setName("payload")
                                .setNumber(1)
                                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                                .setTypeName(".temporal.api.common.v1.Payload")))
                .build(),
            new FileDescriptor[] {Payload.getDescriptor().getFile()});
    Descriptor request = file.findMessageTypeByName("Request");
    Descriptor empty = file.findMessageTypeByName("EmptyResponse");
    Descriptor child = file.findMessageTypeByName("Child");
    ProtoClosure closure = ProtoClosure.of(Collections.emptyList(), Arrays.asList(request, empty));
    assertTrue(closure.allMessages.contains(empty));
    assertTrue(closure.allMessages.contains(child));
    assertTrue(closure.reaches(request));
    assertTrue(closure.reaches(child));
    assertFalse(closure.reaches(empty));
  }
}
