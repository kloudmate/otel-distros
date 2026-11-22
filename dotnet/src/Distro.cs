using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;

namespace KloudMate.OpenTelemetry
{
    internal static class Distro
    {
        internal const string Name = "kloudmate-dotnet";
        internal const string ServiceIdKey = "km.service_id";
        internal const string DefaultEndpoint = "http://127.0.0.1:4318";
        internal const string DefaultStatusDir = "/run/kmagent/sdk-status";

        internal static readonly string Version =
            typeof(Distro).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion?.Split('+')[0]
            ?? "0.0.0";

        private static bool warned;

        /// <summary>One line on stderr, once. Never throws.</summary>
        internal static void Warn(string message, Exception? e)
        {
            if (warned) return;
            warned = true;
            try
            {
                Console.Error.WriteLine("[" + Name + "] " + message + (e == null ? string.Empty : ": " + e.GetType().Name + ": " + e.Message));
            }
            catch
            {
                // nothing left to do
            }
        }
    }

    internal static class Env
    {
        internal static string? Get(string name)
        {
            try
            {
                var v = Environment.GetEnvironmentVariable(name);
                return string.IsNullOrWhiteSpace(v) ? null : v!.Trim();
            }
            catch
            {
                return null;
            }
        }

        internal static bool IsSet(string name) => Get(name) != null;

        /// <summary>A key from OTEL_RESOURCE_ATTRIBUTES, if the app set it there.</summary>
        internal static string? ResourceAttribute(string key)
        {
            var all = Get("OTEL_RESOURCE_ATTRIBUTES");
            if (all == null) return null;
            foreach (var item in all.Split(','))
            {
                var i = item.IndexOf('=');
                if (i > 0 && item.Substring(0, i).Trim() == key)
                {
                    return Uri.UnescapeDataString(item.Substring(i + 1).Trim());
                }
            }
            return null;
        }
    }

    /// <summary>
    /// KloudMate settings: the environment (KM_SERVICE_ID, KM_SDK_STATUS_DIR), then, when OTEL_CONFIG_FILE points at a
    /// file, its distribution.kloudmate block, which takes precedence (specification §1). The block is read by the
    /// plugin itself, so it works whether or not upstream's experimental file-based configuration is on.
    /// </summary>
    internal sealed class Settings
    {
        internal string? ServiceId { get; private set; }
        internal string StatusDir { get; private set; } = Distro.DefaultStatusDir;
        internal string Endpoint { get; private set; } = Distro.DefaultEndpoint;
        internal string? Socket { get; private set; }
        internal string? OpampEndpoint { get; private set; }
        internal string RuntimeMetricsProfile { get; private set; } = "standard";
        internal string? ConfigFile { get; private set; }
        internal string ConfigHash { get; private set; } = string.Empty;
        internal bool FromFile { get; private set; }
        private string? fileText;
        private string? socketPath;
        private bool socketChecked;

        /// <summary>Upstream's own file-based configuration is on: the file names the exporters.</summary>
        internal bool UpstreamFileMode =>
            ConfigFile != null && string.Equals(Env.Get("OTEL_EXPERIMENTAL_FILE_BASED_CONFIGURATION_ENABLED"), "true", StringComparison.OrdinalIgnoreCase);

        /// <summary>
        /// The agent's socket path when it is used: a path is configured (agent.otlp_socket, else KM_OTLP_SOCKET), a
        /// socket there accepts connections, and the app chose no OTLP endpoint or protocol itself. Checked once.
        /// </summary>
        internal string? SocketPath()
        {
            if (!socketChecked)
            {
                socketPath = !AgentSocket.TransportSetByApp() && AgentSocket.Accepts(Socket) ? Socket : null;
                socketChecked = true;
            }
            return socketPath;
        }

        internal static Settings Load()
        {
            var s = new Settings
            {
                ServiceId = Env.Get("KM_SERVICE_ID"),
                StatusDir = Env.Get("KM_SDK_STATUS_DIR") ?? Distro.DefaultStatusDir,
                Socket = Env.Get("KM_OTLP_SOCKET"),
                OpampEndpoint = Env.Get("KM_OPAMP_ENDPOINT"),
            };
            try
            {
                var file = Env.Get("OTEL_CONFIG_FILE");
                if (file != null && File.Exists(file) && new FileInfo(file).Length <= 1024 * 1024)
                {
                    s.ConfigFile = file;
                    s.fileText = File.ReadAllText(file);
                    s.ConfigHash = Hash(s.fileText);
                    s.Apply(MiniYaml.Block(s.fileText, "distribution", "kloudmate"));
                }
            }
            catch (Exception e)
            {
                Distro.Warn("OTEL_CONFIG_FILE not read, using the environment", e);
            }
            return s;
        }

        /// <summary>Settings from a distribution.kloudmate block; unknown keys are ignored.</summary>
        internal void Apply(Dictionary<string, object>? block)
        {
            if (block == null) return;
            FromFile = true;
            if (block.TryGetValue("service_id", out var id) && id is string sid && sid.Trim().Length > 0) ServiceId = sid.Trim();
            if (block.TryGetValue("status_dir", out var dir) && dir is string sd && sd.Trim().Length > 0) StatusDir = sd.Trim();
            if (block.TryGetValue("agent", out var a) && a is Dictionary<string, object> agent)
            {
                if (agent.TryGetValue("otlp_endpoint", out var ep) && ep is string e && e.Trim().Length > 0) Endpoint = e.Trim().TrimEnd('/');
                if (agent.TryGetValue("otlp_socket", out var so) && so is string sock && sock.Trim().Length > 0) Socket = sock.Trim();
                if (agent.TryGetValue("opamp_endpoint", out var op) && op is string opamp && opamp.Trim().Length > 0) OpampEndpoint = opamp.Trim();
            }
            if (block.TryGetValue("runtime_metrics", out var r) && r is Dictionary<string, object> rm
                && rm.TryGetValue("profile", out var p) && p is string profile
                && (profile == "standard" || profile == "extended" || profile == "off"))
            {
                RuntimeMetricsProfile = profile;
            }
        }

        /// <summary>The file and the settings it supplied, and the file's substitutions the app filled itself.</summary>
        internal void Report(Status st)
        {
            if (ConfigFile == null) return;
            st.Put("OTEL_CONFIG_FILE", ConfigFile, "app_env");
            if (FromFile) st.Put("runtime_metrics.profile", RuntimeMetricsProfile, "file");
            foreach (var name in Status.AppSubstitutions(fileText))
            {
                st.Put(name, Env.Get(name), "app_env");
                st.Overridden(name);
            }
        }

        private static string Hash(string text)
        {
            using var sha = System.Security.Cryptography.SHA256.Create();
            var d = sha.ComputeHash(System.Text.Encoding.UTF8.GetBytes(text));
            var b = new System.Text.StringBuilder("sha256:");
            foreach (var x in d) b.Append(x.ToString("x2", System.Globalization.CultureInfo.InvariantCulture));
            return b.ToString();
        }
    }
}
