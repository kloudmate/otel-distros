// KloudMate plugin for OpenTelemetry .NET automatic instrumentation 1.19.1.
// Loaded with OTEL_DOTNET_AUTO_PLUGINS="KloudMate.OpenTelemetry.Plugin, KloudMate.OpenTelemetry".
// Implements the plugin API (IPlugin and its extension interfaces). Every method is guarded: a failure leaves
// upstream behaviour.
using System;
using System.Collections.Generic;
using OpenTelemetry.AutoInstrumentation.PluginApi;
using OpenTelemetry.AutoInstrumentation.PluginApi.Telemetry;
using OpenTelemetry.Exporter;
using OpenTelemetry.Metrics;
using OpenTelemetry.Resources;
using OpenTelemetry.Trace;

namespace KloudMate.OpenTelemetry
{
    public sealed class Plugin : IPlugin, ITelemetryPlugin,
        IConfigureTracesOptions<OtlpExporterOptions>,
        IConfigureMetricsOptions<OtlpExporterOptions>,
        IConfigureLogsOptions<OtlpExporterOptions>
    {
        private readonly Settings settings = Settings.Load();

        /// <summary>Before the SDK is configured: the startup check and the status file.</summary>
        public void Initializing()
        {
            try
            {
                var st = new Status
                {
                    ServiceId = Env.ResourceAttribute(Distro.ServiceIdKey) ?? settings.ServiceId,
                    ConfigHash = settings.ConfigHash,
                };
                settings.Report(st);
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

        public void Initialized()
        {
        }

        public TracerProviderBuilder BeforeConfigureTracerProvider(TracerProviderBuilder builder) => builder;

        public TracerProviderBuilder AfterConfigureTracerProvider(TracerProviderBuilder builder) => builder;

        public void TracerProviderInitialized(TracerProvider tracerProvider)
        {
        }

        public MeterProviderBuilder BeforeConfigureMeterProvider(MeterProviderBuilder builder) => builder;

        public MeterProviderBuilder AfterConfigureMeterProvider(MeterProviderBuilder builder) => builder;

        public void MeterProviderInitialized(MeterProvider meterProvider)
        {
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
