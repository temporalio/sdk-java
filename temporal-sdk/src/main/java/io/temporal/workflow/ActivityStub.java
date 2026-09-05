package io.temporal.workflow;

import java.lang.reflect.Type;

/**
 * ActivityStub is used to call an activity without referencing an interface it implements. This is
 * useful to call activities when their type is not known at compile time or to execute activities
 * implemented in other languages. Created through {@link Workflow#newActivityStub(Class)}.
 */
public interface ActivityStub {

  /**
   * Executes an activity by its type name and arguments. Blocks until the activity completion.
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
   * Executes an activity by its type name and arguments. Blocks until the activity completion.
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

  /**
   * Executes an Activity with options that apply only to this invocation. Blocks until completion.
   *
   * @param activityName name of an Activity type to execute.
   * @param resultClass expected return type of the Activity.
   * @param options options for this invocation.
   * @param args arguments of the Activity.
   * @param <R> return type.
   * @return Activity result.
   */
  <R> R execute(
      String activityName, Class<R> resultClass, ActivityInvocationOptions options, Object... args);

  /**
   * Executes an Activity with options that apply only to this invocation. Blocks until completion.
   *
   * @param activityName name of an Activity type to execute.
   * @param resultClass expected return class of the Activity.
   * @param resultType expected return type of the Activity. Differs from {@code resultClass} for
   *     generic types.
   * @param options options for this invocation.
   * @param args arguments of the Activity.
   * @param <R> return type.
   * @return Activity result.
   */
  <R> R execute(
      String activityName,
      Class<R> resultClass,
      Type resultType,
      ActivityInvocationOptions options,
      Object... args);

  /**
   * Executes an Activity asynchronously with options that apply only to this invocation.
   *
   * @param activityName name of an Activity type to execute.
   * @param resultClass expected return type of the Activity.
   * @param options options for this invocation.
   * @param args arguments of the Activity.
   * @param <R> return type.
   * @return Promise to the Activity result.
   */
  <R> Promise<R> executeAsync(
      String activityName, Class<R> resultClass, ActivityInvocationOptions options, Object... args);

  /**
   * Executes an Activity asynchronously with options that apply only to this invocation.
   *
   * @param activityName name of an Activity type to execute.
   * @param resultClass expected return class of the Activity.
   * @param resultType expected return type of the Activity. Differs from {@code resultClass} for
   *     generic types.
   * @param options options for this invocation.
   * @param args arguments of the Activity.
   * @param <R> return type.
   * @return Promise to the Activity result.
   */
  <R> Promise<R> executeAsync(
      String activityName,
      Class<R> resultClass,
      Type resultType,
      ActivityInvocationOptions options,
      Object... args);
}
