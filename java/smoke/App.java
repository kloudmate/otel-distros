import java.net.HttpURLConnection;

/** Smoke app: one HTTP client call (a span from the upstream agent), then stays up for a metric export. */
public class App {
  public static void main(String[] args) throws Exception {
    String target = System.getenv().getOrDefault("SMOKE_TARGET", "http://127.0.0.1:4318/health");
    long sleep = Long.parseLong(System.getenv().getOrDefault("SMOKE_SLEEP_MS", "4000"));
    // SMOKE_REPEAT_MS: keep calling until the sleep is over, so spans follow an agent attached at runtime.
    long repeat = Long.parseLong(System.getenv().getOrDefault("SMOKE_REPEAT_MS", "0"));
    // SMOKE_DELAY_MS: wait before the first call, e.g. until a remote config has arrived.
    Thread.sleep(Long.parseLong(System.getenv().getOrDefault("SMOKE_DELAY_MS", "0")));
    long end = System.currentTimeMillis() + sleep;
    call(target);
    if (repeat <= 0) {
      Thread.sleep(sleep);
      return;
    }
    while (System.currentTimeMillis() < end) {
      Thread.sleep(repeat);
      call(target);
    }
  }

  private static void call(String target) {
    try {
      HttpURLConnection c = (HttpURLConnection) java.net.URI.create(target).toURL().openConnection();
      c.setConnectTimeout(2000);
      c.getResponseCode();
      c.disconnect();
    } catch (Exception ignored) {
      // a span is recorded either way
    }
  }
}
