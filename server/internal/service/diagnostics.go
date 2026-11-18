package service

import (
	"log"
	"net/http"
	"strings"
	"time"
)

type observedResponse struct {
	http.ResponseWriter
	status int
}

func (w *observedResponse) Unwrap() http.ResponseWriter { return w.ResponseWriter }
func (w *observedResponse) WriteHeader(status int) {
	if w.status != 0 {
		return
	}
	w.status = status
	w.ResponseWriter.WriteHeader(status)
}
func (w *observedResponse) Write(b []byte) (int, error) {
	if w.status == 0 {
		w.WriteHeader(http.StatusOK)
	}
	return w.ResponseWriter.Write(b)
}

// Never log paths, queries, bodies, headers, tokens or upstream error strings.
func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	started := time.Now()
	observed := &observedResponse{ResponseWriter: w}
	s.serveHTTP(observed, r)
	stream := strings.HasPrefix(r.URL.Path, "/api/v1/playback/") && strings.HasSuffix(r.URL.Path, "/stream")
	if observed.status >= 500 || observed.status == 429 || (!stream && time.Since(started) >= 2*time.Second) {
		// Only advertise a fixed category, never a user-supplied path component.
		category := "api"
		for _, prefix := range []string{"auth/", "playback", "metadata/", "admin/", "sync", "snapshot", "mutations"} {
			if strings.HasPrefix(strings.TrimPrefix(r.URL.Path, "/api/v1/"), prefix) {
				category = strings.TrimSuffix(prefix, "/")
				break
			}
		}
		log.Printf("event=request category=%s status=%d duration_ms=%d", category, observed.status, time.Since(started).Milliseconds())
	}
}
