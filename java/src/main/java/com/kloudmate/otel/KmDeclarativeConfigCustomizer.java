package com.kloudmate.otel;

import io.opentelemetry.sdk.autoconfigure.declarativeconfig.DeclarativeConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.DeclarativeConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.AttributeNameValueModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.DistributionPropertyModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.ExperimentalInstrumentationModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.ExperimentalLanguageSpecificInstrumentationModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.ExperimentalLanguageSpecificInstrumentationPropertyModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.OpenTelemetryConfigurationModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.OtlpHttpExporterModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.ResourceModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Declarative-config mode ({@code OTEL_CONFIG_FILE} / {@code otel.config.file}, file format 1.x). The file
 * km-agent generates is the source of truth; the distro only reads {@code distribution.kloudmate}, fills
 * what the file leaves unset (resource attributes, OTLP endpoint, runtime metrics) and writes the startup
 * status. It never replaces a value the file sets, so the app's {@code ${OTEL_*}} substitutions keep winning.
 */
public final class KmDeclarativeConfigCustomizer implements DeclarativeConfigurationCustomizerProvider {

  @Override
  public void customize(DeclarativeConfigurationCustomizer c) {
    c.addModelCustomizer(KmDeclarativeConfigCustomizer::apply);
  }

  static OpenTelemetryConfigurationModel apply(OpenTelemetryConfigurationModel model) {
    try {
      KmSettings s = KmSettings.fromBlock(block(model));
      KmStatus st = new KmStatus();
      st.configHash = KmStartupCheck.hash(configFile());
      st.put("OTEL_CONFIG_FILE", configFile(), "app_env");

      ResourceModel res = model.getResource();
      if (res == null) {
        res = new ResourceModel();
        model.withResource(res);
      }
      List<AttributeNameValueModel> attrs =
          res.getAttributes() == null ? new ArrayList<AttributeNameValueModel>() : new ArrayList<AttributeNameValueModel>(res.getAttributes());
      String fileId = attr(attrs, KmDistro.KM_SERVICE_ID);
      st.serviceId = fileId != null ? fileId : s.serviceId;
      if (fileId == null && s.serviceId != null) {
        attrs.add(stringAttr(KmDistro.KM_SERVICE_ID, s.serviceId));
      }
      set(attrs, KmDistro.DISTRO_NAME, KmDistro.NAME);
      set(attrs, KmDistro.DISTRO_VERSION, KmDistro.VERSION);
      res.withAttributes(attrs);

      fillEndpoints(model, s.endpoint(), st);
      runtimeMetrics(model, s.runtimeMetricsProfile, st);

      KmStartupCheck.run(st, Boolean.TRUE.equals(model.getDisabled()));
      st.write(s.statusDir);
    } catch (Throwable t) {
      KmDistro.warn("declarative config not customized, running with the file as written", t);
    }
    return model;
  }

  static String configFile() {
    String f = KmDistro.sysprop("otel.config.file");
    return KmDistro.notBlank(f) ? f : KmDistro.env("OTEL_CONFIG_FILE");
  }

  /** The {@code distribution.kloudmate} block, or an empty map. */
  static Map<String, Object> block(OpenTelemetryConfigurationModel model) {
    if (model.getDistribution() == null || model.getDistribution().getAdditionalProperties() == null) {
      return Collections.emptyMap();
    }
    DistributionPropertyModel km = model.getDistribution().getAdditionalProperties().get("kloudmate");
    return km == null || km.getAdditionalProperties() == null ? Collections.<String, Object>emptyMap() : km.getAdditionalProperties();
  }

  /** Sets the endpoint on every otlp_http exporter that has none (local-agent discovery). */
  static void fillEndpoints(OpenTelemetryConfigurationModel m, String base, KmStatus st) {
    String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    int filled = 0;
    List<OtlpHttpExporterModel> spans = new ArrayList<OtlpHttpExporterModel>();
    List<io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.OtlpHttpMetricExporterModel> metrics =
        new ArrayList<io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.OtlpHttpMetricExporterModel>();
    List<OtlpHttpExporterModel> logs = new ArrayList<OtlpHttpExporterModel>();
    if (m.getTracerProvider() != null && m.getTracerProvider().getProcessors() != null) {
      for (io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.SpanProcessorModel p : m.getTracerProvider().getProcessors()) {
        if (p.getBatch() != null && p.getBatch().getExporter() != null && p.getBatch().getExporter().getOtlpHttp() != null) {
          spans.add(p.getBatch().getExporter().getOtlpHttp());
        }
        if (p.getSimple() != null && p.getSimple().getExporter() != null && p.getSimple().getExporter().getOtlpHttp() != null) {
          spans.add(p.getSimple().getExporter().getOtlpHttp());
        }
      }
    }
    if (m.getMeterProvider() != null && m.getMeterProvider().getReaders() != null) {
      for (io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.MetricReaderModel r : m.getMeterProvider().getReaders()) {
        if (r.getPeriodic() != null && r.getPeriodic().getExporter() != null && r.getPeriodic().getExporter().getOtlpHttp() != null) {
          metrics.add(r.getPeriodic().getExporter().getOtlpHttp());
        }
      }
    }
    if (m.getLoggerProvider() != null && m.getLoggerProvider().getProcessors() != null) {
      for (io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.LogRecordProcessorModel p : m.getLoggerProvider().getProcessors()) {
        if (p.getBatch() != null && p.getBatch().getExporter() != null && p.getBatch().getExporter().getOtlpHttp() != null) {
          logs.add(p.getBatch().getExporter().getOtlpHttp());
        }
      }
    }
    filled += fill(spans, b + "/v1/traces");
    for (io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.OtlpHttpMetricExporterModel e : metrics) {
      if (!KmDistro.notBlank(e.getEndpoint())) {
        e.withEndpoint(b + "/v1/metrics");
        filled++;
      }
    }
    filled += fill(logs, b + "/v1/logs");
    st.put("OTEL_EXPORTER_OTLP_ENDPOINT", b, filled > 0 ? "distro_default" : "file");
  }

  private static int fill(List<OtlpHttpExporterModel> exporters, String endpoint) {
    int n = 0;
    for (OtlpHttpExporterModel e : exporters) {
      if (!KmDistro.notBlank(e.getEndpoint())) {
        e.withEndpoint(endpoint);
        n++;
      }
    }
    return n;
  }

  /**
   * runtime_metrics.profile: {@code off} disables the runtime-telemetry instrumentation; {@code standard} and
   * {@code extended} turn on the experimental JVM metrics ({@code emit_experimental_metrics/development}, plan 0.3). A value the file already sets under
   * {@code instrumentation/development.java.runtime_telemetry} wins.
   */
  static void runtimeMetrics(OpenTelemetryConfigurationModel m, String profile, KmStatus st) {
    ExperimentalInstrumentationModel inst = m.getInstrumentationDevelopment();
    if (inst == null) {
      inst = new ExperimentalInstrumentationModel();
      m.withInstrumentationDevelopment(inst);
    }
    ExperimentalLanguageSpecificInstrumentationModel java = inst.getJava();
    if (java == null) {
      java = new ExperimentalLanguageSpecificInstrumentationModel();
      inst.withJava(java);
    }
    ExperimentalLanguageSpecificInstrumentationPropertyModel rt = java.getAdditionalProperties().get("runtime_telemetry");
    if (rt == null) {
      rt = new ExperimentalLanguageSpecificInstrumentationPropertyModel();
      java.setAdditionalProperty("runtime_telemetry", rt);
    }
    Map<String, Object> props = rt.getAdditionalProperties();
    // Declarative key names carry the /development suffix for experimental options (agent 2.29.0).
    String key = "off".equals(profile) ? "enabled" : "emit_experimental_metrics/development";
    Object want = "off".equals(profile) ? Boolean.FALSE : Boolean.TRUE;
    if (props.containsKey(key)) {
      st.put("runtime_metrics.profile", profile, "file");
      return;
    }
    rt.setAdditionalProperty(key, want);
    st.put("runtime_metrics.profile", profile, "file");
  }

  private static String attr(List<AttributeNameValueModel> attrs, String name) {
    for (AttributeNameValueModel a : attrs) {
      if (name.equals(a.getName()) && a.getValue() != null) {
        return String.valueOf(a.getValue());
      }
    }
    return null;
  }

  private static void set(List<AttributeNameValueModel> attrs, String name, String value) {
    for (int i = attrs.size() - 1; i >= 0; i--) {
      if (name.equals(attrs.get(i).getName())) {
        attrs.remove(i);
      }
    }
    attrs.add(stringAttr(name, value));
  }

  private static AttributeNameValueModel stringAttr(String name, String value) {
    return new AttributeNameValueModel().withName(name).withValue(value).withType(AttributeNameValueModel.AttributeType.STRING);
  }

  @Override
  public int order() {
    return Integer.MAX_VALUE;
  }
}
