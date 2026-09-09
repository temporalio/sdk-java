package io.temporal.internal.payload.limits.gen;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads {@link FileDescriptor}s from a {@code protoc}-emitted descriptor set file.
 *
 * <p>The generator reads descriptors from a file rather than from compiled proto classes (via
 * {@code SomeProto.getDescriptor()}) so that it does not depend on this module's own compiled
 * output. The protos are generated into this module's main source set, so a generator that needed
 * the compiled classes would form a dependency cycle with the very compilation that consumes its
 * output.
 */
final class ProtoDescriptorSets {
  private ProtoDescriptorSets() {}

  /**
   * Loads every file in {@code descriptorSetFile}, keyed by proto file name (e.g. {@code
   * temporal/api/workflowservice/v1/service.proto}).
   *
   * <p>The descriptor set must have been generated with imports included; a file referenced as a
   * dependency but absent from the set is an error.
   */
  static Map<String, FileDescriptor> load(Path descriptorSetFile)
      throws IOException, DescriptorValidationException {
    FileDescriptorSet set;
    try (InputStream in = Files.newInputStream(descriptorSetFile)) {
      // Custom options are left as unknown fields; only standard options are read here.
      set = FileDescriptorSet.parseFrom(in);
    }
    Map<String, FileDescriptorProto> protos = new LinkedHashMap<>();
    for (FileDescriptorProto proto : set.getFileList()) {
      // A file can appear more than once when several roots import it; the copies are identical.
      protos.putIfAbsent(proto.getName(), proto);
    }
    Map<String, FileDescriptor> built = new LinkedHashMap<>();
    for (String name : protos.keySet()) {
      build(name, protos, built, new LinkedHashSet<>());
    }
    return built;
  }

  /** Builds {@code name} and, depth-first, the files it imports. */
  private static FileDescriptor build(
      String name,
      Map<String, FileDescriptorProto> protos,
      Map<String, FileDescriptor> built,
      Set<String> building)
      throws DescriptorValidationException {
    FileDescriptor existing = built.get(name);
    if (existing != null) {
      return existing;
    }
    if (!building.add(name)) {
      throw new IllegalStateException("cyclic proto import involving `" + name + "`");
    }
    FileDescriptorProto proto = protos.get(name);
    if (proto == null) {
      throw new IllegalStateException(
          "descriptor set is missing imported file `"
              + name
              + "`; it must be generated with descriptorSetOptions.includeImports = true");
    }
    List<FileDescriptor> dependencies = new ArrayList<>();
    for (String dependency : proto.getDependencyList()) {
      dependencies.add(build(dependency, protos, built, building));
    }
    FileDescriptor file =
        FileDescriptor.buildFrom(proto, dependencies.toArray(new FileDescriptor[0]));
    building.remove(name);
    built.put(name, file);
    return file;
  }

  /** Looks up a file that must be present, with a message naming the missing file if it is not. */
  static FileDescriptor require(Map<String, FileDescriptor> files, String name) {
    FileDescriptor file = files.get(name);
    if (file == null) {
      throw new IllegalStateException("descriptor set does not contain `" + name + "`");
    }
    return file;
  }
}
