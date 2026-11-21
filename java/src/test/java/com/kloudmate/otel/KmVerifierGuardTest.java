package com.kloudmate.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.DistributionModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.DistributionPropertyModel;
import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.OpenTelemetryConfigurationModel;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KmVerifierGuardTest {
  private static String[] argv(String... a) {
    return a;
  }

  @Test
  void detectsEveryWayToTurnVerificationOff() {
    assertTrue(KmStartupCheck.verificationOff(null, null, argv("java", "-Xverify:none", "-jar", "app.jar"), null));
    assertTrue(KmStartupCheck.verificationOff(null, null, argv("java", "-noverify", "Main"), null));
    assertTrue(KmStartupCheck.verificationOff(null, null, argv("java", "-XX:-BytecodeVerificationRemote", "Main"), null));
    assertTrue(KmStartupCheck.verificationOff("-Xmx1g -Xverify:none", null, argv("java", "Main"), null));
    assertTrue(KmStartupCheck.verificationOff(null, "-noverify", argv("java", "Main"), null));
    assertTrue(KmStartupCheck.verificationOff(null, null, argv("java", "Main"), "  -Xverify:none  "));
    // SAP Commerce: the Tanuki wrapper's JVM, options before the wrapper's main class.
    assertTrue(KmStartupCheck.verificationOff(null, null, argv("/usr/java/bin/java", "-Xms22G", "-da",
        "-Xverify:none", "-cp", "../lib/wrapper.jar", "org.tanukisoftware.wrapper.WrapperSimpleApp",
        "org.apache.catalina.startup.Bootstrap", "start"), null));
  }

  @Test
  void lastSettingWinsInTheJvmsOrder() {
    assertFalse(KmStartupCheck.verificationOff("-Xverify:none", null, argv("java", "-Xverify:remote", "Main"), null));
    assertTrue(KmStartupCheck.verificationOff(null, null, argv("java", "-Xverify:all", "Main"), "-noverify"));
    assertFalse(KmStartupCheck.verificationOff(null, null, argv("java", "-noverify", "-XX:+BytecodeVerificationRemote", "Main"), null));
  }

  @Test
  void ignoresApplicationArguments() {
    assertFalse(KmStartupCheck.verificationOff(null, null, argv("java", "-jar", "app.jar", "-noverify"), null));
    assertFalse(KmStartupCheck.verificationOff(null, null, argv("java", "Main", "-Xverify:none"), null));
    assertFalse(KmStartupCheck.verificationOff(null, null, argv("java", "-m", "app/app.Main", "-noverify"), null));
    // a -cp value is not the main class, so the scan goes on past it
    assertTrue(KmStartupCheck.verificationOff(null, null, argv("java", "-cp", "a.jar", "-noverify", "Main"), null));
    assertFalse(KmStartupCheck.verificationOff(null, null, null, null));
    assertEquals(Arrays.asList("-cp", "-Xmx1g"), KmStartupCheck.jvmOptions(argv("java", "-cp", "x", "-Xmx1g", "Main", "-z")));
  }

  @Test
  void configFileGetsInstrumentationOffUnlessItSaysOtherwise() {
    OpenTelemetryConfigurationModel empty = new OpenTelemetryConfigurationModel();
    KmVerifierGuard.disableByDefault(empty);
    assertFalse(KmVerifierGuard.instrumentationOnByDefault(empty));

    OpenTelemetryConfigurationModel own = model(true);
    KmVerifierGuard.disableByDefault(own);
    assertTrue(KmVerifierGuard.instrumentationOnByDefault(own)); // the file's value stays
    assertTrue(KmVerifierGuard.instrumentationOnByDefault(new OpenTelemetryConfigurationModel()));
  }

  @Test
  void keepsTheFilesOtherInstrumentationSettings() {
    OpenTelemetryConfigurationModel m = model(null);
    KmVerifierGuard.disableByDefault(m);
    Map<?, ?> i = (Map<?, ?>) m.getDistribution().getAdditionalProperties().get("javaagent").getAdditionalProperties().get("instrumentation");
    assertEquals(Boolean.FALSE, i.get("default_enabled"));
    assertEquals(Arrays.asList("jdbc"), i.get("enabled"));
  }

  @Test
  void statusIsDeniedOrDegraded() {
    KmStatus denied = new KmStatus();
    denied.fail(KmStatus.DENIED, KmStartupCheck.VERIFICATION_OFF);
    assertEquals("denied", denied.result);
    denied.fail(KmStatus.DISABLED, "sdk_disabled");
    assertEquals("denied", denied.result); // denied outranks disabled
    denied.fail(KmStatus.ERROR, "config_parse_error");
    assertEquals("error", denied.result);
  }

  private static OpenTelemetryConfigurationModel model(Boolean defaultEnabled) {
    Map<String, Object> instrumentation = new LinkedHashMap<String, Object>();
    if (defaultEnabled != null) {
      instrumentation.put("default_enabled", defaultEnabled);
    }
    instrumentation.put("enabled", Arrays.asList("jdbc"));
    DistributionPropertyModel agent = new DistributionPropertyModel().withAdditionalProperty("instrumentation", instrumentation);
    return new OpenTelemetryConfigurationModel().withDistribution(new DistributionModel().withAdditionalProperty("javaagent", agent));
  }
}
