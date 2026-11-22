// Smoke app: one HttpClient call (a span), then stays up for a metric export.
var target = Environment.GetEnvironmentVariable("SMOKE_TARGET") ?? "http://127.0.0.1:4318/health";
var sleepMs = int.Parse(Environment.GetEnvironmentVariable("SMOKE_SLEEP_MS") ?? "4000");
// SMOKE_DELAY_MS: wait before the call, e.g. until a remote config has arrived.
await Task.Delay(int.Parse(Environment.GetEnvironmentVariable("SMOKE_DELAY_MS") ?? "0"));
using (var http = new HttpClient { Timeout = TimeSpan.FromSeconds(2) })
{
    try { await http.GetAsync(target); } catch { /* a span is recorded either way */ }
}
await Task.Delay(sleepMs);
Console.WriteLine("app ran");
