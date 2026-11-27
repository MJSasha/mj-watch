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

func TestOwnerCanListAndStopOverlappingStreamRequests(t *testing.T) {
	s, token := fixture(t)
	s.maxStreams, s.maxStreamsPerUser = 1, 1
	i, _ := s.identity(token)
	handle := Token()
	if _, err := s.DB.Exec("INSERT INTO playback VALUES(?,?,?)", handle, i.User, "test-hash"); err != nil {
		t.Fatal(err)
	}
	upstreamClosed := make(chan struct{}, 2)
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/torrents" {
			io.WriteString(w, `{"file_stats":[{"id":17,"path":"movie.mp4"}]}`)
			return
		}
		if r.Method == "HEAD" {
			w.WriteHeader(http.StatusOK)
			return
		}
		w.Header().Set("Content-Length", "100000")
		w.Write(bytes.Repeat([]byte("v"), 40000))
		w.(http.Flusher).Flush()
		<-r.Context().Done()
		upstreamClosed <- struct{}{}
	}))
	defer upstream.Close()
	s.Config.TorrURL = upstream.URL
	gateway := httptest.NewServer(s)
	defer gateway.Close()
	client := gateway.Client()
	client.Timeout = 3 * time.Second
	const list = "admin/playback/streams"
	streamPath := "playback/" + handle + "/stream?file=17&view="
	open := func(view string) *http.Response {
		t.Helper()
		req, _ := http.NewRequest("GET", gateway.URL+"/api/v1/"+streamPath+view, nil)
		req.Header.Set("Authorization", "Bearer "+token)
		resp, err := client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { resp.Body.Close() })
		if resp.StatusCode != 200 {
			t.Fatal(resp.StatusCode)
		}
		return resp
	}
	first := open("playing")
	defer first.Body.Close()
	second := open("playing")
	defer second.Body.Close()
	code, v := call(t, s, token, "GET", list, nil)
	streams, _ := v["streams"].([]any)
	if code != 200 || len(streams) != 1 {
		t.Fatal(code, v)
	}
	stream := streams[0].(map[string]any)
	if stream["account"] != "owner" || stream["device"] != "phone" || stream["file"] != "movie.mp4" || stream["requests"] != float64(2) {
		t.Fatal(stream)
	}
	if code, _ := call(t, s, token, "HEAD", streamPath+"other", nil); code != 200 {
		t.Fatal("HEAD should not take a slot", code)
	}
	if code, _ := call(t, s, token, "GET", streamPath+"other", nil); code != 429 {
		t.Fatal("new view should hit limit", code)
	}
	if code, v := call(t, s, token, "GET", streamPath+"playing", nil); code != 503 || v["code"] != "stream_busy" {
		t.Fatal("range overlap mislabeled as capacity refusal", code, v)
	}
	if _, err := s.DB.Exec("UPDATE users SET role='user' WHERE id=?", i.User); err != nil {
		t.Fatal(err)
	}
	stopPath := list + "/" + stream["id"].(string)
	if code, _ := call(t, s, token, "GET", list, nil); code != 403 {
		t.Fatal("non-owner can read viewers", code)
	}
	if code, _ := call(t, s, token, "DELETE", stopPath, nil); code != 403 {
		t.Fatal("non-owner can stop viewers", code)
	}
	if _, err := s.DB.Exec("UPDATE users SET role='admin' WHERE id=?", i.User); err != nil {
		t.Fatal(err)
	}
	if code, _ := call(t, s, token, "DELETE", stopPath, nil); code != 200 {
		t.Fatal("stop failed", code)
	}
	if code, v := call(t, s, token, "GET", streamPath+"playing", nil); code != 410 || v["code"] != "stream_stopped" {
		t.Fatal("stopped stream reconnected", code, v)
	}
	if code, v := call(t, s, token, "GET", list, nil); code != 200 || len(v["streams"].([]any)) != 0 {
		t.Fatal("stopped stream remained listed", code, v)
	}
	for _, resp := range []*http.Response{first, second} {
		if _, err := io.ReadAll(resp.Body); err != io.ErrUnexpectedEOF {
			t.Fatalf("stream was not interrupted: %v", err)
		}
		select {
		case <-upstreamClosed:
		case <-time.After(time.Second):
			t.Fatal("upstream stream remained open")
		}
	}
	reopened := open("explicit-retry&download=1")
	defer reopened.Body.Close()
	if code, v := call(t, s, token, "GET", list, nil); code != 200 || v["streams"].([]any)[0].(map[string]any)["download"] != true {
		t.Fatal("download not identified", code, v)
	}
}

func TestStreamWatchdogCancelsOnNoProgress(t *testing.T) {
	s, token := fixture(t)
	i, _ := s.identity(token)
	w := httptest.NewRecorder()
	progress := &streamProgress{Writer: w, started: time.Now()}
	ctx, finish := s.watchStream(t.Context(), w, i.Session, nil, progress, 20*time.Millisecond)
	defer finish()
	select {
	case <-ctx.Done():
	case <-time.After(time.Second):
		t.Fatal("stalled stream remained open")
	}
}
