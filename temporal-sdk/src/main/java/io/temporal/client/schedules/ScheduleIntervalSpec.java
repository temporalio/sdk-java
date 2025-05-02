package io.temporal.client.schedules;

import com.google.common.base.Preconditions;
import java.time.Duration;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Specification for scheduling on an interval. Matching times are expressed as
 *
 * <p>epoch + (n * every) + offset.
 */
public final class ScheduleIntervalSpec {
  private final Duration every;
  private final Duration offset;

  /**
   * Construct a ScheduleIntervalSpec
   *
   * @param every Period to repeat the interval
   */
  public ScheduleIntervalSpec(@Nonnull Duration every) {
    this(every, null);
  }

  /**
   * Construct a ScheduleIntervalSpec
   *
   * @param every Period to repeat the interval
   * @param offset Fixed offset added to each interval period.
   */
  public ScheduleIntervalSpec(@Nonnull Duration every, @Nullable Duration offset) {
    this.every = Preconditions.checkNotNull(every);
    this.offset = offset;
  }

  /**
   * Period to repeat the interval.
   *
   * @return period to repeat
   */
  public @Nonnull Duration getEvery() {
    return every;
  }

  /**
   * Fixed offset added to each interval period.
   *
   * @return offset interval
   */
  public @Nullable Duration getOffset() {
    return offset;
  }

  @Override
  public String toString() {
    return "ScheduleIntervalSpec{" + "every=" + every + ", offset=" + offset + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ScheduleIntervalSpec that = (ScheduleIntervalSpec) o;
    return Objects.equals(every, that.every) && Objects.equals(offset, that.offset);
  }

  @Override
  public int hashCode() {
    return Objects.hash(every, offset);
  }
}
