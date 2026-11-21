// opampsink is the local OpAMP server the distro smoke tests talk to, over OpAMP's plain-HTTP transport. It appends
// one JSON line per AgentToServer message to -out (identity, health, the reported status, the remote config status),
// and answers with the remote config in -remote, when that file exists, under the config map key "kloudmate".
//
//	opampsink -listen 127.0.0.1:4320 -out /tmp/opamp.jsonl -remote /tmp/remote.json
package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"flag"
	"io"
	"log"
	"net/http"
	"os"
	"strconv"
	"sync"

	"github.com/open-telemetry/opamp-go/protobufs"
	"google.golang.org/protobuf/proto"
)

// Record is one line of -out.
type Record struct {
	InstanceUID    string            `json:"instance_uid"`
	SequenceNum    uint64            `json:"sequence_num"`
	Capabilities   uint64            `json:"capabilities"`
	Identifying    map[string]string `json:"identifying,omitempty"`
	NonIdentifying map[string]string `json:"non_identifying,omitempty"`
	Healthy        *bool             `json:"healthy,omitempty"`
	HealthStatus   string            `json:"health_status,omitempty"`
	LastError      string            `json:"last_error,omitempty"`
	StartTimeNano  uint64            `json:"start_time_unix_nano,omitempty"`
	Status         json.RawMessage   `json:"status,omitempty"` // the "kloudmate.status" effective config, as JSON
	RemoteStatus   string            `json:"remote_config_status,omitempty"`
	RemoteHash     string            `json:"remote_config_hash,omitempty"`
	RemoteError    string            `json:"remote_config_error,omitempty"`
}

func main() {
	listen := flag.String("listen", "127.0.0.1:4320", "host:port")
	out := flag.String("out", "opamp.jsonl", "JSON lines output")
	remote := flag.String("remote", "", "JSON file served as the remote config (optional, re-read per request)")
	flag.Parse()
	f, err := os.OpenFile(*out, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		log.Fatal(err)
	}
	var mu sync.Mutex
	enc := json.NewEncoder(f)
	http.HandleFunc("/v1/opamp", func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		if err != nil || r.Method != http.MethodPost {
			http.Error(w, "POST an AgentToServer message", http.StatusBadRequest)
			return
		}
		var msg protobufs.AgentToServer
		if err := proto.Unmarshal(body, &msg); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		mu.Lock()
		_ = enc.Encode(record(&msg))
		mu.Unlock()
		resp := &protobufs.ServerToAgent{InstanceUid: msg.InstanceUid}
		if *remote != "" {
			if b, err := os.ReadFile(*remote); err == nil {
				sum := sha256.Sum256(b)
				resp.RemoteConfig = &protobufs.AgentRemoteConfig{
					Config: &protobufs.AgentConfigMap{ConfigMap: map[string]*protobufs.AgentConfigObject{
						"kloudmate": {Body: b, ContentType: "application/json"},
					}},
					ConfigHash: sum[:],
				}
			}
		}
		b, err := proto.Marshal(resp)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "application/x-protobuf")
		_, _ = w.Write(b)
	})
	log.Printf("opampsink listening on %s", *listen)
	log.Fatal(http.ListenAndServe(*listen, nil))
}

func record(m *protobufs.AgentToServer) Record {
	rec := Record{InstanceUID: hex.EncodeToString(m.InstanceUid), SequenceNum: m.SequenceNum, Capabilities: m.Capabilities}
	if d := m.AgentDescription; d != nil {
		rec.Identifying, rec.NonIdentifying = kv(d.IdentifyingAttributes), kv(d.NonIdentifyingAttributes)
	}
	if h := m.Health; h != nil {
		healthy := h.Healthy
		rec.Healthy, rec.HealthStatus, rec.LastError, rec.StartTimeNano = &healthy, h.Status, h.LastError, h.StartTimeUnixNano
	}
	if c := m.EffectiveConfig; c != nil && c.ConfigMap != nil {
		if s := c.ConfigMap.ConfigMap["kloudmate.status"]; s != nil && json.Valid(s.Body) {
			rec.Status = s.Body
		}
	}
	if s := m.RemoteConfigStatus; s != nil {
		rec.RemoteStatus, rec.RemoteHash, rec.RemoteError = s.Status.String(), hex.EncodeToString(s.LastRemoteConfigHash), s.ErrorMessage
	}
	return rec
}

func kv(attrs []*protobufs.KeyValue) map[string]string {
	out := map[string]string{}
	for _, a := range attrs {
		switch v := a.GetValue().GetValue().(type) {
		case *protobufs.AnyValue_StringValue:
			out[a.Key] = v.StringValue
		case *protobufs.AnyValue_IntValue:
			out[a.Key] = strconv.FormatInt(v.IntValue, 10)
		default:
			out[a.Key] = a.GetValue().String()
		}
	}
	return out
}
