using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using OpenTelemetry;
using OpenTelemetry.Trace;

namespace KloudMate.OpenTelemetry
{
    /// <summary>
    /// The configured sampler until a remote setting (specification §4) says otherwise: send_traces false drops every
    /// span, and a sampling_rate samples parent-based at that ratio.
    /// </summary>
    internal sealed class RemoteSampler : Sampler
    {
        private readonly Sampler @delegate;
        private volatile Sampler? @override;

        internal RemoteSampler(Sampler configured)
        {
            @delegate = configured;
            Description = "KloudMateRemote{" + configured.Description + "}";
        }

        internal void Set(double? rate, bool send)
        {
            if (!send) @override = new AlwaysOffSampler();
            else if (rate.HasValue) @override = new ParentBasedSampler(new TraceIdRatioBasedSampler(rate.Value));
            else @override = null;
        }

        public override SamplingResult ShouldSample(in SamplingParameters samplingParameters) =>
            (@override ?? @delegate).ShouldSample(samplingParameters);
    }

    /// <summary>
    /// OpAMP client (specification §4): reports health and the startup status to the local KloudMate Agent over
    /// OpAMP's plain-HTTP transport, and applies the remote settings that can change live through
    /// <see cref="RemoteSampler"/>. Off unless an OpAMP endpoint is configured. Its own requests run with
    /// instrumentation suppressed; the handful of OpAMP messages it needs are encoded by hand. Never throws into the
    /// app.
    /// </summary>
    internal sealed class Opamp
    {
        internal const ulong Capabilities = 0x1 | 0x2 | 0x4 | 0x800 | 0x1000;
        internal const int Applied = 1;
        internal const int Failed = 3;
        internal const ulong ReportFullState = 0x1;
        internal const double DefaultPollSeconds = 30;
        internal static readonly string[] Live = { "sampling_rate", "send_traces" };
        internal static readonly string[] NotLive =
        {
            "logging_level", "send_metrics", "send_logs", "deactivate_instrumentations", "deactivate_all_instrumentations",
            "metrics_profile",
        };

        private static readonly HttpClient Http = new HttpClient { Timeout = TimeSpan.FromSeconds(10) };
        private readonly object sync = new object();
        private readonly string endpoint;
        private readonly Status status;
        private readonly string statusDir;
        private readonly Func<string, byte[], Task<byte[]>> post;
        private readonly Func<string, bool> setByApp;
        private readonly ulong startNanos = (ulong)DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() * 1_000_000UL;
        private Timer? timer;

        internal Opamp(string endpoint, Status status, string statusDir, RemoteSampler? sampler, string serviceName,
            double intervalSeconds, Func<string, byte[], Task<byte[]>>? post = null, Func<string, bool>? setByApp = null)
        {
            this.endpoint = endpoint;
            this.status = status;
            this.statusDir = statusDir;
            Sampler = sampler;
            ServiceName = serviceName;
            IntervalSeconds = intervalSeconds;
            this.post = post ?? Post;
            this.setByApp = setByApp ?? Env.IsSet;
            Uid = Uuid7();
        }

        internal byte[] Uid { get; }
        internal RemoteSampler? Sampler { get; }
        internal string ServiceName { get; }
        internal double IntervalSeconds { get; }
        internal ulong Seq { get; private set; }
        internal bool FullState { get; set; } = true;
        internal byte[] RemoteHash { get; set; } = Array.Empty<byte>();
        internal int RemoteStatus { get; private set; }
        internal string RemoteError { get; private set; } = string.Empty;

        internal static double PollSeconds(string? value) =>
            double.TryParse(value, NumberStyles.Float, CultureInfo.InvariantCulture, out var s) && s > 0 ? Math.Max(1, s) : DefaultPollSeconds;

        internal void Start()
        {
            var jitter = new Random();
            timer = new Timer(async _ =>
            {
                try
                {
                    await PollOnce().ConfigureAwait(false);
                }
                catch
                {
                    lock (sync) FullState = true; // the agent may not be up yet; try again next time
                }
                try
                {
                    timer?.Change(TimeSpan.FromSeconds(IntervalSeconds * (0.9 + jitter.NextDouble() * 0.2)), Timeout.InfiniteTimeSpan);
                }
                catch (ObjectDisposedException)
                {
                }
            }, null, TimeSpan.Zero, Timeout.InfiniteTimeSpan);
        }

        internal async Task PollOnce()
        {
            byte[] msg;
            lock (sync) msg = Message();
            var reply = await post(endpoint, msg).ConfigureAwait(false);
            lock (sync)
            {
                Seq++;
                FullState = false;
                Handle(reply);
            }
        }

        internal byte[] Message()
        {
            lock (sync)
            {
                var st = status;
                var healthy = st.Result == "ok" || st.Result == "degraded";
                var health = new Pb().Bool(1, healthy).Fixed64(2, startNanos).Str(3, st.Result == "ok" ? string.Empty : st.Reason).Str(4, st.Result);
                var m = new Pb().Bytes(1, Uid).UInt(2, Seq);
                if (FullState)
                {
                    var desc = new Pb();
                    desc.Msg(1, Kv("service.name", ServiceName));
                    desc.Msg(1, Kv("service.instance.id", UuidString(Uid)));
                    if (!string.IsNullOrEmpty(st.ServiceId)) desc.Msg(1, Kv("km.service_id", st.ServiceId!));
                    desc.Msg(2, Kv("telemetry.distro.name", Distro.Name));
                    desc.Msg(2, Kv("telemetry.distro.version", Distro.Version));
                    desc.Msg(2, KvInt("process.pid", Environment.ProcessId));
                    desc.Msg(2, Kv("os.type", "linux"));
                    m.Msg(3, desc);
                }
                m.UInt(4, Capabilities).Msg(5, health);
                var json = Encoding.UTF8.GetBytes(st.ToJson(Environment.ProcessId).Trim());
                m.Msg(6, new Pb().Msg(1, ConfigMap("kloudmate.status", json, "application/json")));
                if (RemoteHash.Length > 0) m.Msg(7, new Pb().Bytes(1, RemoteHash).UInt(2, (ulong)RemoteStatus).Str(3, RemoteError));
                return m.ToArray();
            }
        }

        internal void Handle(byte[] reply)
        {
            lock (sync)
            {
                var f = Pb.Decode(reply);
                if ((Pb.LastULong(f, 6) & ReportFullState) != 0) FullState = true;
                foreach (var rc in Pb.All(f, 3))
                {
                    var rcf = Pb.Decode((byte[])rc);
                    var h = Pb.LastBytes(rcf, 2);
                    if (h.Length == 0 || h.SequenceEqual(RemoteHash)) continue;
                    byte[]? body = null;
                    foreach (var cm in Pb.All(rcf, 1))
                    {
                        foreach (var entry in Pb.All(Pb.Decode((byte[])cm), 1))
                        {
                            var e = Pb.Decode((byte[])entry);
                            if (Encoding.UTF8.GetString(Pb.LastBytes(e, 1)) == "kloudmate") body = Pb.LastBytes(Pb.Decode(Pb.LastBytes(e, 2)), 1);
                        }
                    }
                    Apply(h, body);
                }
            }
        }

        internal void Apply(byte[] hash, byte[]? body)
        {
            lock (sync)
            {
                RemoteHash = hash;
                Dictionary<string, JsonElement> remote;
                try
                {
                    remote = ParseRemote(body);
                }
                catch (FormatException e)
                {
                    RemoteStatus = Failed;
                    RemoteError = e.Message;
                    return;
                }
                var overridden = new List<string>();
                if (remote.ContainsKey("sampling_rate") && (setByApp("OTEL_TRACES_SAMPLER") || setByApp("OTEL_TRACES_SAMPLER_ARG"))) overridden.Add("sampling_rate");
                if (remote.ContainsKey("send_traces") && setByApp("OTEL_TRACES_EXPORTER")) overridden.Add("send_traces");
                var notLive = remote.Keys.Where(k => NotLive.Contains(k)).ToList();
                var live = remote.Where(kv => Live.Contains(kv.Key) && !overridden.Contains(kv.Key)).ToDictionary(kv => kv.Key, kv => kv.Value);
                if (Sampler == null && live.Count > 0)
                {
                    notLive.AddRange(live.Keys);
                    live.Clear();
                }
                notLive.Sort(StringComparer.Ordinal);
                Sampler?.Set(
                    live.TryGetValue("sampling_rate", out var r) ? r.GetDouble() : (double?)null,
                    !live.TryGetValue("send_traces", out var s) || s.GetBoolean());
                foreach (var k in Live) status.Remove(k);
                foreach (var kv in live) status.Put(kv.Key, kv.Value.GetRawText(), "remote");
                foreach (var k in overridden) status.Overridden(k);
                var notes = new List<string>();
                if (overridden.Count > 0) notes.Add("set by the application: " + string.Join(", ", overridden));
                if (notLive.Count > 0) notes.Add("not applied live: " + string.Join(", ", notLive));
                RemoteStatus = Applied;
                RemoteError = string.Join("; ", notes);
                status.Write(statusDir);
            }
        }

        /// <summary>The remote settings, validated: the known keys only. Throws FormatException for the server.</summary>
        internal static Dictionary<string, JsonElement> ParseRemote(byte[]? body)
        {
            var text = body == null ? string.Empty : Encoding.UTF8.GetString(body).Trim();
            var result = new Dictionary<string, JsonElement>(StringComparer.Ordinal);
            if (text.Length == 0) return result;
            JsonElement root;
            try
            {
                using var doc = JsonDocument.Parse(text);
                root = doc.RootElement.Clone();
            }
            catch (JsonException)
            {
                throw new FormatException("remote config is not a JSON object");
            }
            if (root.ValueKind != JsonValueKind.Object) throw new FormatException("remote config is not a JSON object");
            foreach (var p in root.EnumerateObject())
            {
                if (p.Name == "sampling_rate" && (p.Value.ValueKind != JsonValueKind.Number || p.Value.GetDouble() < 0 || p.Value.GetDouble() > 1))
                {
                    throw new FormatException("sampling_rate: a number from 0 to 1");
                }
                if (p.Name == "send_traces" && p.Value.ValueKind != JsonValueKind.True && p.Value.ValueKind != JsonValueKind.False)
                {
                    throw new FormatException("send_traces: true or false");
                }
                if (Live.Contains(p.Name) || NotLive.Contains(p.Name)) result[p.Name] = p.Value;
            }
            return result;
        }

        private static async Task<byte[]> Post(string url, byte[] body)
        {
            using var scope = SuppressInstrumentationScope.Begin();
            using var content = new ByteArrayContent(body);
            content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("application/x-protobuf");
            using var response = await Http.PostAsync(url, content).ConfigureAwait(false);
            response.EnsureSuccessStatusCode();
            return await response.Content.ReadAsByteArrayAsync().ConfigureAwait(false);
        }

        internal static Pb Kv(string key, string value) => new Pb().Str(1, key).Msg(2, new Pb().Str(1, value));

        internal static Pb KvInt(string key, long value) => new Pb().Str(1, key).Msg(2, new Pb().Tag(3, 0).Varint((ulong)value));

        /// <summary>AgentConfigMap with one entry: map&lt;string, AgentConfigFile{body, content_type}&gt;.</summary>
        internal static Pb ConfigMap(string key, byte[] body, string contentType) =>
            new Pb().Msg(1, new Pb().Str(1, key).Msg(2, new Pb().Bytes(1, body).Str(2, contentType)));

        internal static byte[] Uuid7()
        {
            var b = RandomNumberGenerator.GetBytes(16);
            var ms = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            for (var i = 0; i < 6; i++) b[i] = (byte)(ms >> (8 * (5 - i)));
            b[6] = (byte)(0x70 | (b[6] & 0x0F));
            b[8] = (byte)(0x80 | (b[8] & 0x3F));
            return b;
        }

        internal static string UuidString(byte[] b)
        {
            var h = Convert.ToHexString(b).ToLowerInvariant();
            return h.Substring(0, 8) + "-" + h.Substring(8, 4) + "-" + h.Substring(12, 4) + "-" + h.Substring(16, 4) + "-" + h.Substring(20);
        }
    }

    /// <summary>A minimal protobuf writer and reader: varint, fixed64 and length-delimited fields.</summary>
    internal sealed class Pb
    {
        private readonly MemoryStream buf = new MemoryStream();

        internal Pb Varint(ulong v)
        {
            while (v >= 0x80)
            {
                buf.WriteByte((byte)(v | 0x80));
                v >>= 7;
            }
            buf.WriteByte((byte)v);
            return this;
        }

        internal Pb Tag(int num, int wire) => Varint(((ulong)num << 3) | (uint)wire);

        internal Pb UInt(int num, ulong v) => v == 0 ? this : Tag(num, 0).Varint(v);

        internal Pb Bool(int num, bool v) => v ? Tag(num, 0).Varint(1) : this;

        internal Pb Fixed64(int num, ulong v)
        {
            if (v == 0) return this;
            Tag(num, 1);
            for (var i = 0; i < 8; i++) buf.WriteByte((byte)(v >> (8 * i)));
            return this;
        }

        internal Pb Bytes(int num, byte[] v)
        {
            Tag(num, 2).Varint((ulong)v.Length);
            buf.Write(v, 0, v.Length);
            return this;
        }

        internal Pb Str(int num, string v) => string.IsNullOrEmpty(v) ? this : Bytes(num, Encoding.UTF8.GetBytes(v));

        internal Pb Msg(int num, Pb m) => Bytes(num, m.ToArray());

        internal byte[] ToArray() => buf.ToArray();

        /// <summary>Fields by number: ulong for varint/fixed, byte[] for length-delimited. Throws on malformed input.</summary>
        internal static Dictionary<int, List<object>> Decode(byte[] b)
        {
            var f = new Dictionary<int, List<object>>();
            var i = 0;
            while (i < b.Length)
            {
                var key = ReadVarint(b, ref i);
                var num = (int)(key >> 3);
                var wire = (int)(key & 7);
                object v;
                switch (wire)
                {
                    case 0:
                        v = ReadVarint(b, ref i);
                        break;
                    case 1:
                    case 5:
                        var n = wire == 1 ? 8 : 4;
                        if (i + n > b.Length) throw new FormatException("truncated field");
                        ulong x = 0;
                        for (var k = n - 1; k >= 0; k--) x = (x << 8) | b[i + k];
                        i += n;
                        v = x;
                        break;
                    case 2:
                        var len = ReadVarint(b, ref i);
                        if (len > (ulong)(b.Length - i)) throw new FormatException("truncated field");
                        v = b.AsSpan(i, (int)len).ToArray();
                        i += (int)len;
                        break;
                    default:
                        throw new FormatException("unsupported wire type " + wire);
                }
                if (!f.TryGetValue(num, out var l)) f[num] = l = new List<object>();
                l.Add(v);
            }
            return f;
        }

        private static ulong ReadVarint(byte[] b, ref int i)
        {
            ulong n = 0;
            for (var shift = 0; ; shift += 7)
            {
                if (i >= b.Length || shift > 63) throw new FormatException("bad varint");
                var x = b[i++];
                n |= (ulong)(x & 0x7F) << shift;
                if ((x & 0x80) == 0) return n;
            }
        }

        internal static IReadOnlyList<object> All(Dictionary<int, List<object>> f, int num) =>
            f.TryGetValue(num, out var l) ? l : (IReadOnlyList<object>)Array.Empty<object>();

        internal static ulong LastULong(Dictionary<int, List<object>> f, int num) =>
            All(f, num).Count > 0 && All(f, num)[All(f, num).Count - 1] is ulong u ? u : 0;

        internal static byte[] LastBytes(Dictionary<int, List<object>> f, int num) =>
            All(f, num).Count > 0 && All(f, num)[All(f, num).Count - 1] is byte[] b ? b : Array.Empty<byte>();
    }
}
