using System;
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
    /// KloudMate settings from the environment. The distribution.kloudmate block of a config file is not read
    /// yet: upstream's file-based configuration for .NET is experimental and partial.
    /// </summary>
    internal sealed class Settings
    {
        internal string? ServiceId { get; private set; }
        internal string StatusDir { get; private set; } = Distro.DefaultStatusDir;
        internal string Endpoint { get; private set; } = Distro.DefaultEndpoint;

        internal static Settings FromEnvironment() => new Settings
        {
            ServiceId = Env.Get("KM_SERVICE_ID"),
            StatusDir = Env.Get("KM_SDK_STATUS_DIR") ?? Distro.DefaultStatusDir,
        };
    }
}
