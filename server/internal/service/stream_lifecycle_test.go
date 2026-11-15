package service

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

func TestOpenStreamSurvivesTokenRotationAndStopsAfterDeviceRevocation(t *testing.T) {
	s, _ := fixture(t)
	login, e := Bootstrap(s.DB, "owner", "stream lifecycle")
	if e != nil {
		t.Fatal(e)
	}
	token := login["accessToken"].(string)
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/torrents" {
			io.WriteString(w, `{"hash":"0123456789012345678901234567890123456789","file_stats":[{"id":17,"path":"movie.mp4","length":90000000}]}`)
			return
		}
		if r.URL.Path != "/stream" {
			http.NotFound(w, r)
			return
		}
		tick := time.NewTicker(50 * time.Millisecond)
		defer tick.Stop()
		for {
			select {
			case <-r.Context().Done():
				return
			case <-tick.C:
				if _, e := w.Write(bytes.Repeat([]byte("v"), 40000)); e != nil {
					return
				}
				w.(http.Flusher).Flush()
			}
		}
	}))
	defer upstream.Close()
	s.Config.TorrURL = upstream.URL
	code, v := call(t, s, token, "POST", "playback", map[string]string{"requestId": Token(), "magnet": "magnet:?xt=urn:btih:0123456789012345678901234567890123456789"})
	if code != 200 {
		t.Fatal(code, v)
	}
	gateway := httptest.NewServer(s)
	defer gateway.Close()
	request, _ := http.NewRequest("GET", gateway.URL+"/api/v1/playback/"+v["id"].(string)+"/stream?file=17", nil)
	request.Header.Set("Authorization", "Bearer "+token)
	response, e := http.DefaultClient.Do(request)
	if e != nil {
		t.Fatal(e)
	}
	defer response.Body.Close()
	var received atomic.Int64
	ended := make(chan struct{})
	go func() {
		defer close(ended)
		buffer := make([]byte, 32768)
		for {
			n, e := response.Body.Read(buffer)
			received.Add(int64(n))
			if e != nil {
				return
			}
		}
	}()
	// Rotate both tokens while the same session has an active response.
	code, refresh := call(t, s, "", "POST", "auth/refresh", map[string]string{"refreshToken": login["refreshToken"].(string)})
	if code != 200 {
		t.Fatal(code, refresh)
	}
	time.Sleep(5500 * time.Millisecond)
	select {
	case <-ended:
		t.Fatal("token rotation interrupted existing stream")
	default:
	}
	if received.Load() < 100000 {
		t.Fatal("stream stalled")
	}
	code, v = call(t, s, refresh["accessToken"].(string), "POST", "auth/logout", json.RawMessage(`{}`))
	if code != 200 {
		t.Fatal(code, v)
	}
	select {
	case <-ended:
	case <-time.After(7 * time.Second):
		t.Fatal("revoked stream remained open")
	}
}
