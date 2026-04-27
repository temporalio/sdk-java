package io.temporal.springai.tool;

import io.nexusrpc.Service;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.tool.support.ToolDefinitions;
import org.springframework.ai.tool.support.ToolUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Utility class for extracting tool definitions from Temporal Nexus service interfaces.
 *
 * <p>This class bridges Spring AI's {@link Tool} annotation with Nexus RPC's {@link Service}
 * annotation, allowing Nexus service methods to be used as AI tools within workflows.
 *
 * <p>Example:
 *
 * <pre>{@code
 * @Service
 * public interface WeatherService {
 *     @Tool(description = "Get the current weather for a city")
 *     String getWeather(String city);
 * }
 *
 * // In workflow:
 * WeatherService weatherTool = Workflow.newNexusServiceStub(WeatherService.class, opts);
 * ToolCallback[] callbacks = NexusToolUtil.fromNexusServiceStub(weatherTool);
 * }</pre>
 */
public final class NexusToolUtil {

  private NexusToolUtil() {
    // Utility class
  }

  /**
   * Creates {@link ToolCallback} instances from Nexus service stub objects.
   *
   * <p>For each Nexus service stub, this method:
   *
   * <ol>
   *   <li>Finds all interfaces annotated with {@link Service}
   *   <li>Extracts methods annotated with {@link Tool}
   *   <li>Creates {@link MethodToolCallback} instances for each method
   *   <li>Wraps them in {@link NexusToolCallback} to mark their origin
   * </ol>
   *
   * <p>Methods that return functional types (Function, Supplier, Consumer) are excluded as they are
   * not supported as tools.
   *
   * @param toolObjects the Nexus service stub objects to convert
   * @return an array of ToolCallback instances
   */
  public static ToolCallback[] fromNexusServiceStub(Object... toolObjects) {
    List<ToolCallback> callbacks = new ArrayList<>();

    for (Object toolObject : toolObjects) {
      Stream.of(toolObject.getClass().getInterfaces())
          .filter(iface -> iface.isAnnotationPresent(Service.class))
          .flatMap(iface -> Stream.of(ReflectionUtils.getDeclaredMethods(iface)))
          .filter(method -> method.isAnnotationPresent(Tool.class))
          .filter(method -> !isFunctionalType(method))
          .map(method -> createToolCallback(method, toolObject))
          .map(NexusToolCallback::new)
          .forEach(callbacks::add);
    }

    return callbacks.toArray(new ToolCallback[0]);
  }

  /**
   * Checks if any interfaces implemented by the object are annotated with {@link Service} and
   * contain methods annotated with {@link Tool}.
   *
   * @param object the object to check
   * @return true if the object has tool-annotated Nexus service methods
   */
  public static boolean hasToolAnnotations(Object object) {
    return Stream.of(object.getClass().getInterfaces())
        .filter(iface -> iface.isAnnotationPresent(Service.class))
        .flatMap(iface -> Stream.of(ReflectionUtils.getDeclaredMethods(iface)))
        .anyMatch(method -> method.isAnnotationPresent(Tool.class));
  }

  private static MethodToolCallback createToolCallback(Method method, Object toolObject) {
    return MethodToolCallback.builder()
        .toolDefinition(ToolDefinitions.from(method))
        .toolMetadata(ToolMetadata.from(method))
        .toolMethod(method)
        .toolObject(toolObject)
        .toolCallResultConverter(ToolUtils.getToolCallResultConverter(method))
        .build();
  }

  private static boolean isFunctionalType(Method method) {
    Class<?> returnType = method.getReturnType();
    return ClassUtils.isAssignable(returnType, Function.class)
        || ClassUtils.isAssignable(returnType, Supplier.class)
        || ClassUtils.isAssignable(returnType, Consumer.class);
  }
}
