using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Text;

namespace KloudMate.OpenTelemetry
{
    /// <summary>The startup status (specification §5): &lt;status_dir&gt;/&lt;pid&gt;.json, only into an existing directory.</summary>
    internal sealed class Status
    {
        private static readonly Dictionary<string, int> Order = new Dictionary<string, int>
        {
            ["ok"] = 0, ["degraded"] = 1, ["unsupported_runtime"] = 2, ["denied"] = 2, ["disabled"] = 2, ["error"] = 3,
        };

        private readonly List<KeyValuePair<string, string[]>> effective = new List<KeyValuePair<string, string[]>>();
        private readonly List<string> overriddenByApp = new List<string>();

        internal string? ServiceId { get; set; }
        internal string ConfigHash { get; set; } = string.Empty;
        internal string Result { get; private set; } = "ok";
        internal string Reason { get; private set; } = string.Empty;

        internal void Put(string key, string? value, string origin)
        {
            if (value != null) effective.Add(new KeyValuePair<string, string[]>(key, new[] { value, origin }));
        }

        /// <summary>Removes every value recorded for key.</summary>
        internal void Remove(string key) => effective.RemoveAll(e => e.Key == key);

        /// <summary>A KloudMate-managed setting the app replaced with its own value (sorted, no duplicates).</summary>
        internal void Overridden(string key)
        {
            if (!overriddenByApp.Contains(key))
            {
                overriddenByApp.Add(key);
                overriddenByApp.Sort(StringComparer.Ordinal);
            }
        }

        internal IReadOnlyList<string> OverriddenByApp => overriddenByApp;

        private static readonly System.Text.RegularExpressions.Regex Substitution =
            new System.Text.RegularExpressions.Regex(@"\$\{(?:env:)?(OTEL_[A-Z0-9_]+)(?::-[^}]*)?\}");

        /// <summary>
        /// The ${OTEL_*} substitutions of a config file the app supplies itself (set in its environment), in file
        /// order: the file's KloudMate values the app replaced.
        /// </summary>
        internal static List<string> AppSubstitutions(string? text)
        {
            var list = new List<string>();
            if (text == null) return list;
            foreach (System.Text.RegularExpressions.Match m in Substitution.Matches(text))
            {
                var name = m.Groups[1].Value;
                if (name != "OTEL_CONFIG_FILE" && Env.IsSet(name) && !list.Contains(name)) list.Add(name);
            }
            return list;
        }

        internal void Fail(string result, string reason)
        {
            if (Rank(result) > Rank(Result))
            {
                Result = result;
                Reason = reason;
            }
        }

        private static int Rank(string r) => Order.TryGetValue(r, out var n) ? n : 3;

        internal string? Write(string dir)
        {
            try
            {
                if (!Directory.Exists(dir)) return null;
                var pid = Process.GetCurrentProcess().Id;
                var path = Path.Combine(dir, pid.ToString(CultureInfo.InvariantCulture) + ".json");
                var tmp = Path.Combine(dir, "." + pid.ToString(CultureInfo.InvariantCulture) + ".json.tmp");
                File.WriteAllText(tmp, ToJson(pid), new UTF8Encoding(false));
                if (File.Exists(path)) File.Delete(path);
                File.Move(tmp, path);
                return path;
            }
            catch (Exception e)
            {
                Distro.Warn("status file not written", e);
                return null;
            }
        }

        internal string ToJson(int pid)
        {
            var now = DateTime.UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ", CultureInfo.InvariantCulture);
            var b = new StringBuilder(512);
            b.Append("{\"schema_version\":1,\"pid\":").Append(pid.ToString(CultureInfo.InvariantCulture));
            b.Append(",\"km.service_id\":").Append(Q(ServiceId ?? string.Empty));
            b.Append(",\"distro\":{\"name\":").Append(Q(Distro.Name)).Append(",\"version\":").Append(Q(Distro.Version)).Append('}');
            b.Append(",\"result\":").Append(Q(Result)).Append(",\"reason\":").Append(Q(Reason));
            b.Append(",\"started_at\":").Append(Q(now)).Append(",\"heartbeat_at\":").Append(Q(now));
            b.Append(",\"config_hash\":").Append(Q(ConfigHash)).Append(",\"effective_config\":{");
            for (var i = 0; i < effective.Count; i++)
            {
                if (i > 0) b.Append(',');
                b.Append(Q(effective[i].Key)).Append(":{\"value\":").Append(Q(effective[i].Value[0]))
                 .Append(",\"origin\":").Append(Q(effective[i].Value[1])).Append('}');
            }
            b.Append("},\"overridden_by_app\":[");
            for (var i = 0; i < overriddenByApp.Count; i++)
            {
                if (i > 0) b.Append(',');
                b.Append(Q(overriddenByApp[i]));
            }
            b.Append("],\"instrumentations\":[]");
            b.Append(",\"runtime\":{\"name\":\"dotnet\",\"version\":").Append(Q(Environment.Version.ToString())).Append("}}\n");
            return b.ToString();
        }

        internal static string Q(string s)
        {
            var b = new StringBuilder(s.Length + 2).Append('"');
            foreach (var c in s)
            {
                switch (c)
                {
                    case '"': b.Append("\\\""); break;
                    case '\\': b.Append("\\\\"); break;
                    case '\n': b.Append("\\n"); break;
                    case '\r': b.Append("\\r"); break;
                    case '\t': b.Append("\\t"); break;
                    default:
                        if (c < 0x20) b.Append("\\u").Append(((int)c).ToString("x4", CultureInfo.InvariantCulture));
                        else b.Append(c);
                        break;
                }
            }
            return b.Append('"').ToString();
        }
    }

    internal static class StartupCheck
    {
        /// <summary>Records why the distribution may not do its job, without ever stopping the app.</summary>
        internal static void Run(Status st)
        {
            try
            {
                if (string.Equals(Env.Get("OTEL_SDK_DISABLED"), "true", StringComparison.OrdinalIgnoreCase))
                {
                    st.Fail("disabled", "sdk_disabled");
                }
                if (Env.IsSet("DD_TRACE_ENABLED") || Env.IsSet("DD_DOTNET_TRACER_HOME")) st.Fail("degraded", "foreign_agent:ddtrace");
                if (Env.IsSet("NEW_RELIC_LICENSE_KEY") || Env.IsSet("NEWRELIC_HOME")) st.Fail("degraded", "foreign_agent:newrelic");
                if (Env.IsSet("ELASTIC_APM_SERVER_URL")) st.Fail("degraded", "foreign_agent:elastic");
            }
            catch (Exception e)
            {
                Distro.Warn("startup check failed", e);
            }
        }
    }
}
