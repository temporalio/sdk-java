/*
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package com.uber.cadence.internal.logging;

import com.uber.cadence.internal.replay.ReplayAware;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.Marker;

public class ReplayAwareLogger implements Logger {
  private final Logger log;
  private final ReplayAware context;
  private final Supplier<Boolean> enableLoggingInReplay;

  public ReplayAwareLogger(
      Logger logger, ReplayAware context, Supplier<Boolean> enableLoggingInReplay) {
    this.log = logger;
    this.context = context;
    this.enableLoggingInReplay = enableLoggingInReplay;
  }

  @Override
  public String getName() {
    return log.getName();
  }

  @Override
  public boolean isTraceEnabled() {
    if (shouldSkipLogging()) {
      return false;
    }

    return log.isTraceEnabled();
  }

  @Override
  public void trace(String msg) {
    if (shouldSkipLogging()) return;

    log.trace(msg);
  }

  @Override
  public void trace(String format, Object arg) {
    if (shouldSkipLogging()) return;

    log.trace(format, arg);
  }

  @Override
  public void trace(String format, Object arg1, Object arg2) {
    if (shouldSkipLogging()) return;

    log.trace(format, arg1, arg2);
  }

  @Override
  public void trace(String format, Object... arguments) {
    if (shouldSkipLogging()) return;

    log.trace(format, arguments);
  }

  @Override
  public void trace(String msg, Throwable t) {
    if (shouldSkipLogging()) return;

    log.trace(msg, t);
  }

  @Override
  public boolean isTraceEnabled(Marker marker) {
    if (shouldSkipLogging()) {
      return false;
    }

    return log.isTraceEnabled(marker);
  }

  @Override
  public void trace(Marker marker, String msg) {
    if (shouldSkipLogging()) return;

    log.trace(marker, msg);
  }

  @Override
  public void trace(Marker marker, String format, Object arg) {
    if (shouldSkipLogging()) return;

    log.trace(marker, format, arg);
  }

  @Override
  public void trace(Marker marker, String format, Object arg1, Object arg2) {
    if (shouldSkipLogging()) return;

    log.trace(marker, format, arg1, arg2);
  }

  @Override
  public void trace(Marker marker, String format, Object... argArray) {
    if (shouldSkipLogging()) return;

    log.trace(marker, format, argArray);
  }

  @Override
  public void trace(Marker marker, String msg, Throwable t) {
    if (shouldSkipLogging()) return;

    log.trace(marker, msg, t);
  }

  @Override
  public boolean isDebugEnabled() {
    if (shouldSkipLogging()) {
      return false;
    }

    return log.isDebugEnabled();
  }

  @Override
  public void debug(String msg) {
    if (shouldSkipLogging()) return;

    log.debug(msg);
  }

  @Override
  public void debug(String format, Object arg) {
    if (shouldSkipLogging()) return;

    log.debug(format, arg);
  }

  @Override
  public void debug(String format, Object arg1, Object arg2) {
    if (shouldSkipLogging()) return;

    log.debug(format, arg1, arg2);
  }

  @Override
  public void debug(String format, Object... arguments) {
    if (shouldSkipLogging()) return;

    log.debug(format, arguments);
  }

  @Override
  public void debug(String msg, Throwable t) {
    if (shouldSkipLogging()) return;

    log.debug(msg, t);
  }

  @Override
  public boolean isDebugEnabled(Marker marker) {
    if (shouldSkipLogging()) {
      return false;
    }

    return log.isDebugEnabled(marker);
  }

  @Override
  public void debug(Marker marker, String msg) {
    if (shouldSkipLogging()) return;

    log.debug(marker, msg);
  }

  @Override
  public void debug(Marker marker, String format, Object arg) {
    if (shouldSkipLogging()) return;

    log.debug(marker, format, arg);
  }

  @Override
  public void debug(Marker marker, String format, Object arg1, Object arg2) {
    if (shouldSkipLogging()) return;

    log.debug(marker, format, arg1, arg2);
  }

  @Override
  public void debug(Marker marker, String format, Object... arguments) {
    if (shouldSkipLogging()) return;

    log.debug(marker, format, arguments);
  }

  @Override
  public void debug(Marker marker, String msg, Throwable t) {
    if (shouldSkipLogging()) return;

    log.debug(marker, msg, t);
  }

  @Override
  public boolean isInfoEnabled() {
    if (shouldSkipLogging()) {
      return false;
    }

    return log.isInfoEnabled();
  }

  @Override
  public void info(String msg) {
    if (shouldSkipLogging()) return;

    log.info(msg);
  }

  @Override
  public void info(String format, Object arg) {
    if (shouldSkipLogging()) return;

    log.info(format, arg);
  }

  @Override
  public void info(String format, Object arg1, Object arg2) {
    if (shouldSkipLogging()) return;

    log.info(format, arg1, arg2);
  }

  @Override
  public void info(String format, Object... arguments) {
    if (shouldSkipLogging()) return;

    log.info(format, arguments);
  }

  @Override
  public void info(String msg, Throwable t) {
    if (shouldSkipLogging()) return;

    log.info(msg, t);
  }

  @Override
  public boolean isInfoEnabled(Marker marker) {
    if (shouldSkipLogging()) {
      return false;
    }

    return log.isInfoEnabled(marker);
  }

  @Override
  public void info(Marker marker, String msg) {
    if (shouldSkipLogging()) return;

    log.info(marker, msg);
  }

  @Override
  public void info(Marker marker, String format, Object arg) {
    if (shouldSkipLogging()) return;

    log.info(marker, format, arg);
  }

  @Override
  public void info(Marker marker, String format, Object arg1, Object arg2) {
    if (shouldSkipLogging()) return;

    log.info(marker, format, arg1, arg2);
  }

  @Override
  public void info(Marker marker, String format, Object... arguments) {
    if (shouldSkipLogging()) return;

    log.info(marker, format, arguments);
  }

  @Override
  public void info(Marker marker, String msg, Throwable t) {
    if (shouldSkipLogging()) return;

    log.info(marker, msg, t);
  }

  @Override
  public boolean isWarnEnabled() {
    if (shouldSkipLogging()) {
      return false;
    }

    return log.isWarnEnabled();
  }

  @Override
  public void warn(String msg) {
    if (shouldSkipLogging()) return;

    log.warn(msg);
  }

  @Override
  public void warn(String format, Object arg) {
    if (shouldSkipLogging()) return;

    log.warn(format, arg);
  }

  @Override
  public void warn(String format, Object... arguments) {
    if (shouldSkipLogging()) return;

    log.warn(format, arguments);
  }

  @Override
  public void warn(String format, Object arg1, Object arg2) {
    if (shouldSkipLogging()) return;

    log.warn(format, arg1, arg2);
  }

  @Override
  public void warn(String msg, Throwable t) {
    if (shouldSkipLogging()) return;

    log.warn(msg, t);
  }

  @Override
  public boolean isWarnEnabled(Marker marker) {
    if (shouldSkipLogging()) {
      return false;
    }

    return log.isWarnEnabled(marker);
  }

  @Override
  public void warn(Marker marker, String msg) {
    if (shouldSkipLogging()) return;

    log.warn(marker, msg);
  }

  @Override
  public void warn(Marker marker, String format, Object arg) {
    if (shouldSkipLogging()) return;

    log.warn(marker, format, arg);
  }

  @Override
  public void warn(Marker marker, String format, Object arg1, Object arg2) {
    if (shouldSkipLogging()) return;

    log.warn(marker, format, arg1, arg2);
  }

  @Override
  public void warn(Marker marker, String format, Object... arguments) {
    if (shouldSkipLogging()) return;

    log.warn(marker, format, arguments);
  }

  @Override
  public void warn(Marker marker, String msg, Throwable t) {
    if (shouldSkipLogging()) return;

    log.warn(marker, msg, t);
  }

  @Override
  public boolean isErrorEnabled() {
    if (shouldSkipLogging()) {
      return false;
    }

    return log.isErrorEnabled();
  }

  @Override
  public void error(String msg) {
    if (shouldSkipLogging()) return;

    log.error(msg);
  }

  @Override
  public void error(String format, Object arg) {
    if (shouldSkipLogging()) return;

    log.error(format, arg);
  }

  @Override
  public void error(String format, Object arg1, Object arg2) {
    if (shouldSkipLogging()) return;

    log.error(format, arg1, arg2);
  }

  @Override
  public void error(String format, Object... arguments) {
    if (shouldSkipLogging()) return;

    log.error(format, arguments);
  }

  @Override
  public void error(String msg, Throwable t) {
    if (shouldSkipLogging()) return;

    log.error(msg, t);
  }

  @Override
  public boolean isErrorEnabled(Marker marker) {
    if (shouldSkipLogging()) {
      return false;
    }

    return log.isErrorEnabled(marker);
  }

  @Override
  public void error(Marker marker, String msg) {
    if (shouldSkipLogging()) return;

    log.error(marker, msg);
  }

  @Override
  public void error(Marker marker, String format, Object arg) {
    if (shouldSkipLogging()) return;

    log.error(marker, format, arg);
  }

  @Override
  public void error(Marker marker, String format, Object arg1, Object arg2) {
    if (shouldSkipLogging()) return;

    log.error(marker, format, arg1, arg2);
  }

  @Override
  public void error(Marker marker, String format, Object... arguments) {
    if (shouldSkipLogging()) return;

    log.error(marker, format, arguments);
  }

  @Override
  public void error(Marker marker, String msg, Throwable t) {
    if (shouldSkipLogging()) return;

    log.error(marker, msg, t);
  }

  private boolean shouldSkipLogging() {
    return context.isReplaying() && !enableLoggingInReplay.get();
  }
}
