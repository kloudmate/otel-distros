package com.kloudmate.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.AttributeNameValueModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.DistributionModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.OpenTelemetryConfigurationModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.ResourceModel;
import io.opentelemetry.sdk.resources.Resource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KmDistroTest {
  private static final AttributeKey<String> SID = AttributeKey.stringKey("km.service_id");

  @Test
  void stampAddsServiceIdAndDistro() {
    Resource r = KmAutoConfigCustomizer.stamp(
        Resource.create(Attributes.of(AttributeKey.stringKey("telemetry.distro.name"), "opentelemetry-java-instrumentation")),
        "systemd:a.service");
    assertEquals("systemd:a.service", r.getAttribute(SID));
    assertEquals("kloudmate-java", r.getAttribute(AttributeKey.stringKey("telemetry.distro.name")));
  }

  @Test
  void stampNeverRewritesExistingServiceId() {
    Resource r = KmAutoConfigCustomizer.stamp(Resource.create(Attributes.of(SID, "app-own")), "systemd:a.service");
    assertEquals("app-own", r.getAttribute(SID));
  }

  @Test
  void settingsDefaultsAndBlock() {
    KmSettings env = KmSettings.fromEnv();
    assertEquals("http://127.0.0.1:4318", env.endpoint());
    assertEquals("standard", env.runtimeMetricsProfile);

    Map<String, Object> agent = new HashMap<String, Object>();
    agent.put("otlp_endpoint", "http://10.0.0.1:4318");
    agent.put("otlp_socket", "/run/x.sock");
    Map<String, Object> block = new HashMap<String, Object>();
    block.put("service_id", "docker:web");
    block.put("agent", agent);
    block.put("runtime_metrics", Collections.singletonMap("profile", "off"));
    block.put("unknown_future_key", Arrays.asList(1, 2));
    KmSettings s = KmSettings.fromBlock(block);
    assertEquals("docker:web", s.serviceId);
    assertEquals("http://10.0.0.1:4318", s.endpoint()); // no UDS transport in the Java exporters
    assertEquals("off", s.runtimeMetricsProfile);
  }

  @Test
  void declarativeKeepsFileServiceIdAndReadsBlock(@TempDir Path dir) throws Exception {
    Map<String, Object> km = new HashMap<String, Object>();
    km.put("service_id", "systemd:block.service");
    km.put("status_dir", dir.toString());
    OpenTelemetryConfigurationModel m = new OpenTelemetryConfigurationModel()
        .setDistribution(new DistributionModel().setExtensionProperty("kloudmate", km));
    KmDeclarativeConfigCustomizer.apply(m);
    assertEquals("systemd:block.service", value(m.getResource(), "km.service_id"));
    assertEquals("kloudmate-java", value(m.getResource(), "telemetry.distro.name"));
    assertTrue(Files.list(dir).anyMatch(p -> p.toString().endsWith(".json")), "status file");

    OpenTelemetryConfigurationModel own = new OpenTelemetryConfigurationModel()
        .setResource(new ResourceModel().setAttributes(Arrays.asList(
            new AttributeNameValueModel().setName("km.service_id").setValue("from-file"))))
        .setDistribution(new DistributionModel().setExtensionProperty("kloudmate", km));
    KmDeclarativeConfigCustomizer.apply(own);
    assertEquals("from-file", value(own.getResource(), "km.service_id"));
  }

  @Test
  void neverThrowsOnEmptyModel() {
    OpenTelemetryConfigurationModel m = KmDeclarativeConfigCustomizer.apply(new OpenTelemetryConfigurationModel());
    assertNull(m.getDisabled());
  }

  @Test
  void statusJsonEscapes() {
    KmStatus st = new KmStatus();
    st.serviceId = "a\"b";
    st.put("K", "v\n", "app_env");
    st.fail(KmStatus.DEGRADED, "foreign_agent:ddtrace");
    st.fail(KmStatus.OK, "ignored");
    String j = st.toJson(42);
    assertTrue(j.contains("\"km.service_id\":\"a\\\"b\""), j);
    assertTrue(j.contains("\"result\":\"degraded\",\"reason\":\"foreign_agent:ddtrace\""), j);
    assertTrue(j.contains("\"K\":{\"value\":\"v\\n\",\"origin\":\"app_env\"}"), j);
  }

  private static String value(ResourceModel r, String name) {
    for (AttributeNameValueModel a : r.getAttributes()) {
      if (name.equals(a.getName())) {
        return String.valueOf(a.getValue());
      }
    }
    return null;
  }
}
