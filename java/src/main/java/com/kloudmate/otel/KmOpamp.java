package com.kloudmate.otel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.net.ssl.SSLSocketFactory;

/**
 * OpAMP client (contract §4): reports health and the startup status to the local KloudMate Agent over OpAMP's
 * plain-HTTP transport, and applies the remote settings that can change live ({@code sampling_rate},
 * {@code send_traces}) through {@link KmRemoteSampler}. Off unless an OpAMP endpoint is configured. It speaks
 * HTTP/1.1 over a plain socket, which the agent doesn't instrument, so its own requests are never traced; the
 * handful of OpAMP messages it needs are encoded by hand. Never throws into the app.
 */
final class KmOpamp {
  static final long CAPABILITIES = 0x1 | 0x2 | 0x4 | 0x800 | 0x1000;
  static final int APPLIED = 1;
  static final int FAILED = 3;
  static final long REPORT_FULL_STATE = 0x1;
  static final int DEFAULT_POLL_SECONDS = 30;
  static final List<String> LIVE = Arrays.asList("sampling_rate", "send_traces");
  static final List<String> NOT_LIVE = Arrays.asList("logging_level", "send_metrics", "send_logs",
      "deactivate_instrumentations", "deactivate_all_instrumentations", "metrics_profile");

  interface Transport {
    byte[] post(String url, byte[] body) throws IOException;
  }

  /** The app's own setting for each live key, as a property: the remote value gives way to it. */
  interface AppSettings {
    boolean setByApp(String property);
  }

  private static volatile KmOpamp instance;

  final String endpoint;
  final KmStatus status;
  final String statusDir;
  final long intervalMillis;
  final Transport transport;
  final AppSettings app;
  final byte[] uid = uuid7();
  final long startNanos = System.currentTimeMillis() * 1_000_000L;
  volatile String serviceName = "unknown_service:java";
  volatile KmRemoteSampler sampler;
  /** Env mode installs the sampler while the SDK is built: live keys wait for it rather than being refused. */
  final boolean samplerExpected;
  long seq;
  boolean fullState = true;
  byte[] remoteHash = new byte[0];
  int remoteStatus;
  String remoteError = "";
  Double liveRate;
  boolean liveSend = true;

  KmOpamp(String endpoint, KmStatus status, String statusDir, long intervalMillis, Transport transport,
      AppSettings app, boolean samplerExpected) {
    this.endpoint = endpoint;
    this.status = status;
    this.statusDir = statusDir;
    this.intervalMillis = intervalMillis;
    this.transport = transport;
    this.app = app;
    this.samplerExpected = samplerExpected;
  }

  // --- start-up -------------------------------------------------------------------------------------------------

  /** Starts the client once per JVM when an endpoint is configured. */
  static synchronized KmOpamp start(
      String endpoint, KmStatus status, String statusDir, String serviceName, boolean samplerExpected) {
    if (!KmDistro.notBlank(endpoint) || instance != null || status == null) {
      return instance;
    }
    try {
      KmOpamp c = new KmOpamp(endpoint.trim(), status, statusDir, pollMillis(KmDistro.env("KM_OPAMP_POLL_INTERVAL")),
          KmOpamp::post, KmDistro::setByApp, samplerExpected);
      if (KmDistro.notBlank(serviceName)) {
        c.serviceName = serviceName;
      }
      instance = c;
      Thread t = new Thread(c::run, "kloudmate-opamp");
      t.setDaemon(true);
      t.start();
      return c;
    } catch (Throwable t) {
      KmDistro.warn("OpAMP client not started", t);
      return null;
    }
  }

  static KmOpamp instance() {
    return instance;
  }

  /** The configured OpAMP endpoint: {@code agent.opamp_endpoint} of a block or attach file, else the env. */
  static String endpoint(Map<String, Object> block) {
    String e = KmSettings.str(KmSettings.map(block == null ? null : block.get("agent")).get("opamp_endpoint"));
    return e != null ? e : KmDistro.env("KM_OPAMP_ENDPOINT");
  }

  static long pollMillis(String seconds) {
    try {
      double s = Double.parseDouble(seconds);
      return s > 0 ? (long) (Math.max(1.0, s) * 1000) : DEFAULT_POLL_SECONDS * 1000L;
    } catch (Throwable t) {
      return DEFAULT_POLL_SECONDS * 1000L;
    }
  }

  /** Wraps the SDK's sampler (env mode); live settings already received apply to it at once. */
  static KmRemoteSampler wrap(io.opentelemetry.sdk.trace.samplers.Sampler configured) {
    KmRemoteSampler s = new KmRemoteSampler(configured);
    KmOpamp c = instance;
    if (c != null) {
      synchronized (c) {
        c.sampler = s;
        s.set(c.liveRate, c.liveSend);
      }
    }
    return s;
  }

  // --- the loop --------------------------------------------------------------------------------------------------

  void run() {
    java.util.Random jitter = new java.util.Random();
    while (true) {
      try {
        pollOnce();
      } catch (Throwable t) {
        synchronized (this) {
          fullState = true; // the agent may not be up yet; try again next time
        }
      }
      try {
        Thread.sleep((long) (intervalMillis * (0.9 + jitter.nextDouble() * 0.2)));
      } catch (InterruptedException e) {
        return;
      }
    }
  }

  void pollOnce() throws IOException {
    byte[] msg;
    synchronized (this) {
      msg = message();
    }
    byte[] reply = transport.post(endpoint, msg);
    synchronized (this) {
      seq++;
      fullState = false;
      handle(reply);
    }
  }

  // --- messages --------------------------------------------------------------------------------------------------

  synchronized byte[] message() {
    KmStatus st = status;
    boolean healthy = KmStatus.OK.equals(st.result) || KmStatus.DEGRADED.equals(st.result);
    Pb health = new Pb().bool(1, healthy).fixed64(2, startNanos)
        .str(3, KmStatus.OK.equals(st.result) ? "" : st.reason).str(4, st.result);
    Pb m = new Pb().bytes(1, uid).uint(2, seq);
    if (fullState) {
      Pb desc = new Pb();
      desc.msg(1, kv("service.name", serviceName));
      desc.msg(1, kv("service.instance.id", uuidString(uid)));
      if (KmDistro.notBlank(st.serviceId)) {
        desc.msg(1, kv("km.service_id", st.serviceId));
      }
      desc.msg(2, kv("telemetry.distro.name", KmDistro.NAME));
      desc.msg(2, kv("telemetry.distro.version", KmDistro.VERSION));
      desc.msg(2, kvInt("process.pid", KmDistro.pid()));
      desc.msg(2, kv("os.type", "linux"));
      m.msg(3, desc);
    }
    m.uint(4, CAPABILITIES).msg(5, health);
    byte[] json = st.toJson(KmDistro.pid()).trim().getBytes(StandardCharsets.UTF_8);
    m.msg(6, new Pb().msg(1, configMap("kloudmate.status", json, "application/json")));
    if (remoteHash.length > 0) {
      m.msg(7, new Pb().bytes(1, remoteHash).uint(2, remoteStatus).str(3, remoteError));
    }
    return m.toByteArray();
  }

  synchronized void handle(byte[] reply) {
    Map<Integer, List<Object>> f = Pb.decode(reply);
    if ((Pb.lastLong(f, 6) & REPORT_FULL_STATE) != 0) {
      fullState = true;
    }
    for (Object rc : Pb.all(f, 3)) {
      Map<Integer, List<Object>> rcf = Pb.decode((byte[]) rc);
      byte[] h = Pb.lastBytes(rcf, 2);
      if (h.length == 0 || Arrays.equals(h, remoteHash)) {
        continue;
      }
      byte[] body = null;
      for (Object cm : Pb.all(rcf, 1)) {
        for (Object entry : Pb.all(Pb.decode((byte[]) cm), 1)) {
          Map<Integer, List<Object>> e = Pb.decode((byte[]) entry);
          if ("kloudmate".equals(new String(Pb.lastBytes(e, 1), StandardCharsets.UTF_8))) {
            body = Pb.lastBytes(Pb.decode(Pb.lastBytes(e, 2)), 1);
          }
        }
      }
      apply(h, body);
    }
  }

  synchronized void apply(byte[] hash, byte[] body) {
    remoteHash = hash;
    Map<String, Object> remote;
    try {
      remote = parseRemote(body);
    } catch (IllegalArgumentException e) {
      remoteStatus = FAILED;
      remoteError = e.getMessage();
      return;
    }
    List<String> overridden = new ArrayList<String>();
    if (remote.containsKey("sampling_rate")
        && (app.setByApp("otel.traces.sampler") || app.setByApp("otel.traces.sampler.arg"))) {
      overridden.add("sampling_rate");
    }
    if (remote.containsKey("send_traces") && app.setByApp("otel.traces.exporter")) {
      overridden.add("send_traces");
    }
    List<String> notLive = new ArrayList<String>();
    Map<String, Object> live = new LinkedHashMap<String, Object>();
    for (Map.Entry<String, Object> e : remote.entrySet()) {
      if (NOT_LIVE.contains(e.getKey())) {
        notLive.add(e.getKey());
      } else if (LIVE.contains(e.getKey()) && !overridden.contains(e.getKey())) {
        live.put(e.getKey(), e.getValue());
      }
    }
    if (sampler == null && !samplerExpected && !live.isEmpty()) {
      notLive.addAll(live.keySet());
      live.clear();
    }
    Collections.sort(notLive);
    liveRate = live.containsKey("sampling_rate") ? ((BigDecimal) live.get("sampling_rate")).doubleValue() : null;
    liveSend = !Boolean.FALSE.equals(live.get("send_traces"));
    KmRemoteSampler s = sampler;
    if (s != null) {
      s.set(liveRate, liveSend);
    }
    for (String k : LIVE) {
      status.effective.remove(k);
    }
    for (Map.Entry<String, Object> e : live.entrySet()) {
      Object v = e.getValue();
      status.put(e.getKey(), v instanceof BigDecimal ? ((BigDecimal) v).stripTrailingZeros().toPlainString() : String.valueOf(v), "remote");
    }
    for (String k : overridden) {
      status.overridden(k);
    }
    List<String> notes = new ArrayList<String>();
    if (!overridden.isEmpty()) {
      notes.add("set by the application: " + join(overridden));
    }
    if (!notLive.isEmpty()) {
      notes.add("not applied live: " + join(notLive));
    }
    remoteStatus = APPLIED;
    remoteError = join(notes, "; ");
    status.write(statusDir);
  }

  /** The remote settings, validated: the known keys only, numbers as BigDecimal. */
  static Map<String, Object> parseRemote(byte[] body) {
    String text = body == null ? "" : new String(body, StandardCharsets.UTF_8).trim();
    if (text.isEmpty()) {
      return Collections.emptyMap();
    }
    Object parsed;
    try {
      parsed = new Json(text).parse();
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("remote config is not a JSON object");
    }
    if (!(parsed instanceof Map)) {
      throw new IllegalArgumentException("remote config is not a JSON object");
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) parsed;
    if (data.containsKey("sampling_rate")) {
      Object r = data.get("sampling_rate");
      if (!(r instanceof BigDecimal) || ((BigDecimal) r).signum() < 0 || ((BigDecimal) r).compareTo(BigDecimal.ONE) > 0) {
        throw new IllegalArgumentException("sampling_rate: a number from 0 to 1");
      }
    }
    if (data.containsKey("send_traces") && !(data.get("send_traces") instanceof Boolean)) {
      throw new IllegalArgumentException("send_traces: true or false");
    }
    Map<String, Object> out = new TreeMap<String, Object>();
    for (Map.Entry<String, Object> e : data.entrySet()) {
      if (LIVE.contains(e.getKey()) || NOT_LIVE.contains(e.getKey())) {
        out.put(e.getKey(), e.getValue());
      }
    }
    return out;
  }

  // --- helpers ---------------------------------------------------------------------------------------------------

  static Pb kv(String key, String value) {
    return new Pb().str(1, key).msg(2, new Pb().str(1, value));
  }

  static Pb kvInt(String key, long value) {
    return new Pb().str(1, key).msg(2, new Pb().tag(3, 0).varint(value));
  }

  /** AgentConfigMap with one entry: map&lt;string, AgentConfigFile{body, content_type}&gt;. */
  static Pb configMap(String key, byte[] body, String contentType) {
    return new Pb().msg(1, new Pb().str(1, key).msg(2, new Pb().bytes(1, body).str(2, contentType)));
  }

  static byte[] uuid7() {
    byte[] b = new byte[16];
    new SecureRandom().nextBytes(b);
    long ms = System.currentTimeMillis();
    for (int i = 0; i < 6; i++) {
      b[i] = (byte) (ms >>> (8 * (5 - i)));
    }
    b[6] = (byte) (0x70 | (b[6] & 0x0F));
    b[8] = (byte) (0x80 | (b[8] & 0x3F));
    return b;
  }

  static String uuidString(byte[] b) {
    StringBuilder h = new StringBuilder(36);
    for (int i = 0; i < b.length; i++) {
      if (i == 4 || i == 6 || i == 8 || i == 10) {
        h.append('-');
      }
      h.append(Character.forDigit((b[i] >> 4) & 0xF, 16)).append(Character.forDigit(b[i] & 0xF, 16));
    }
    return h.toString();
  }

  private static String join(List<String> items) {
    return join(items, ", ");
  }

  private static String join(List<String> items, String sep) {
    StringBuilder b = new StringBuilder();
    for (String s : items) {
      if (b.length() > 0) {
        b.append(sep);
      }
      b.append(s);
    }
    return b.toString();
  }

  /** POST over HTTP/1.1 on a plain (or TLS) socket. */
  static byte[] post(String url, byte[] body) throws IOException {
    URI u = URI.create(url);
    boolean tls = "https".equalsIgnoreCase(u.getScheme());
    if (!tls && !"http".equalsIgnoreCase(u.getScheme())) {
      throw new IOException("unsupported scheme " + u.getScheme());
    }
    int port = u.getPort() > 0 ? u.getPort() : (tls ? 443 : 80);
    String path = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath())
        + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
    Socket s = tls ? SSLSocketFactory.getDefault().createSocket() : new Socket();
    try {
      s.connect(new InetSocketAddress(u.getHost(), port), 5000);
      s.setSoTimeout(10000);
      OutputStream out = s.getOutputStream();
      String head = "POST " + path + " HTTP/1.1\r\nHost: " + u.getHost() + ":" + port
          + "\r\nContent-Type: application/x-protobuf\r\nContent-Length: " + body.length
          + "\r\nConnection: close\r\n\r\n";
      out.write(head.getBytes(StandardCharsets.US_ASCII));
      out.write(body);
      out.flush();
      return readResponse(s.getInputStream());
    } finally {
      s.close();
    }
  }

  static byte[] readResponse(InputStream in) throws IOException {
    String status = line(in);
    String[] parts = status.split(" ", 3);
    if (parts.length < 2 || !parts[1].startsWith("2")) {
      throw new IOException("OpAMP server answered " + status);
    }
    long length = -1;
    boolean chunked = false;
    for (String h = line(in); !h.isEmpty(); h = line(in)) {
      int c = h.indexOf(':');
      if (c < 0) {
        continue;
      }
      String name = h.substring(0, c).trim();
      String value = h.substring(c + 1).trim();
      if (name.equalsIgnoreCase("Content-Length")) {
        length = Long.parseLong(value);
      } else if (name.equalsIgnoreCase("Transfer-Encoding") && value.toLowerCase(java.util.Locale.ROOT).contains("chunked")) {
        chunked = true;
      }
    }
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    if (chunked) {
      for (long n = Long.parseLong(line(in).split(";")[0].trim(), 16); n > 0; n = Long.parseLong(line(in).split(";")[0].trim(), 16)) {
        copy(in, body, n);
        line(in);
      }
    } else if (length >= 0) {
      copy(in, body, length);
    } else {
      copy(in, body, Long.MAX_VALUE);
    }
    return body.toByteArray();
  }

  private static void copy(InputStream in, ByteArrayOutputStream out, long n) throws IOException {
    if (n > 4 * 1024 * 1024 && n != Long.MAX_VALUE) {
      throw new IOException("OpAMP response too large");
    }
    byte[] buf = new byte[8192];
    while (n > 0) {
      int r = in.read(buf, 0, (int) Math.min(buf.length, n));
      if (r < 0) {
        if (n == Long.MAX_VALUE) {
          return;
        }
        throw new IOException("truncated OpAMP response");
      }
      out.write(buf, 0, r);
      n -= r;
      if (out.size() > 4 * 1024 * 1024) {
        throw new IOException("OpAMP response too large");
      }
    }
  }

  private static String line(InputStream in) throws IOException {
    StringBuilder b = new StringBuilder();
    for (int c = in.read(); c != '\n'; c = in.read()) {
      if (c < 0) {
        throw new IOException("truncated OpAMP response");
      }
      if (c != '\r') {
        b.append((char) c);
      }
      if (b.length() > 8192) {
        throw new IOException("OpAMP response header too long");
      }
    }
    return b.toString();
  }

  // --- a minimal protobuf writer and reader ------------------------------------------------------------------------

  static final class Pb {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    Pb varint(long v) {
      while ((v & ~0x7FL) != 0) {
        out.write((int) ((v & 0x7F) | 0x80));
        v >>>= 7;
      }
      out.write((int) v);
      return this;
    }

    Pb tag(int num, int wire) {
      return varint(((long) num << 3) | wire);
    }

    Pb uint(int num, long v) {
      return v == 0 ? this : tag(num, 0).varint(v);
    }

    Pb bool(int num, boolean v) {
      return v ? tag(num, 0).varint(1) : this;
    }

    Pb fixed64(int num, long v) {
      if (v == 0) {
        return this;
      }
      tag(num, 1);
      for (int i = 0; i < 8; i++) {
        out.write((int) (v >>> (8 * i)) & 0xFF);
      }
      return this;
    }

    Pb bytes(int num, byte[] v) {
      tag(num, 2).varint(v.length);
      out.write(v, 0, v.length);
      return this;
    }

    Pb str(int num, String v) {
      return v == null || v.isEmpty() ? this : bytes(num, v.getBytes(StandardCharsets.UTF_8));
    }

    Pb msg(int num, Pb m) {
      return bytes(num, m.toByteArray());
    }

    byte[] toByteArray() {
      return out.toByteArray();
    }

    /** Fields by number: Long for varint/fixed, byte[] for length-delimited. Throws on malformed input. */
    static Map<Integer, List<Object>> decode(byte[] b) {
      Map<Integer, List<Object>> f = new LinkedHashMap<Integer, List<Object>>();
      int[] i = {0};
      while (i[0] < b.length) {
        long key = readVarint(b, i);
        int num = (int) (key >>> 3);
        int wire = (int) (key & 7);
        Object v;
        if (wire == 0) {
          v = readVarint(b, i);
        } else if (wire == 1 || wire == 5) {
          int n = wire == 1 ? 8 : 4;
          if (i[0] + n > b.length) {
            throw new IllegalArgumentException("truncated field");
          }
          long x = 0;
          for (int k = n - 1; k >= 0; k--) {
            x = (x << 8) | (b[i[0] + k] & 0xFF);
          }
          i[0] += n;
          v = x;
        } else if (wire == 2) {
          long n = readVarint(b, i);
          if (n < 0 || i[0] + n > b.length) {
            throw new IllegalArgumentException("truncated field");
          }
          v = Arrays.copyOfRange(b, i[0], i[0] + (int) n);
          i[0] += (int) n;
        } else {
          throw new IllegalArgumentException("unsupported wire type " + wire);
        }
        List<Object> l = f.get(num);
        if (l == null) {
          l = new ArrayList<Object>();
          f.put(num, l);
        }
        l.add(v);
      }
      return f;
    }

    private static long readVarint(byte[] b, int[] i) {
      long n = 0;
      for (int shift = 0; ; shift += 7) {
        if (i[0] >= b.length || shift > 63) {
          throw new IllegalArgumentException("bad varint");
        }
        int x = b[i[0]++] & 0xFF;
        n |= (long) (x & 0x7F) << shift;
        if ((x & 0x80) == 0) {
          return n;
        }
      }
    }

    static List<Object> all(Map<Integer, List<Object>> f, int num) {
      List<Object> l = f.get(num);
      return l == null ? Collections.emptyList() : l;
    }

    static long lastLong(Map<Integer, List<Object>> f, int num) {
      List<Object> l = all(f, num);
      return l.isEmpty() || !(l.get(l.size() - 1) instanceof Long) ? 0 : (Long) l.get(l.size() - 1);
    }

    static byte[] lastBytes(Map<Integer, List<Object>> f, int num) {
      List<Object> l = all(f, num);
      return l.isEmpty() || !(l.get(l.size() - 1) instanceof byte[]) ? new byte[0] : (byte[]) l.get(l.size() - 1);
    }
  }

  // --- a minimal JSON reader: objects, arrays, strings, numbers (BigDecimal), booleans, null ------------------------

  static final class Json {
    private final String s;
    private int i;

    Json(String s) {
      this.s = s;
    }

    Object parse() {
      Object v = value();
      ws();
      if (i != s.length()) {
        throw new IllegalArgumentException("trailing data");
      }
      return v;
    }

    private void ws() {
      while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
        i++;
      }
    }

    private Object value() {
      ws();
      if (i >= s.length()) {
        throw new IllegalArgumentException("unexpected end");
      }
      char c = s.charAt(i);
      if (c == '{') {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        i++;
        ws();
        if (peek('}')) {
          return m;
        }
        do {
          ws();
          String k = string();
          ws();
          expect(':');
          m.put(k, value());
          ws();
        } while (peek(','));
        expect('}');
        return m;
      }
      if (c == '[') {
        List<Object> l = new ArrayList<Object>();
        i++;
        ws();
        if (peek(']')) {
          return l;
        }
        do {
          l.add(value());
          ws();
        } while (peek(','));
        expect(']');
        return l;
      }
      if (c == '"') {
        return string();
      }
      if (s.startsWith("true", i)) {
        i += 4;
        return Boolean.TRUE;
      }
      if (s.startsWith("false", i)) {
        i += 5;
        return Boolean.FALSE;
      }
      if (s.startsWith("null", i)) {
        i += 4;
        return null;
      }
      int start = i;
      while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
        i++;
      }
      return new BigDecimal(s.substring(start, i));
    }

    private boolean peek(char c) {
      if (i < s.length() && s.charAt(i) == c) {
        i++;
        return true;
      }
      return false;
    }

    private void expect(char c) {
      if (!peek(c)) {
        throw new IllegalArgumentException("expected " + c);
      }
    }

    private String string() {
      expect('"');
      StringBuilder b = new StringBuilder();
      while (true) {
        char c = s.charAt(i++);
        if (c == '"') {
          return b.toString();
        }
        if (c == '\\') {
          char e = s.charAt(i++);
          switch (e) {
            case 'n': b.append('\n'); break;
            case 't': b.append('\t'); break;
            case 'r': b.append('\r'); break;
            case 'b': b.append('\b'); break;
            case 'f': b.append('\f'); break;
            case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
            default: b.append(e);
          }
        } else {
          b.append(c);
        }
      }
    }
  }
}
