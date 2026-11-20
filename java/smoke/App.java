import java.net.HttpURLConnection;

/** Smoke app: one HTTP client call (a span from the upstream agent), then stays up for a metric export. */
public class App {
  public static void main(String[] args) throws Exception {
    String target = System.getenv().getOrDefault("SMOKE_TARGET", "http://127.0.0.1:4318/health");
    try {
      HttpURLConnection c = (HttpURLConnection) java.net.URI.create(target).toURL().openConnection();
      c.setConnectTimeout(2000);
      c.getResponseCode();
      c.disconnect();
    } catch (Exception ignored) {
      // a span is recorded either way
    }
    Thread.sleep(Long.parseLong(System.getenv().getOrDefault("SMOKE_SLEEP_MS", "4000")));
  }
}
