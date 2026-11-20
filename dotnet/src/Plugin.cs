// KloudMate plugin for OpenTelemetry .NET automatic instrumentation 1.15.0.
// Loaded with OTEL_DOTNET_AUTO_PLUGINS="KloudMate.OpenTelemetry.Plugin, KloudMate.OpenTelemetry".
// Upstream discovers these methods by name. Every method is guarded: a failure leaves upstream behaviour.
using System;
using System.Collections.Generic;
using OpenTelemetry.Exporter;
using OpenTelemetry.Resources;

namespace KloudMate.OpenTelemetry
{
    public sealed class Plugin
    {
        private readonly Settings settings = Settings.FromEnvironment();

        /// <summary>Before the SDK is configured: the startup check and the status file.</summary>
        public void Initializing()
        {
            try
            {
                var st = new Status { ServiceId = Env.ResourceAttribute(Distro.ServiceIdKey) ?? settings.ServiceId };
                Endpoints.Report(st, settings);
                foreach (var k in new[] { "OTEL_SERVICE_NAME", "OTEL_TRACES_SAMPLER", "OTEL_TRACES_SAMPLER_ARG" })
                {
                    if (Env.IsSet(k)) st.Put(k, Env.Get(k), "app_env");
                }
                StartupCheck.Run(st);
                st.Write(settings.StatusDir);
            }
            catch (Exception e)
            {
                Distro.Warn("status not reported", e);
            }
        }

        /// <summary>km.service_id when the resource has none; telemetry.distro.* names this distribution.</summary>
        public ResourceBuilder ConfigureResource(ResourceBuilder builder)
        {
            try
            {
                var attrs = new Dictionary<string, object>
                {
                    ["telemetry.distro.name"] = Distro.Name,
                    ["telemetry.distro.version"] = Distro.Version,
                };
                if (settings.ServiceId != null && Env.ResourceAttribute(Distro.ServiceIdKey) == null)
                {
                    attrs[Distro.ServiceIdKey] = settings.ServiceId;
                }
                return builder.AddAttributes(attrs);
            }
            catch (Exception e)
            {
                Distro.Warn("resource not stamped", e);
                return builder;
            }
        }

        public void ConfigureTracesOptions(OtlpExporterOptions options) => Endpoints.Apply(options, settings, "TRACES", "/v1/traces");

        public void ConfigureMetricsOptions(OtlpExporterOptions options) => Endpoints.Apply(options, settings, "METRICS", "/v1/metrics");

        public void ConfigureLogsOptions(OtlpExporterOptions options) => Endpoints.Apply(options, settings, "LOGS", "/v1/logs");
    }
}
