package com.kloudmate.otel;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.resources.Resource;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Env/system-property mode (no {@code otel.config.file}). Adds the KloudMate defaults as the lowest-priority
 * property source, so anything the app sets itself wins (ADR-0002), stamps the resource, and writes the
 * startup status. Every step is guarded: on failure the agent runs with upstream defaults.
 */
public final class KmAutoConfigCustomizer implements AutoConfigurationCustomizerProvider {
  static final String DEFAULT_ENABLED = "otel.instrumentation.common.default-enabled";

  /** KloudMate defaults over upstream 2.34.0 (README "What it adds"). */
  static Map<String, String> defaults(KmSettings s) {
    Map<String, String> d = new LinkedHashMap<String, String>();
    d.put("otel.exporter.otlp.endpoint", s.endpoint());
    d.put("otel.exporter.otlp.protocol", "http/protobuf");
    d.put("otel.propagators", "tracecontext,baggage");
    if ("off".equals(s.runtimeMetricsProfile)) {
      d.put("otel.instrumentation.runtime-telemetry.enabled", "false");
    } else {
      d.put("otel.instrumentation.runtime-telemetry.emit-experimental-metrics", "true");
    }
    return d;
  }

  @Override
  public void customize(AutoConfigurationCustomizer c) {
    final KmSettings settings;
    final KmAttachConfig attach;
    final Map<String, String> supplied;
    try {
      // The file the agent arguments name (kloudmate-javaagent.jar=<file>): how a runtime attach is configured.
      attach = KmAttachConfig.load();
      settings = attach.path != null ? KmSettings.fromBlock(attach.block) : KmSettings.fromEnv();
      supplied = defaults(settings);
      if (KmStartupCheck.bytecodeVerificationOff()) {
        // No instrumentation by default in a JVM that never verifies the classes the agent rewrites. Lowest
        // priority like every default here: an app that sets it to true itself keeps its instrumentation.
        supplied.put(DEFAULT_ENABLED, "false");
      }
      // The file's otel.* properties over the KloudMate defaults, still under the app's own settings.
      supplied.putAll(attach.otel);
    } catch (Throwable t) {
      KmDistro.warn("disabled, running with upstream defaults", t);
      return;
    }
    c.addPropertiesSupplier(() -> supplied);
    // The startup status, for the OpAMP client (contract §4), which starts once the resource is known.
    final java.util.concurrent.atomic.AtomicReference<KmStatus> written = new java.util.concurrent.atomic.AtomicReference<KmStatus>();
    final String opampEndpoint = KmOpamp.endpoint(attach.path != null ? attach.block : null);
    c.addPropertiesCustomizer(
        config -> {
          try {
            KmStatus st = new KmStatus();
            st.serviceId = settings.serviceId;
            if (attach.path != null) {
              st.configHash = KmStartupCheck.hash(attach.path);
              st.put(KmAttachConfig.PROPERTY, attach.path, "file");
            }
            for (String k : supplied.keySet()) {
              boolean app = KmDistro.setByApp(k);
              String origin = app ? "app_env" : attach.otel.containsKey(k) ? "file" : "distro_default";
              st.put(KmDistro.envName(k), config.getString(k), origin);
              if (app) {
                st.overridden(KmDistro.envName(k));
              }
            }
            for (String k : new String[] {"otel.service.name", "otel.traces.sampler", "otel.traces.sampler.arg"}) {
              if (KmDistro.setByApp(k)) {
                st.put(KmDistro.envName(k), config.getString(k), "app_env");
              }
            }
            KmStartupCheck.run(st, config.getBoolean("otel.sdk.disabled", false), config.getBoolean(DEFAULT_ENABLED, true));
            st.write(settings.statusDir);
            written.set(st);
          } catch (Throwable t) {
            KmDistro.warn("status not reported", t);
          }
          return Collections.<String, String>emptyMap();
        });
    c.addResourceCustomizer(
        (resource, config) -> {
          Resource r = stamp(resource, settings.serviceId);
          if (KmDistro.notBlank(opampEndpoint)) {
            KmOpamp.start(opampEndpoint, written.get(), settings.statusDir,
                r.getAttribute(AttributeKey.stringKey("service.name")), true);
          }
          return r;
        });
    // Live remote settings (sampling_rate, send_traces) go through a sampler around the configured one.
    c.addSamplerCustomizer((sampler, config) -> KmOpamp.instance() == null ? sampler : KmOpamp.wrap(sampler));
  }

  /**
   * Adds km.service_id when the resource doesn't carry one (contract §2: never rewritten), and sets
   * telemetry.distro.* to this distro, replacing upstream's opentelemetry-java-instrumentation values.
   */
  static Resource stamp(Resource resource, String serviceId) {
    try {
      AttributesBuilder b = Attributes.builder()
          .put(AttributeKey.stringKey(KmDistro.DISTRO_NAME), KmDistro.NAME)
          .put(AttributeKey.stringKey(KmDistro.DISTRO_VERSION), KmDistro.VERSION);
      AttributeKey<String> sid = AttributeKey.stringKey(KmDistro.KM_SERVICE_ID);
      if (serviceId != null && resource.getAttribute(sid) == null) {
        b.put(sid, serviceId);
      }
      return resource.merge(Resource.create(b.build()));
    } catch (Throwable t) {
      KmDistro.warn("resource not stamped", t);
      return resource;
    }
  }

  @Override
  public int order() {
    return Integer.MAX_VALUE; // after upstream's customizers, so telemetry.distro.* is ours
  }

}
