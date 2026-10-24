package io.temporal.common.converter;

import io.temporal.payload.codec.PayloadCodecProvider;

/** A data converter exposing its codec-only stage, without opting into header processing. */
public interface DataConverterWithCodec extends DataConverter, PayloadCodecProvider {}
