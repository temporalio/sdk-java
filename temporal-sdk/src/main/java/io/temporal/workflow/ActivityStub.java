package io.temporal.workflow;

import java.lang.reflect.Type;

/**
 * ActivityStub is used to call an activity without referencing an interface that it implements. This is
 * useful for calling activities when their type is not known at compile time, or for executing activities
 * implemented in other languages. Created through {@link Workflow#newActivityStub(Class)}.
 */
public interface ActivityStub {

  /**
   * Executes an activity by its type name and arguments. Blocks until activity completion.
   *
   * @param activityName name of an activity type to execute.
   * @param resultClass the expected return type of the activity. Use Void.class for activities that
   *     return void type.
   * @param args arguments of the activity.
   * @param <R> return type.
   * @return an activity result.
   */
  <R> R execute(String activityName, Class<R> resultClass, Object... args);

  /**
   * Executes an activity by its type name and arguments. Blocks until activity completion.
   *
   * @param activityName name of an activity type to execute.
   * @param resultClass the expected return class of the activity. Use Void.class for activities
   *     that return void type.
   * @param resultType the expected return type of the activity. Differs from resultClass for
   *     generic types.
   * @param args arguments of the activity.
   * @param <R> return type.
   * @return an activity result.
   */
  <R> R execute(String activityName, Class<R> resultClass, Type resultType, Object... args);

  /**
   * Executes an activity asynchronously by its type name and arguments.
   *
   * @param activityName name of an activity type to execute.
   * @param resultClass the expected return type of the activity. Use Void.class for activities that
   *     return void type.
   * @param args arguments of the activity.
   * @param <R> return type.
   * @return Promise to the activity result.
   */
  <R> Promise<R> executeAsync(String activityName, Class<R> resultClass, Object... args);

  /**
   * Executes an activity asynchronously by its type name and arguments.
   *
   * @param activityName name of an activity type to execute.
   * @param resultClass the expected return class of the activity. Use Void.class for activities
   *     that return void type.
   * @param resultType the expected return type of the activity. Differs from resultClass for
   *     generic types.
   * @param args arguments of the activity.
   * @param <R> return type.
   * @return Promise to the activity result.
   */
  <R> Promise<R> executeAsync(
      String activityName, Class<R> resultClass, Type resultType, Object... args);
}
