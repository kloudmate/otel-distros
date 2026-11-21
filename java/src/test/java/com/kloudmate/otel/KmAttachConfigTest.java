package com.kloudmate.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KmAttachConfigTest {
  @TempDir Path dir;

  private String write(String name, String text) throws Exception {
    Path p = dir.resolve(name);
    Files.write(p, text.getBytes(StandardCharsets.UTF_8));
    return p.toString();
  }

  @Test
  void readsKloudMateAndOtelKeys() throws Exception {
    String path = write("1234.properties",
        "# written by the KloudMate Agent\n"
            + "kloudmate.service_id=proc:abc\n"
            + "kloudmate.status_dir=/run/kmagent/sdk-status\n"
            + "kloudmate.agent.otlp_endpoint=http://10.0.0.1:4318\n"
            + "kloudmate.runtime_metrics.profile=off\n"
            + "otel.service.name=checkout\n"
            + "otel.resource.attributes=deployment.environment.name=prod\n"
            + "otel.empty=\n"
            + "unrelated.key=x\n");
    KmAttachConfig c = KmAttachConfig.load(path);
    assertEquals(path, c.path);
    assertEquals(Arrays.asList("otel.resource.attributes", "otel.service.name"), Arrays.asList(c.otel.keySet().toArray()));
    KmSettings s = KmSettings.fromBlock(c.block);
    assertEquals("proc:abc", s.serviceId);
    assertEquals("/run/kmagent/sdk-status", s.statusDir);
    assertEquals("http://10.0.0.1:4318", s.endpoint());
    assertEquals("off", s.runtimeMetricsProfile);
  }

  @Test
  void ignoresWhatItCannotRead() throws Exception {
    assertSame(KmAttachConfig.NONE, KmAttachConfig.load((String) null));
    assertSame(KmAttachConfig.NONE, KmAttachConfig.load("  "));
    assertSame(KmAttachConfig.NONE, KmAttachConfig.load(dir.resolve("missing.properties").toString()));
    assertSame(KmAttachConfig.NONE, KmAttachConfig.load(dir.toString()));
    File big = dir.resolve("big.properties").toFile();
    FileOutputStream out = new FileOutputStream(big);
    try {
      out.write(new byte[(int) KmAttachConfig.MAX_BYTES + 1]);
    } finally {
      out.close();
    }
    assertSame(KmAttachConfig.NONE, KmAttachConfig.load(big.getPath()));
    assertNull(KmAttachConfig.NONE.path);
  }

  @Test
  void fileOverDefaultsAppOverFile() throws Exception {
    KmAttachConfig c = KmAttachConfig.load(write("p.properties", "otel.exporter.otlp.protocol=grpc\n"));
    Map<String, String> supplied = KmAutoConfigCustomizer.defaults(KmSettings.fromBlock(c.block));
    supplied.putAll(c.otel);
    assertEquals("grpc", supplied.get("otel.exporter.otlp.protocol"));
    assertEquals("tracecontext,baggage", supplied.get("otel.propagators"));
  }
}
