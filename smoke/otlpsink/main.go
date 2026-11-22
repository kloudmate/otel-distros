// otlpsink is the local OTLP sink the distro smoke tests export to. It accepts OTLP/HTTP on
// /v1/{traces,metrics,logs} (protobuf or JSON) and OTLP/gRPC, and appends one JSON line per request to
// -out: the signal, the listener it came in on, and each resource's attributes.
//
//	otlpsink -listen 127.0.0.1:4318 -listen unix:/tmp/otlp.sock -listen grpc+unix:/tmp/grpc.sock -out /tmp/sink.jsonl
//
// A listener is host:port or unix:/path for OTLP/HTTP; prefix it with grpc+ (grpc+127.0.0.1:4317,
// grpc+unix:/path) for OTLP/gRPC, the protocol the KloudMate Agent's socket speaks.
package main

import (
	"context"
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
	"google.golang.org/grpc"
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

type sink struct {
	mu  sync.Mutex
	enc *json.Encoder
}

func (s *sink) emit(listener string, rec Record) {
	rec.Listener = listener
	s.mu.Lock()
	_ = s.enc.Encode(rec)
	s.mu.Unlock()
}

func main() {
	var ls listeners
	flag.Var(&ls, "listen", "host:port or unix:/path, with a grpc+ prefix for OTLP/gRPC (repeatable)")
	out := flag.String("out", "sink.jsonl", "JSON lines output")
	flag.Parse()
	if len(ls) == 0 {
		ls = listeners{"127.0.0.1:4318"}
	}
	f, err := os.OpenFile(*out, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		log.Fatal(err)
	}
	s := &sink{enc: json.NewEncoder(f)}
	for _, l := range ls {
		addr, grpcMode := strings.CutPrefix(l, "grpc+")
		network := "tcp"
		if p, ok := strings.CutPrefix(addr, "unix:"); ok {
			network, addr = "unix", p
			_ = os.Remove(p)
		}
		ln, err := net.Listen(network, addr)
		if err != nil {
			log.Fatal(err)
		}
		if network == "unix" {
			_ = os.Chmod(addr, 0o666) // containers run the app as any user
		}
		log.Printf("otlpsink listening on %s", l)
		if grpcMode {
			go serveGRPC(ln, l, s)
		} else {
			go serveHTTP(ln, l, s)
		}
	}
	select {}
}

func serveHTTP(ln net.Listener, l string, s *sink) {
	mux := http.NewServeMux()
	for _, sig := range []string{"traces", "metrics", "logs"} {
		mux.HandleFunc("/v1/"+sig, func(w http.ResponseWriter, r *http.Request) {
			rec, err := decodeHTTP(sig, r)
			if err != nil {
				http.Error(w, err.Error(), http.StatusBadRequest)
				return
			}
			s.emit(l, rec)
			ct := r.Header.Get("Content-Type")
			if ct == "" {
				ct = "application/x-protobuf"
			}
			w.Header().Set("Content-Type", ct)
			w.WriteHeader(http.StatusOK)
		})
	}
	log.Fatal(http.Serve(ln, mux))
}

type traceServer struct {
	coltrace.UnimplementedTraceServiceServer
	l string
	s *sink
}

func (t traceServer) Export(_ context.Context, m *coltrace.ExportTraceServiceRequest) (*coltrace.ExportTraceServiceResponse, error) {
	t.s.emit(t.l, fromTraces(m))
	return &coltrace.ExportTraceServiceResponse{}, nil
}

type metricsServer struct {
	colmetrics.UnimplementedMetricsServiceServer
	l string
	s *sink
}

func (t metricsServer) Export(_ context.Context, m *colmetrics.ExportMetricsServiceRequest) (*colmetrics.ExportMetricsServiceResponse, error) {
	t.s.emit(t.l, fromMetrics(m))
	return &colmetrics.ExportMetricsServiceResponse{}, nil
}

type logsServer struct {
	collogs.UnimplementedLogsServiceServer
	l string
	s *sink
}

func (t logsServer) Export(_ context.Context, m *collogs.ExportLogsServiceRequest) (*collogs.ExportLogsServiceResponse, error) {
	t.s.emit(t.l, fromLogs(m))
	return &collogs.ExportLogsServiceResponse{}, nil
}

func serveGRPC(ln net.Listener, l string, s *sink) {
	g := grpc.NewServer()
	coltrace.RegisterTraceServiceServer(g, traceServer{l: l, s: s})
	colmetrics.RegisterMetricsServiceServer(g, metricsServer{l: l, s: s})
	collogs.RegisterLogsServiceServer(g, logsServer{l: l, s: s})
	log.Fatal(g.Serve(ln))
}

func decodeHTTP(sig string, r *http.Request) (Record, error) {
	body, err := io.ReadAll(r.Body)
	if err != nil {
		return Record{}, err
	}
	unmarshal := proto.Unmarshal
	if strings.HasPrefix(r.Header.Get("Content-Type"), "application/json") {
		unmarshal = func(b []byte, m proto.Message) error { return protojson.Unmarshal(b, m) }
	}
	switch sig {
	case "traces":
		var m coltrace.ExportTraceServiceRequest
		if err := unmarshal(body, &m); err != nil {
			return Record{Signal: sig}, err
		}
		return fromTraces(&m), nil
	case "metrics":
		var m colmetrics.ExportMetricsServiceRequest
		if err := unmarshal(body, &m); err != nil {
			return Record{Signal: sig}, err
		}
		return fromMetrics(&m), nil
	default:
		var m collogs.ExportLogsServiceRequest
		if err := unmarshal(body, &m); err != nil {
			return Record{Signal: sig}, err
		}
		return fromLogs(&m), nil
	}
}

func fromTraces(m *coltrace.ExportTraceServiceRequest) Record {
	rec := Record{Signal: "traces"}
	for _, rs := range m.ResourceSpans {
		rec.Resources = append(rec.Resources, attrs(rs.Resource))
		for _, ss := range rs.ScopeSpans {
			for _, s := range ss.Spans {
				rec.Items++
				rec.Names = append(rec.Names, s.Name)
			}
		}
	}
	return rec
}

func fromMetrics(m *colmetrics.ExportMetricsServiceRequest) Record {
	rec := Record{Signal: "metrics"}
	for _, rm := range m.ResourceMetrics {
		rec.Resources = append(rec.Resources, attrs(rm.Resource))
		for _, sm := range rm.ScopeMetrics {
			for _, mt := range sm.Metrics {
				rec.Items++
				rec.Names = append(rec.Names, mt.Name)
			}
		}
	}
	return rec
}

func fromLogs(m *collogs.ExportLogsServiceRequest) Record {
	rec := Record{Signal: "logs"}
	for _, rl := range m.ResourceLogs {
		rec.Resources = append(rec.Resources, attrs(rl.Resource))
		for _, sl := range rl.ScopeLogs {
			rec.Items += len(sl.LogRecords)
		}
	}
	return rec
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
