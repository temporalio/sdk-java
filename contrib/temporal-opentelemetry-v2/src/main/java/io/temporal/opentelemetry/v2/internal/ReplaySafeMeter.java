package io.temporal.opentelemetry.v2.internal;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.BatchCallback;
import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.api.metrics.DoubleCounterBuilder;
import io.opentelemetry.api.metrics.DoubleGauge;
import io.opentelemetry.api.metrics.DoubleGaugeBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.DoubleHistogramBuilder;
import io.opentelemetry.api.metrics.DoubleUpDownCounter;
import io.opentelemetry.api.metrics.DoubleUpDownCounterBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongCounterBuilder;
import io.opentelemetry.api.metrics.LongGauge;
import io.opentelemetry.api.metrics.LongGaugeBuilder;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.LongHistogramBuilder;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.LongUpDownCounterBuilder;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableDoubleCounter;
import io.opentelemetry.api.metrics.ObservableDoubleGauge;
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement;
import io.opentelemetry.api.metrics.ObservableDoubleUpDownCounter;
import io.opentelemetry.api.metrics.ObservableLongCounter;
import io.opentelemetry.api.metrics.ObservableLongGauge;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;
import io.opentelemetry.api.metrics.ObservableLongUpDownCounter;
import io.opentelemetry.api.metrics.ObservableMeasurement;
import io.opentelemetry.context.Context;
import java.util.List;
import java.util.function.Consumer;

/**
 * Wraps a meter so the synchronous instruments it builds drop recordings made by replaying workflow
 * code, which would otherwise be recorded again on every replay.
 */
public final class ReplaySafeMeter implements Meter {
  private final Meter delegate;

  public ReplaySafeMeter(Meter delegate) {
    this.delegate = delegate;
  }

  @Override
  public LongCounterBuilder counterBuilder(String name) {
    return new ReplaySafeLongCounterBuilder(delegate.counterBuilder(name));
  }

  @Override
  public LongUpDownCounterBuilder upDownCounterBuilder(String name) {
    return new ReplaySafeLongUpDownCounterBuilder(delegate.upDownCounterBuilder(name));
  }

  @Override
  public DoubleHistogramBuilder histogramBuilder(String name) {
    return new ReplaySafeDoubleHistogramBuilder(delegate.histogramBuilder(name));
  }

  @Override
  public DoubleGaugeBuilder gaugeBuilder(String name) {
    return new ReplaySafeDoubleGaugeBuilder(delegate.gaugeBuilder(name));
  }

  @Override
  public BatchCallback batchCallback(
      Runnable callback,
      ObservableMeasurement observableMeasurement,
      ObservableMeasurement... additionalMeasurements) {
    return delegate.batchCallback(callback, observableMeasurement, additionalMeasurements);
  }

  private static final class ReplaySafeLongCounterBuilder implements LongCounterBuilder {
    private final LongCounterBuilder delegate;

    ReplaySafeLongCounterBuilder(LongCounterBuilder delegate) {
      this.delegate = delegate;
    }

    @Override
    public LongCounterBuilder setDescription(String description) {
      delegate.setDescription(description);
      return this;
    }

    @Override
    public LongCounterBuilder setUnit(String unit) {
      delegate.setUnit(unit);
      return this;
    }

    @Override
    public DoubleCounterBuilder ofDoubles() {
      return new ReplaySafeDoubleCounterBuilder(delegate.ofDoubles());
    }

    @Override
    public LongCounter build() {
      return new ReplaySafeLongCounter(delegate.build());
    }

    @Override
    public ObservableLongCounter buildWithCallback(Consumer<ObservableLongMeasurement> callback) {
      return delegate.buildWithCallback(callback);
    }

    @Override
    public ObservableLongMeasurement buildObserver() {
      return delegate.buildObserver();
    }
  }

  private static final class ReplaySafeDoubleCounterBuilder implements DoubleCounterBuilder {
    private final DoubleCounterBuilder delegate;

    ReplaySafeDoubleCounterBuilder(DoubleCounterBuilder delegate) {
      this.delegate = delegate;
    }

    @Override
    public DoubleCounterBuilder setDescription(String description) {
      delegate.setDescription(description);
      return this;
    }

    @Override
    public DoubleCounterBuilder setUnit(String unit) {
      delegate.setUnit(unit);
      return this;
    }

    @Override
    public DoubleCounter build() {
      return new ReplaySafeDoubleCounter(delegate.build());
    }

    @Override
    public ObservableDoubleCounter buildWithCallback(
        Consumer<ObservableDoubleMeasurement> callback) {
      return delegate.buildWithCallback(callback);
    }

    @Override
    public ObservableDoubleMeasurement buildObserver() {
      return delegate.buildObserver();
    }
  }

  private static final class ReplaySafeLongUpDownCounterBuilder
      implements LongUpDownCounterBuilder {
    private final LongUpDownCounterBuilder delegate;

    ReplaySafeLongUpDownCounterBuilder(LongUpDownCounterBuilder delegate) {
      this.delegate = delegate;
    }

    @Override
    public LongUpDownCounterBuilder setDescription(String description) {
      delegate.setDescription(description);
      return this;
    }

    @Override
    public LongUpDownCounterBuilder setUnit(String unit) {
      delegate.setUnit(unit);
      return this;
    }

    @Override
    public DoubleUpDownCounterBuilder ofDoubles() {
      return new ReplaySafeDoubleUpDownCounterBuilder(delegate.ofDoubles());
    }

    @Override
    public LongUpDownCounter build() {
      return new ReplaySafeLongUpDownCounter(delegate.build());
    }

    @Override
    public ObservableLongUpDownCounter buildWithCallback(
        Consumer<ObservableLongMeasurement> callback) {
      return delegate.buildWithCallback(callback);
    }

    @Override
    public ObservableLongMeasurement buildObserver() {
      return delegate.buildObserver();
    }
  }

  private static final class ReplaySafeDoubleUpDownCounterBuilder
      implements DoubleUpDownCounterBuilder {
    private final DoubleUpDownCounterBuilder delegate;

    ReplaySafeDoubleUpDownCounterBuilder(DoubleUpDownCounterBuilder delegate) {
      this.delegate = delegate;
    }

    @Override
    public DoubleUpDownCounterBuilder setDescription(String description) {
      delegate.setDescription(description);
      return this;
    }

    @Override
    public DoubleUpDownCounterBuilder setUnit(String unit) {
      delegate.setUnit(unit);
      return this;
    }

    @Override
    public DoubleUpDownCounter build() {
      return new ReplaySafeDoubleUpDownCounter(delegate.build());
    }

    @Override
    public ObservableDoubleUpDownCounter buildWithCallback(
        Consumer<ObservableDoubleMeasurement> callback) {
      return delegate.buildWithCallback(callback);
    }

    @Override
    public ObservableDoubleMeasurement buildObserver() {
      return delegate.buildObserver();
    }
  }

  private static final class ReplaySafeDoubleHistogramBuilder implements DoubleHistogramBuilder {
    private final DoubleHistogramBuilder delegate;

    ReplaySafeDoubleHistogramBuilder(DoubleHistogramBuilder delegate) {
      this.delegate = delegate;
    }

    @Override
    public DoubleHistogramBuilder setDescription(String description) {
      delegate.setDescription(description);
      return this;
    }

    @Override
    public DoubleHistogramBuilder setUnit(String unit) {
      delegate.setUnit(unit);
      return this;
    }

    @Override
    public DoubleHistogramBuilder setExplicitBucketBoundariesAdvice(List<Double> bucketBoundaries) {
      delegate.setExplicitBucketBoundariesAdvice(bucketBoundaries);
      return this;
    }

    @Override
    public LongHistogramBuilder ofLongs() {
      return new ReplaySafeLongHistogramBuilder(delegate.ofLongs());
    }

    @Override
    public DoubleHistogram build() {
      return new ReplaySafeDoubleHistogram(delegate.build());
    }
  }

  private static final class ReplaySafeLongHistogramBuilder implements LongHistogramBuilder {
    private final LongHistogramBuilder delegate;

    ReplaySafeLongHistogramBuilder(LongHistogramBuilder delegate) {
      this.delegate = delegate;
    }

    @Override
    public LongHistogramBuilder setDescription(String description) {
      delegate.setDescription(description);
      return this;
    }

    @Override
    public LongHistogramBuilder setUnit(String unit) {
      delegate.setUnit(unit);
      return this;
    }

    @Override
    public LongHistogramBuilder setExplicitBucketBoundariesAdvice(List<Long> bucketBoundaries) {
      delegate.setExplicitBucketBoundariesAdvice(bucketBoundaries);
      return this;
    }

    @Override
    public LongHistogram build() {
      return new ReplaySafeLongHistogram(delegate.build());
    }
  }

  private static final class ReplaySafeDoubleGaugeBuilder implements DoubleGaugeBuilder {
    private final DoubleGaugeBuilder delegate;

    ReplaySafeDoubleGaugeBuilder(DoubleGaugeBuilder delegate) {
      this.delegate = delegate;
    }

    @Override
    public DoubleGaugeBuilder setDescription(String description) {
      delegate.setDescription(description);
      return this;
    }

    @Override
    public DoubleGaugeBuilder setUnit(String unit) {
      delegate.setUnit(unit);
      return this;
    }

    @Override
    public LongGaugeBuilder ofLongs() {
      return new ReplaySafeLongGaugeBuilder(delegate.ofLongs());
    }

    @Override
    public ObservableDoubleGauge buildWithCallback(Consumer<ObservableDoubleMeasurement> callback) {
      return delegate.buildWithCallback(callback);
    }

    @Override
    public ObservableDoubleMeasurement buildObserver() {
      return delegate.buildObserver();
    }

    @Override
    public DoubleGauge build() {
      return new ReplaySafeDoubleGauge(delegate.build());
    }
  }

  private static final class ReplaySafeLongGaugeBuilder implements LongGaugeBuilder {
    private final LongGaugeBuilder delegate;

    ReplaySafeLongGaugeBuilder(LongGaugeBuilder delegate) {
      this.delegate = delegate;
    }

    @Override
    public LongGaugeBuilder setDescription(String description) {
      delegate.setDescription(description);
      return this;
    }

    @Override
    public LongGaugeBuilder setUnit(String unit) {
      delegate.setUnit(unit);
      return this;
    }

    @Override
    public ObservableLongGauge buildWithCallback(Consumer<ObservableLongMeasurement> callback) {
      return delegate.buildWithCallback(callback);
    }

    @Override
    public ObservableLongMeasurement buildObserver() {
      return delegate.buildObserver();
    }

    @Override
    public LongGauge build() {
      return new ReplaySafeLongGauge(delegate.build());
    }
  }

  private static final class ReplaySafeLongCounter implements LongCounter {
    private final LongCounter delegate;

    ReplaySafeLongCounter(LongCounter delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean isEnabled() {
      return delegate.isEnabled();
    }

    @Override
    public void add(long value) {
      if (OpenTelemetrySuppression.shouldSuppress()) {
        return;
      }
      delegate.add(value);
    }

    @Override
    public void add(long value, Attributes attributes) {
      if (OpenTelemetrySuppression.shouldSuppress()) {
        return;
      }
      delegate.add(value, attributes);
    }

    @Override
    public void add(long value, Attributes attributes, Context context) {
      if (OpenTelemetrySuppression.shouldSuppress()) {
        return;
      }
      delegate.add(value, attributes, context);
    }
  }

  private static final class ReplaySafeDoubleCounter implements DoubleCounter {
    private final DoubleCounter delegate;

    ReplaySafeDoubleCounter(DoubleCounter delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean isEnabled() {
      return delegate.isEnabled();
    }

    @Override
    public void add(double value) {
      if (OpenTelemetrySuppression.shouldSuppress()) {
        return;
      }
      delegate.add(value);
    }

    @Override
    public void add(double value, Attributes attributes) {
      if (OpenTelemetrySuppression.shouldSuppress()) {
        return;
      }
      delegate.add(value, attributes);
    }

    @Override
    public void add(double value, Attributes attributes, Context context) {
      if (OpenTelemetrySuppression.shouldSuppress()) {
        return;
      }
      delegate.add(value, attributes, context);
    }
  }

  private static final class ReplaySafeLongUpDownCounter implements LongUpDownCounter {
    private final LongUpDownCounter delegate;

    ReplaySafeLongUpDownCounter(LongUpDownCounter delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean isEnabled() {
      return delegate.isEnabled();
    }

    @Override
    public void add(long value) {
      if (OpenTelemetrySuppression.shouldSuppress()) {
        return;
      }
      delegate.add(value);
    }

    @Override
    public void add(long value, Attributes attributes) {
      if (OpenTelemetrySuppression.shouldSuppress()) {
        return;
      }
      delegate.add(value, attributes);
    }

    @Override
    public void add(long value, Attributes attributes, Context context) {
      if (OpenTelemetrySuppression.shouldSuppress()) {
        return;
      }
      delegate.add(value, attributes, context);
    }
  }

  private static final class ReplaySafeDoubleUpDownCounter implements DoubleUpDownCounter {
    private final DoubleUpDownCounter delegate;

    ReplaySafeDoubleUpDownCounter(DoubleUpDownCounter delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean isEnabled() {
      return delegate.isEnabled();
    }

    @Override
    public void add(double value) {
      if (OpenTelemetrySuppression.shouldSuppress()) {
        return;
      }
      delegate.add(value);
    }

    @Override
    public void add(double value, Attributes attributes) {
      if (OpenTelemetrySuppression.shouldSuppress()) {
        return;
      }
      delegate.add(value, attributes);
    }

    @Override
    public void add(double value, Attributes attributes, Context context) {
      if (OpenTelemetrySuppression.shouldSuppress()) {
        return;
      }
      delegate.add(value, attributes, context);
    }
  }

  private static final class ReplaySafeDoubleHistogram implements DoubleHistogram {
    private final DoubleHistogram delegate;

    ReplaySafeDoubleHistogram(DoubleHistogram delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean isEnabled() {
      return delegate.isEnabled();
    }

    @Override
    public void record(double value) {
      if (OpenTelemetrySuppression.shouldSuppress()) {
        return;
      }
      delegate.record(value);
    }

    @Override
    public void record(double value, Attributes attributes) {
      if (OpenTelemetrySuppression.shouldSuppress()) {
        return;
      }
      delegate.record(value, attributes);
    }

    @Override
    public void record(double value, Attributes attributes, Context context) {
      if (OpenTelemetrySuppression.shouldSuppress()) {
        return;
      }
      delegate.record(value, attributes, context);
    }
  }

  private static final class ReplaySafeLongHistogram implements LongHistogram {
    private final LongHistogram delegate;

    ReplaySafeLongHistogram(LongHistogram delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean isEnabled() {
      return delegate.isEnabled();
    }

    @Override
    public void record(long value) {
      if (OpenTelemetrySuppression.shouldSuppress()) {
        return;
      }
      delegate.record(value);
    }

    @Override
    public void record(long value, Attributes attributes) {
      if (OpenTelemetrySuppression.shouldSuppress()) {
        return;
      }
      delegate.record(value, attributes);
    }

    @Override
    public void record(long value, Attributes attributes, Context context) {
      if (OpenTelemetrySuppression.shouldSuppress()) {
        return;
      }
      delegate.record(value, attributes, context);
    }
  }

  private static final class ReplaySafeDoubleGauge implements DoubleGauge {
    private final DoubleGauge delegate;

    ReplaySafeDoubleGauge(DoubleGauge delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean isEnabled() {
      return delegate.isEnabled();
    }

    @Override
    public void set(double value) {
      if (OpenTelemetrySuppression.shouldSuppress()) {
        return;
      }
      delegate.set(value);
    }

    @Override
    public void set(double value, Attributes attributes) {
      if (OpenTelemetrySuppression.shouldSuppress()) {
        return;
      }
      delegate.set(value, attributes);
    }

    @Override
    public void set(double value, Attributes attributes, Context context) {
      if (OpenTelemetrySuppression.shouldSuppress()) {
        return;
      }
      delegate.set(value, attributes, context);
    }
  }

  private static final class ReplaySafeLongGauge implements LongGauge {
    private final LongGauge delegate;

    ReplaySafeLongGauge(LongGauge delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean isEnabled() {
      return delegate.isEnabled();
    }

    @Override
    public void set(long value) {
      if (OpenTelemetrySuppression.shouldSuppress()) {
        return;
      }
      delegate.set(value);
    }

    @Override
    public void set(long value, Attributes attributes) {
      if (OpenTelemetrySuppression.shouldSuppress()) {
        return;
      }
      delegate.set(value, attributes);
    }

    @Override
    public void set(long value, Attributes attributes, Context context) {
      if (OpenTelemetrySuppression.shouldSuppress()) {
        return;
      }
      delegate.set(value, attributes, context);
    }
  }
}
