using System;
using OpenTelemetry.Exporter;

namespace KloudMate.OpenTelemetry
{
    /// <summary>
    /// Local-agent endpoint and protocol, applied only where the app set nothing itself: the agent's socket (OTLP/gRPC)
    /// when it is used (Settings.SocketPath), else TCP 127.0.0.1:4318 (not localhost, which resolves to ::1 first on
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
                // Env mode moves the exporter to the socket; in upstream's file mode only an otlp_grpc exporter
                // without an endpoint takes it, as the file names the protocol.
                var sock = s.SocketPath();
                if (sock != null && !SetElsewhere(options)
                    && (!s.UpstreamFileMode || options.Protocol == OtlpExportProtocol.Grpc))
                {
                    options.Protocol = OtlpExportProtocol.Grpc;
                    options.Endpoint = AgentSocket.Endpoint;
                    options.HttpClientFactory = () => AgentSocket.Client(sock, options.TimeoutMilliseconds);
                    return;
                }
                if (!ProtocolSetByApp(signal) && !s.UpstreamFileMode)
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
            var sock = s.UpstreamFileMode ? null : s.SocketPath();
            if (sock != null)
            {
                st.Put("OTEL_EXPORTER_OTLP_ENDPOINT", "unix://" + sock, "distro_default");
                st.Put("OTEL_EXPORTER_OTLP_PROTOCOL", "grpc", "distro_default");
                return;
            }
            var ep = Env.Get("OTEL_EXPORTER_OTLP_ENDPOINT");
            st.Put("OTEL_EXPORTER_OTLP_ENDPOINT", ep ?? s.Endpoint, ep != null ? "app_env" : "distro_default");
            if (ep != null) st.Overridden("OTEL_EXPORTER_OTLP_ENDPOINT");
            var proto = Env.Get("OTEL_EXPORTER_OTLP_PROTOCOL");
            st.Put("OTEL_EXPORTER_OTLP_PROTOCOL", proto ?? "http/protobuf", proto != null ? "app_env" : "distro_default");
            if (proto != null) st.Overridden("OTEL_EXPORTER_OTLP_PROTOCOL");
        }
    }
}
