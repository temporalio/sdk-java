package io.temporal.internal.activity;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.uber.m3.tally.NoopScope;
import io.temporal.activity.DynamicActivity;
import io.temporal.api.common.v1.Header;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.CodecDataConverter;
import io.temporal.common.converter.CodecDataConverterTest.PrefixPayloadCodec;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.interceptors.WorkerInterceptor;
import java.util.Collections;
import java.util.Optional;
import org.junit.Test;

public class ActivityTaskExecutorsTest {
  @Test
  public void contextPropagationPreservesHeaderPresenceWithAndWithoutCodecs() {
    for (boolean enabled : new boolean[] {false, true}) {
      for (boolean present : new boolean[] {false, true}) {
        ActivityInfoInternal info = mock(ActivityInfoInternal.class);
        when(info.getNamespace()).thenReturn("namespace");
        when(info.getInput()).thenReturn(Optional.empty());
        when(info.getHeader())
            .thenReturn(present ? Optional.of(Header.getDefaultInstance()) : Optional.empty());
        InternalActivityExecutionContext context = mock(InternalActivityExecutionContext.class);
        when(context.getInfo()).thenReturn(info);
        ActivityExecutionContextFactory factory = mock(ActivityExecutionContextFactory.class);
        when(factory.createContext(eq(info), any(), any())).thenReturn(context);
        ContextPropagator propagator = mock(ContextPropagator.class);
        when(propagator.encodeHeaders()).thenReturn(enabled);
        when(propagator.deserializeContext(any())).thenReturn("context");
        DynamicActivity activity = mock(DynamicActivity.class);
        CodecDataConverter converter =
            new CodecDataConverter(
                DefaultDataConverter.STANDARD_INSTANCE,
                Collections.singletonList(new PrefixPayloadCodec()));

        new ActivityTaskExecutors.DynamicActivityImplementation(
                activity,
                converter,
                Collections.singletonList(propagator),
                new WorkerInterceptor[0],
                factory)
            .execute(info, new NoopScope());

        verify(activity).execute(any());
        verify(propagator, times(present ? 1 : 0)).deserializeContext(Collections.emptyMap());
        verify(propagator, times(present ? 1 : 0)).setCurrentContext("context");
      }
    }
  }
}
