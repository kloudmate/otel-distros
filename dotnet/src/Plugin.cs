// KloudMate plugin for OpenTelemetry .NET automatic instrumentation 1.19.1.
// Loaded with OTEL_DOTNET_AUTO_PLUGINS="KloudMate.OpenTelemetry.Plugin, KloudMate.OpenTelemetry".
// Implements the plugin API (IPlugin and its extension interfaces). Every method is guarded: a failure leaves
// upstream behaviour.
using System;
using System.Collections.Generic;
using OpenTelemetry;
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
        private Status? status;
        private RemoteSampler? remoteSampler;
        private string? serviceName;

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
                status = st;
            }
            catch (Exception e)
            {
                Distro.Warn("status not reported", e);
            }
        }

        /// <summary>After the SDK is configured: the OpAMP client, when an endpoint is configured.</summary>
        public void Initialized()
        {
            try
            {
                if (settings.OpampEndpoint == null || status == null) return;
                var name = serviceName ?? Env.Get("OTEL_SERVICE_NAME") ?? "unknown_service:dotnet";
                new Opamp(settings.OpampEndpoint, status, settings.StatusDir, remoteSampler, name,
                    Opamp.PollSeconds(Env.Get("KM_OPAMP_POLL_INTERVAL"))).Start();
            }
            catch (Exception e)
            {
                Distro.Warn("OpAMP client not started", e);
            }
        }

        public TracerProviderBuilder BeforeConfigureTracerProvider(TracerProviderBuilder builder) => builder;

        /// <summary>
        /// With OpAMP on, live remote settings (sampling_rate, send_traces) go through a sampler that otherwise behaves
        /// as upstream's default (parentbased_always_on). Not when the app or upstream's file mode chooses the
        /// sampler: those keys are then reported as not applied live.
        /// </summary>
        public TracerProviderBuilder AfterConfigureTracerProvider(TracerProviderBuilder builder)
        {
            try
            {
                if (settings.OpampEndpoint != null && !Env.IsSet("OTEL_TRACES_SAMPLER") && !settings.UpstreamFileMode)
                {
                    remoteSampler = new RemoteSampler(new ParentBasedSampler(new AlwaysOnSampler()));
                    return builder.SetSampler(remoteSampler);
                }
            }
            catch (Exception e)
            {
                Distro.Warn("remote sampler not installed", e);
            }
            return builder;
        }

        public void TracerProviderInitialized(TracerProvider tracerProvider)
        {
            try
            {
                foreach (var a in tracerProvider.GetResource().Attributes)
                {
                    if (a.Key == "service.name" && a.Value is string n && n.Length > 0) serviceName = n;
                }
            }
            catch
            {
                // the service name falls back to OTEL_SERVICE_NAME
            }
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
