package io.temporal.springai.tool;

import io.temporal.activity.ActivityInterface;
import io.temporal.common.metadata.POJOActivityInterfaceMetadata;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
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
 * Utility class for extracting tool definitions from Temporal activity interfaces.
 *
 * <p>This class bridges Spring AI's {@link Tool} annotation with Temporal's {@link
 * ActivityInterface} annotation, allowing activity methods to be used as AI tools within workflows.
 *
 * <p>Example:
 *
 * <pre>{@code
 * @ActivityInterface
 * public interface WeatherActivity {
 *     @Tool(description = "Get the current weather for a city")
 *     String getWeather(String city);
 * }
 *
 * // In workflow:
 * WeatherActivity weatherTool = Workflow.newActivityStub(WeatherActivity.class, opts);
 * ToolCallback[] callbacks = ActivityToolUtil.fromActivityStub(weatherTool);
 * }</pre>
 */
public final class ActivityToolUtil {

  private ActivityToolUtil() {
    // Utility class
  }

  /**
   * Extracts {@link Tool} annotations from the given activity stub object.
   *
   * <p>Scans all interfaces implemented by the stub that are annotated with {@link
   * ActivityInterface}, and returns a map of activity type names to their {@link Tool} annotations.
   *
   * @param activityStub the activity stub to extract annotations from
   * @return a map of activity type names to Tool annotations
   */
  public static Map<String, Tool> getToolAnnotations(Object activityStub) {
    return Stream.of(activityStub.getClass().getInterfaces())
        .filter(iface -> iface.isAnnotationPresent(ActivityInterface.class))
        .map(POJOActivityInterfaceMetadata::newInstance)
        .flatMap(metadata -> metadata.getMethodsMetadata().stream())
        .filter(methodMetadata -> methodMetadata.getMethod().isAnnotationPresent(Tool.class))
        .collect(
            Collectors.toMap(
                methodMetadata -> methodMetadata.getActivityTypeName(),
                methodMetadata -> methodMetadata.getMethod().getAnnotation(Tool.class)));
  }

  /**
   * Creates {@link ToolCallback} instances from activity stub objects.
   *
   * <p>For each activity stub, this method:
   *
   * <ol>
   *   <li>Finds all interfaces annotated with {@link ActivityInterface}
   *   <li>Extracts methods annotated with {@link Tool}
   *   <li>Creates {@link MethodToolCallback} instances for each method
   *   <li>Wraps them in {@link ActivityToolCallback} to mark their origin
   * </ol>
   *
   * <p>Methods that return functional types (Function, Supplier, Consumer) are excluded as they are
   * not supported as tools.
   *
   * @param toolObjects the activity stub objects to convert
   * @return an array of ToolCallback instances
   */
  public static ToolCallback[] fromActivityStub(Object... toolObjects) {
    List<ToolCallback> callbacks = new ArrayList<>();

    for (Object toolObject : toolObjects) {
      Stream.of(toolObject.getClass().getInterfaces())
          .filter(iface -> iface.isAnnotationPresent(ActivityInterface.class))
          .flatMap(iface -> Stream.of(ReflectionUtils.getDeclaredMethods(iface)))
          .filter(method -> method.isAnnotationPresent(Tool.class))
          .filter(method -> !isFunctionalType(method))
          .map(method -> createToolCallback(method, toolObject))
          .map(ActivityToolCallback::new)
          .forEach(callbacks::add);
    }

    return callbacks.toArray(new ToolCallback[0]);
  }

  /**
   * Checks if any interfaces implemented by the object are annotated with {@link ActivityInterface}
   * and contain methods annotated with {@link Tool}.
   *
   * @param object the object to check
   * @return true if the object has tool-annotated activity methods
   */
  public static boolean hasToolAnnotations(Object object) {
    return Stream.of(object.getClass().getInterfaces())
        .filter(iface -> iface.isAnnotationPresent(ActivityInterface.class))
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
