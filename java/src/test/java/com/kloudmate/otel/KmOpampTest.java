package com.kloudmate.otel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KmOpampTest {
  private static byte[] reply(String remoteJson, String hash, long flags) {
    KmOpamp.Pb m = new KmOpamp.Pb();
    if (remoteJson != null) {
      KmOpamp.Pb cfg = KmOpamp.configMap("kloudmate", remoteJson.getBytes(StandardCharsets.UTF_8), "application/json");
      m.msg(3, new KmOpamp.Pb().msg(1, cfg).bytes(2, hash.getBytes(StandardCharsets.UTF_8)));
    }
    return m.uint(6, flags).toByteArray();
  }

  private static KmOpamp client(Set<String> appSet) {
    KmStatus st = new KmStatus();
    st.serviceId = "systemd:web.service";
    KmOpamp c = new KmOpamp("http://x/v1/opamp", st, "/nonexistent-km", 1000,
        (url, body) -> new byte[0], appSet::contains, true);
    c.sampler = new KmRemoteSampler(Sampler.alwaysOn());
    return c;
  }

  private static SamplingDecision decision(KmOpamp c) {
    return c.sampler.shouldSample(Context.root(), "7fffffffffffffff0000000000000000", "x", SpanKind.INTERNAL,
        Attributes.empty(), Collections.emptyList()).getDecision();
  }

  @Test
  void messageFields() throws Exception {
    KmOpamp c = client(Collections.<String>emptySet());
    c.serviceName = "web";
    Map<Integer, List<Object>> f = KmOpamp.Pb.decode(c.message());
    assertArrayEquals(c.uid, KmOpamp.Pb.lastBytes(f, 1));
    assertEquals(7, (c.uid[6] >> 4) & 0xF);
    assertEquals(KmOpamp.CAPABILITIES, KmOpamp.Pb.lastLong(f, 4));
    Map<String, String> ident = new java.util.HashMap<String, String>();
    for (Object kv : KmOpamp.Pb.all(KmOpamp.Pb.decode(KmOpamp.Pb.lastBytes(f, 3)), 1)) {
      Map<Integer, List<Object>> k = KmOpamp.Pb.decode((byte[]) kv);
      ident.put(new String(KmOpamp.Pb.lastBytes(k, 1), StandardCharsets.UTF_8),
          new String(KmOpamp.Pb.lastBytes(KmOpamp.Pb.decode(KmOpamp.Pb.lastBytes(k, 2)), 1), StandardCharsets.UTF_8));
    }
    assertEquals("web", ident.get("service.name"));
    assertEquals("systemd:web.service", ident.get("km.service_id"));
    Map<Integer, List<Object>> health = KmOpamp.Pb.decode(KmOpamp.Pb.lastBytes(f, 5));
    assertEquals(1, KmOpamp.Pb.lastLong(health, 1));
    assertEquals("ok", new String(KmOpamp.Pb.lastBytes(health, 4), StandardCharsets.UTF_8));
    Map<Integer, List<Object>> entry = KmOpamp.Pb.decode(KmOpamp.Pb.lastBytes(
        KmOpamp.Pb.decode(KmOpamp.Pb.lastBytes(KmOpamp.Pb.decode(KmOpamp.Pb.lastBytes(f, 6)), 1)), 1));
    assertEquals("kloudmate.status", new String(KmOpamp.Pb.lastBytes(entry, 1), StandardCharsets.UTF_8));
    String body = new String(KmOpamp.Pb.lastBytes(KmOpamp.Pb.decode(KmOpamp.Pb.lastBytes(entry, 2)), 1), StandardCharsets.UTF_8);
    assertTrue(body.startsWith("{") && body.contains("\"km.service_id\":\"systemd:web.service\""), body);
    c.pollOnce();
    assertFalse(KmOpamp.Pb.decode(c.message()).containsKey(3), "agent_description only until the server has it");
  }

  @Test
  void remoteSettingsAppliedLive() {
    KmOpamp c = client(Collections.<String>emptySet());
    assertEquals(SamplingDecision.RECORD_AND_SAMPLE, decision(c));
    c.handle(reply("{\"sampling_rate\": 0, \"logging_level\": \"debug\", \"future_key\": [1, {\"a\": null}]}", "h1", 0));
    assertEquals(SamplingDecision.DROP, decision(c));
    assertArrayEquals(new String[] {"0", "remote"}, c.status.effective.get("sampling_rate"));
    assertEquals(KmOpamp.APPLIED, c.remoteStatus);
    assertEquals("not applied live: logging_level", c.remoteError);
    Map<Integer, List<Object>> rcs = KmOpamp.Pb.decode(KmOpamp.Pb.lastBytes(KmOpamp.Pb.decode(c.message()), 7));
    assertEquals("h1", new String(KmOpamp.Pb.lastBytes(rcs, 1), StandardCharsets.UTF_8));
    assertEquals(KmOpamp.APPLIED, KmOpamp.Pb.lastLong(rcs, 2));
    c.handle(reply("{}", "h2", 0));
    assertEquals(SamplingDecision.RECORD_AND_SAMPLE, decision(c));
    assertFalse(c.status.effective.containsKey("sampling_rate"));
    c.handle(reply("{\"send_traces\": false, \"sampling_rate\": 0.50}", "h3", 0));
    assertEquals(SamplingDecision.DROP, decision(c));
    assertArrayEquals(new String[] {"0.5", "remote"}, c.status.effective.get("sampling_rate"));
  }

  @Test
  void settingsBeforeTheSamplerExistsApplyWhenItDoes() {
    KmOpamp c = client(Collections.<String>emptySet());
    c.sampler = null;
    c.handle(reply("{\"send_traces\": false}", "h1", 0));
    assertEquals("", c.remoteError);
    KmRemoteSampler s = new KmRemoteSampler(Sampler.alwaysOn());
    synchronized (c) {
      c.sampler = s;
      s.set(c.liveRate, c.liveSend);
    }
    assertEquals(SamplingDecision.DROP, decision(c));
  }

  @Test
  void invalidConfigFailsAndChangesNothing() {
    KmOpamp c = client(Collections.<String>emptySet());
    for (String[] bad : new String[][] {
        {"{\"sampling_rate\": 2}", "sampling_rate"}, {"{\"sampling_rate\": true}", "sampling_rate"},
        {"{\"send_traces\": \"no\"}", "send_traces"}, {"[1, 2]", "JSON object"}, {"{oops", "JSON object"}}) {
      c.remoteHash = new byte[0];
      c.handle(reply(bad[0], "h", 0));
      assertEquals(KmOpamp.FAILED, c.remoteStatus);
      assertTrue(c.remoteError.contains(bad[1]), c.remoteError);
      assertEquals(SamplingDecision.RECORD_AND_SAMPLE, decision(c));
    }
  }

  @Test
  void appSettingsWinAndFullStateOnRequest() {
    KmOpamp c = client(new HashSet<String>(Arrays.asList("otel.traces.sampler")));
    c.handle(reply("{\"sampling_rate\": 0, \"send_traces\": true}", "h1", 0));
    assertEquals(SamplingDecision.RECORD_AND_SAMPLE, decision(c));
    assertEquals(Collections.singletonList("sampling_rate"), c.status.overriddenByApp);
    assertEquals("set by the application: sampling_rate", c.remoteError);
    c.fullState = false;
    c.handle(reply(null, "x", KmOpamp.REPORT_FULL_STATE));
    assertTrue(KmOpamp.Pb.decode(c.message()).containsKey(3));
  }

  @Test
  void httpResponses() throws Exception {
    byte[] plain = "HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabc".getBytes(StandardCharsets.US_ASCII);
    assertArrayEquals("abc".getBytes(StandardCharsets.US_ASCII), KmOpamp.readResponse(new ByteArrayInputStream(plain)));
    byte[] chunked = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nab\r\n1\r\nc\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    assertArrayEquals("abc".getBytes(StandardCharsets.US_ASCII), KmOpamp.readResponse(new ByteArrayInputStream(chunked)));
    byte[] error = "HTTP/1.1 503 Unavailable\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    assertThrows(java.io.IOException.class, () -> KmOpamp.readResponse(new ByteArrayInputStream(error)));
  }

  @Test
  void decoderRejectsGarbage() {
    for (byte[] bad : new byte[][] {{0x0a, 0x05, 0x61}, {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
        (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff}, {0x0f}}) {
      assertThrows(IllegalArgumentException.class, () -> KmOpamp.Pb.decode(bad));
    }
  }

  @Test
  void endpointAndInterval() {
    assertEquals("http://a/v1/opamp", KmOpamp.endpoint(Collections.<String, Object>singletonMap("agent",
        Collections.<String, Object>singletonMap("opamp_endpoint", "http://a/v1/opamp"))));
    assertEquals(1000L, KmOpamp.pollMillis("0.2"));
    assertEquals(30000L, KmOpamp.pollMillis("nope"));
    assertEquals(5000L, KmOpamp.pollMillis("5"));
  }
}
