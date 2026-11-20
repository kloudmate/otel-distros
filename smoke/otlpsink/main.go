// otlpsink is the local OTLP/HTTP sink the distro smoke tests export to. It accepts
// /v1/{traces,metrics,logs} (protobuf or JSON), and appends one JSON line per request to
// -out: the signal, the listener it came in on, and each resource's attributes.
//
//	otlpsink -listen 127.0.0.1:4318 -listen unix:/tmp/otlp.sock -out /tmp/sink.jsonl
package main

import (
	"encoding/json"
	"flag"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"

	collogs "go.opentelemetry.io/proto/otlp/collector/logs/v1"
	colmetrics "go.opentelemetry.io/proto/otlp/collector/metrics/v1"
	coltrace "go.opentelemetry.io/proto/otlp/collector/trace/v1"
	commonpb "go.opentelemetry.io/proto/otlp/common/v1"
	resourcepb "go.opentelemetry.io/proto/otlp/resource/v1"
	"google.golang.org/protobuf/encoding/protojson"
	"google.golang.org/protobuf/proto"
)

type listeners []string

func (l *listeners) String() string     { return strings.Join(*l, ",") }
func (l *listeners) Set(v string) error { *l = append(*l, v); return nil }

// Record is one line of -out.
type Record struct {
	Signal    string              `json:"signal"`
	Listener  string              `json:"listener"`
	Resources []map[string]string `json:"resources"`
	Items     int                 `json:"items"` // spans, metric data points' metrics, or log records
	Names     []string            `json:"names,omitempty"`
}

func main() {
	var ls listeners
	flag.Var(&ls, "listen", "host:port or unix:/path (repeatable)")
	out := flag.String("out", "sink.jsonl", "JSON lines output")
	flag.Parse()
	if len(ls) == 0 {
		ls = listeners{"127.0.0.1:4318"}
	}
	f, err := os.OpenFile(*out, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		log.Fatal(err)
	}
	var mu sync.Mutex
	enc := json.NewEncoder(f)
	for _, l := range ls {
		network, addr := "tcp", l
		if p, ok := strings.CutPrefix(l, "unix:"); ok {
			network, addr = "unix", p
			_ = os.Remove(p)
		}
		ln, err := net.Listen(network, addr)
		if err != nil {
			log.Fatal(err)
		}
		mux := http.NewServeMux()
		for _, sig := range []string{"traces", "metrics", "logs"} {
			mux.HandleFunc("/v1/"+sig, func(w http.ResponseWriter, r *http.Request) {
				rec, err := decode(sig, r)
				if err != nil {
					http.Error(w, err.Error(), http.StatusBadRequest)
					return
				}
				rec.Listener = l
				mu.Lock()
				_ = enc.Encode(rec)
				mu.Unlock()
				ct := r.Header.Get("Content-Type")
				if ct == "" {
					ct = "application/x-protobuf"
				}
				w.Header().Set("Content-Type", ct)
				w.WriteHeader(http.StatusOK)
			})
		}
		log.Printf("otlpsink listening on %s", l)
		go func() { log.Fatal(http.Serve(ln, mux)) }()
	}
	select {}
}

func decode(sig string, r *http.Request) (Record, error) {
	body, err := io.ReadAll(r.Body)
	if err != nil {
		return Record{}, err
	}
	unmarshal := proto.Unmarshal
	if strings.HasPrefix(r.Header.Get("Content-Type"), "application/json") {
		unmarshal = func(b []byte, m proto.Message) error { return protojson.Unmarshal(b, m) }
	}
	rec := Record{Signal: sig}
	switch sig {
	case "traces":
		var m coltrace.ExportTraceServiceRequest
		if err := unmarshal(body, &m); err != nil {
			return rec, err
		}
		for _, rs := range m.ResourceSpans {
			rec.Resources = append(rec.Resources, attrs(rs.Resource))
			for _, ss := range rs.ScopeSpans {
				for _, s := range ss.Spans {
					rec.Items++
					rec.Names = append(rec.Names, s.Name)
				}
			}
		}
	case "metrics":
		var m colmetrics.ExportMetricsServiceRequest
		if err := unmarshal(body, &m); err != nil {
			return rec, err
		}
		for _, rm := range m.ResourceMetrics {
			rec.Resources = append(rec.Resources, attrs(rm.Resource))
			for _, sm := range rm.ScopeMetrics {
				for _, mt := range sm.Metrics {
					rec.Items++
					rec.Names = append(rec.Names, mt.Name)
				}
			}
		}
	case "logs":
		var m collogs.ExportLogsServiceRequest
		if err := unmarshal(body, &m); err != nil {
			return rec, err
		}
		for _, rl := range m.ResourceLogs {
			rec.Resources = append(rec.Resources, attrs(rl.Resource))
			for _, sl := range rl.ScopeLogs {
				rec.Items += len(sl.LogRecords)
			}
		}
	}
	return rec, nil
}

func attrs(r *resourcepb.Resource) map[string]string {
	out := map[string]string{}
	if r == nil {
		return out
	}
	for _, kv := range r.Attributes {
		out[kv.Key] = str(kv.Value)
	}
	return out
}

func str(v *commonpb.AnyValue) string {
	switch x := v.GetValue().(type) {
	case *commonpb.AnyValue_StringValue:
		return x.StringValue
	default:
		b, _ := protojson.Marshal(v)
		return string(b)
	}
}
