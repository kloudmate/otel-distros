using System;
using System.IO;
using System.Net.Http;
using System.Net.Sockets;

namespace KloudMate.OpenTelemetry
{
    /// <summary>
    /// The KloudMate Agent's OTLP Unix socket, which speaks OTLP/gRPC. The exporter reaches it through an HttpClient
    /// whose connections are opened on the socket; the endpoint's host only names the HTTP/2 authority.
    /// </summary>
    internal static class AgentSocket
    {
        /// <summary>The endpoint set on the exporter: any authority works, the connection goes to the socket.</summary>
        internal static readonly Uri Endpoint = new Uri("http://localhost:4317");

        private static readonly string[] TransportKeys =
        {
            "OTEL_EXPORTER_OTLP_ENDPOINT", "OTEL_EXPORTER_OTLP_PROTOCOL",
            "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "OTEL_EXPORTER_OTLP_TRACES_PROTOCOL",
            "OTEL_EXPORTER_OTLP_METRICS_ENDPOINT", "OTEL_EXPORTER_OTLP_METRICS_PROTOCOL",
            "OTEL_EXPORTER_OTLP_LOGS_ENDPOINT", "OTEL_EXPORTER_OTLP_LOGS_PROTOCOL",
        };

        /// <summary>True when the app set an OTLP endpoint or protocol itself: the socket is then left unused.</summary>
        internal static bool TransportSetByApp()
        {
            foreach (var k in TransportKeys)
            {
                if (Env.IsSet(k)) return true;
            }
            return false;
        }

        /// <summary>
        /// True when path is absolute and a socket there accepts a connection. .NET can't tell a socket from a file
        /// by its metadata, so this connects once and closes; a gRPC server ignores a connection that sends nothing.
        /// </summary>
        internal static bool Accepts(string? path)
        {
            if (path == null || !Path.IsPathRooted(path) || !File.Exists(path)) return false;
            try
            {
                using var s = new Socket(AddressFamily.Unix, SocketType.Stream, ProtocolType.Unspecified);
                s.Connect(new UnixDomainSocketEndPoint(path));
                return true;
            }
            catch
            {
                return false;
            }
        }

        /// <summary>An HttpClient whose connections go to the socket at path.</summary>
        internal static HttpClient Client(string path, int timeoutMilliseconds)
        {
            var handler = new SocketsHttpHandler
            {
                ConnectCallback = async (context, cancellationToken) =>
                {
                    var socket = new Socket(AddressFamily.Unix, SocketType.Stream, ProtocolType.Unspecified);
                    try
                    {
                        await socket.ConnectAsync(new UnixDomainSocketEndPoint(path), cancellationToken).ConfigureAwait(false);
                        return new NetworkStream(socket, ownsSocket: true);
                    }
                    catch
                    {
                        socket.Dispose();
                        throw;
                    }
                },
            };
            return new HttpClient(handler) { Timeout = TimeSpan.FromMilliseconds(timeoutMilliseconds) };
        }
    }
}
