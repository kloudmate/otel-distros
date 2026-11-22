package com.kloudmate.otel;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.util.List;

/**
 * The configured sampler until a remote setting (contract §4) says otherwise: {@code send_traces: false} drops every
 * span, and a {@code sampling_rate} samples parent-based at that ratio.
 */
final class KmRemoteSampler implements Sampler {
  private final Sampler delegate;
  private volatile Sampler override;

  KmRemoteSampler(Sampler delegate) {
    this.delegate = delegate;
  }

  void set(Double rate, boolean send) {
    if (!send) {
      override = Sampler.alwaysOff();
    } else if (rate != null) {
      override = Sampler.parentBased(Sampler.traceIdRatioBased(rate));
    } else {
      override = null;
    }
  }

  private Sampler current() {
    Sampler o = override;
    return o != null ? o : delegate;
  }

  @Override
  public SamplingResult shouldSample(
      Context parentContext, String traceId, String name, SpanKind spanKind, Attributes attributes, List<LinkData> parentLinks) {
    return current().shouldSample(parentContext, traceId, name, spanKind, attributes, parentLinks);
  }

  @Override
  public String getDescription() {
    return "KloudMateRemote{" + current().getDescription() + "}";
  }
}
