package control

import (
	"dev.mjwatch/server/internal/resources"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestCacheEstimateRequiresTorrentInventory(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/echo":
			w.Write([]byte("MatriX.145.2"))
		case "/settings":
			w.Write([]byte(`{"CacheSize":67108864}`))
		default:
			w.WriteHeader(http.StatusServiceUnavailable)
		}
	}))
	defer upstream.Close()
	if snapshot, err := (TorrControl{URL: upstream.URL}).Read(); err == nil || snapshot != nil {
		t.Fatal("estimated cache without knowing existing torrents", snapshot, err)
	}
}

func TestCacheIncreaseRequiresHeadroomButDiskAndReductionRemainAvailable(t *testing.T) {
	before := map[string]any{"CacheSize": float64(64 << 20), "UseDisk": false}
	after := map[string]any{"CacheSize": float64(128 << 20), "UseDisk": false}
	memory := resources.Memory{Total: 512 << 20, Available: 300 << 20}
	if validateCacheMemory(before, after, false, memory, 1) == nil {
		t.Fatal("cache increase ignored neighbours and reserve")
	}
	after["CacheSize"] = float64(32 << 20)
	if err := validateCacheMemory(before, after, false, memory, 1); err != nil {
		t.Fatal(err)
	}
	memory.Available = 0
	if err := validateCacheMemory(before, after, false, memory, 1); err != nil {
		t.Fatal("reduction blocked", err)
	}
	before["UseDisk"] = true
	if validateCacheMemory(before, after, false, memory, 1) == nil {
		t.Fatal("disk to RAM switch ignored pressure")
	}
	after["UseDisk"] = true
	if err := validateCacheMemory(before, after, false, memory, 1); err != nil {
		t.Fatal("disk cache treated as RAM", err)
	}
}

func TestSettingsRevisionMergeAndUnknownFields(t *testing.T) {
	settings := map[string]any{"CacheSize": float64(64 << 20), "ReaderReadAHead": float64(95), "PreloadCache": float64(50), "UnknownFutureField": "keep", "UnknownFutureObject": map[string]any{"enabled": true}}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/echo" {
			w.Write([]byte("MatriX.145.2"))
			return
		}
		var body map[string]any
		json.NewDecoder(r.Body).Decode(&body)
		if r.URL.Path == "/torrents" {
			json.NewEncoder(w).Encode([]any{})
			return
		}
		if body["action"] == "set" {
			settings = body["sets"].(map[string]any)
		} else {
			json.NewEncoder(w).Encode(settings)
		}
	}))
	defer srv.Close()
	ctl := TorrControl{URL: srv.URL, StateDir: t.TempDir()}
	snapshot, e := ctl.Read()
	if e != nil {
		t.Fatal(e)
	}
	input := Apply{ID: "operation-123456789", Revision: snapshot["revision"].(string), Confirmed: true, Changes: map[string]any{"PreloadCache": float64(25)}}
	got, e := ctl.Apply(input)
	if e != nil {
		t.Fatal(e)
	}
	if settings["UnknownFutureField"] != "keep" || settings["UnknownFutureObject"].(map[string]any)["enabled"] != true || settings["PreloadCache"] != float64(25) {
		t.Fatal(settings)
	}
	if _, e = ctl.Apply(input); e != nil {
		t.Fatal("receipt retry", e)
	}
	input.ID = "another-123456789"
	if _, e = ctl.Apply(input); e == nil {
		t.Fatal("stale revision accepted")
	}
	if got["settings"] == nil {
		t.Fatal(got)
	}
	for _, bad := range []map[string]any{{"UnknownFutureObject": map[string]any{"enabled": false}}, {"PreloadCache": float64(101)}, {"ReaderReadAHead": float64(4)}, {"EnableDLNA": true}, {"CacheSize": "$(touch /tmp/exploit)"}, {"DisableTCP": "false"}} {
		if Validate(bad) == nil {
			t.Fatal("accepted invalid", bad)
		}
	}
}
