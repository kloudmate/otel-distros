// Smoke app: one HttpClient call (a span), then stays up for a metric export.
var target = Environment.GetEnvironmentVariable("SMOKE_TARGET") ?? "http://127.0.0.1:4318/health";
var sleepMs = int.Parse(Environment.GetEnvironmentVariable("SMOKE_SLEEP_MS") ?? "4000");
using (var http = new HttpClient { Timeout = TimeSpan.FromSeconds(2) })
{
    try { await http.GetAsync(target); } catch { /* a span is recorded either way */ }
}
await Task.Delay(sleepMs);
Console.WriteLine("app ran");
