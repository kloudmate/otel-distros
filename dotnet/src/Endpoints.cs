using System;
using OpenTelemetry.Exporter;

namespace KloudMate.OpenTelemetry
{
    /// <summary>
    /// Local-agent endpoint and protocol, applied only where the app set nothing itself. The .NET OTLP exporter has no
    /// Unix-socket transport, so the TCP endpoint 127.0.0.1:4318 is used (not localhost, which resolves to ::1 first on
    /// many hosts).
    /// </summary>
    internal static class Endpoints
    {
        internal static bool EndpointSetByApp(string signal) =>
            Env.IsSet("OTEL_EXPORTER_OTLP_ENDPOINT") || Env.IsSet("OTEL_EXPORTER_OTLP_" + signal + "_ENDPOINT");

        internal static bool ProtocolSetByApp(string signal) =>
            Env.IsSet("OTEL_EXPORTER_OTLP_PROTOCOL") || Env.IsSet("OTEL_EXPORTER_OTLP_" + signal + "_PROTOCOL");

        internal static void Apply(OtlpExporterOptions options, Settings s, string signal, string path)
        {
            try
            {
                if (!ProtocolSetByApp(signal))
                {
                    options.Protocol = OtlpExportProtocol.HttpProtobuf;
                }
                if (!EndpointSetByApp(signal) && options.Protocol == OtlpExportProtocol.HttpProtobuf
                    && !SetElsewhere(options))
                {
                    // Set in code, the HTTP endpoint is used as is: it must carry the signal path.
                    options.Endpoint = new Uri(s.Endpoint.TrimEnd('/') + path);
                }
            }
            catch (Exception e)
            {
                Distro.Warn("OTLP " + signal.ToLowerInvariant() + " options not applied", e);
            }
        }

        /// <summary>
        /// True when the options already carry an endpoint other than the SDK's default for their protocol: upstream's
        /// file-based configuration set it from the config file, which wins over the agent endpoint.
        /// </summary>
        internal static bool SetElsewhere(OtlpExporterOptions options)
        {
            try
            {
                var dflt = new OtlpExporterOptions { Protocol = options.Protocol }.Endpoint;
                return options.Endpoint != null && dflt != null && options.Endpoint.GetLeftPart(UriPartial.Authority) != dflt.GetLeftPart(UriPartial.Authority);
            }
            catch
            {
                return false;
            }
        }

        internal static void Report(Status st, Settings s)
        {
            var ep = Env.Get("OTEL_EXPORTER_OTLP_ENDPOINT");
            st.Put("OTEL_EXPORTER_OTLP_ENDPOINT", ep ?? s.Endpoint, ep != null ? "app_env" : "distro_default");
            if (ep != null) st.Overridden("OTEL_EXPORTER_OTLP_ENDPOINT");
            var proto = Env.Get("OTEL_EXPORTER_OTLP_PROTOCOL");
            st.Put("OTEL_EXPORTER_OTLP_PROTOCOL", proto ?? "http/protobuf", proto != null ? "app_env" : "distro_default");
            if (proto != null) st.Overridden("OTEL_EXPORTER_OTLP_PROTOCOL");
        }
    }
}
