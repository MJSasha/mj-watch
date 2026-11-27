package service

import (
	"bytes"
	"context"
	"encoding/base32"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"math"
	"mime/multipart"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

func (s *Server) torr(ctx context.Context, path string, body io.Reader, contentType string) (*http.Response, error) {
	req, e := http.NewRequestWithContext(ctx, "POST", s.Config.TorrURL+path, body)
	if e != nil {
		return nil, e
	}
	req.Header.Set("Content-Type", contentType)
	if s.Config.TorrUser != "" {
		req.SetBasicAuth(s.Config.TorrUser, s.Config.TorrPassword)
	}
	return s.Client.Do(req)
}
func readJSONResponse(resp *http.Response, e error, v any) error {
	if e != nil {
		return e
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return &upstreamFailure{resp.StatusCode}
	}
	return json.NewDecoder(io.LimitReader(resp.Body, 4<<20)).Decode(v)
}
func (s *Server) playback(w http.ResponseWriter, r *http.Request, path string, i identity, token string) {
	if path == "playback" && r.Method == "POST" {
		select {
		case s.uploadSlots <- struct{}{}:
			defer func() { <-s.uploadSlots }()
		default:
			w.Header().Set("Retry-After", "5")
			fail(w, 429, "playback_busy")
			return
		}
		var v struct {
			Magnet    string `json:"magnet"`
			Torrent   string `json:"torrent"`
			Hash      string `json:"hash"`
			RequestID string `json:"requestId"`
		}
		if !decodeLimit(w, r, &v, 16<<20) {
			return
		}
		if len(v.RequestID) < 16 || len(v.RequestID) > 128 {
			fail(w, 400, "request_id_required")
			return
		}
		var existing string
		err := s.DB.QueryRow("SELECT handle FROM playback_requests WHERE user_id=? AND id=?", i.User, v.RequestID).Scan(&existing)
		if err == nil {
			if existing == "" {
				fail(w, 409, "playback_outcome_unknown")
				return
			}
			r.URL.Path = "/api/v1/playback/" + existing
			r.Method = "GET"
			s.playback(w, r, "playback/"+existing, i, token)
			return
		}
		if _, err = s.DB.Exec("INSERT INTO playback_requests(user_id,id) VALUES(?,?)", i.User, v.RequestID); err != nil {
			fail(w, 409, "playback_request_pending")
			return
		}
		if v.Magnet != "" {
			u, err := url.Parse(v.Magnet)
			xt := ""
			if u != nil {
				xt = strings.TrimPrefix(u.Query().Get("xt"), "urn:btih:")
			}
			var raw []byte
			if len(xt) == 40 {
				raw, err = hex.DecodeString(xt)
			} else if len(xt) == 32 {
				raw, err = base32.StdEncoding.DecodeString(strings.ToUpper(xt))
			} else {
				err = fmt.Errorf("hash")
			}
			if err != nil || u == nil || u.Scheme != "magnet" || !strings.HasPrefix(u.Query().Get("xt"), "urn:btih:") || len(raw) != 20 || len(v.Magnet) > 65536 {
				s.DB.Exec("DELETE FROM playback_requests WHERE user_id=? AND id=?", i.User, v.RequestID)
				fail(w, 400, "invalid_magnet")
				return
			}
			var pending int
			s.DB.QueryRow("SELECT COUNT(*) FROM playback_jobs j JOIN playback p ON p.id=j.handle WHERE p.user_id=? AND j.state='pending' AND j.created>?", i.User, time.Now().Unix()-120).Scan(&pending)
			if pending >= 2 {
				s.DB.Exec("DELETE FROM playback_requests WHERE user_id=? AND id=?", i.User, v.RequestID)
				w.Header().Set("Retry-After", "5")
				fail(w, 429, "playback_busy")
				return
			}
			select {
			case s.playbackSlots <- struct{}{}:
			default:
				s.DB.Exec("DELETE FROM playback_requests WHERE user_id=? AND id=?", i.User, v.RequestID)
				w.Header().Set("Retry-After", "5")
				fail(w, 429, "playback_busy")
				return
			}
			hash := hex.EncodeToString(raw)
			id := Token()
			tx, err := s.DB.Begin()
			if err != nil {
				<-s.playbackSlots
				fail(w, 503, "database_unavailable")
				return
			}
			defer tx.Rollback()
			_, err = tx.Exec("INSERT INTO playback VALUES(?,?,?)", id, i.User, hash)
			if err == nil {
				_, err = tx.Exec("INSERT INTO playback_jobs VALUES(?,'pending',?)", id, time.Now().Unix())
			}
			if err == nil {
				_, err = tx.Exec("UPDATE playback_requests SET handle=? WHERE user_id=? AND id=?", id, i.User, v.RequestID)
			}
			if err == nil {
				err = tx.Commit()
			}
			if err != nil {
				<-s.playbackSlots
				fail(w, 503, "database_unavailable")
				return
			}
			go s.prepareMagnet(id, hash, v.Magnet)
			respond(w, 200, map[string]any{"id": id, "hash": hash, "torrent": map[string]any{"hash": hash, "file_stats": []any{}}, "preparing": true})
			return
		}
		var result map[string]any
		var e error
		switch {
		case v.Torrent != "":
			data, err := base64.StdEncoding.DecodeString(v.Torrent)
			if err != nil || len(data) > 10<<20 {
				fail(w, 400, "invalid_torrent")
				return
			}
			var b bytes.Buffer
			mw := multipart.NewWriter(&b)
			part, _ := mw.CreateFormFile("file1", "upload.torrent")
			part.Write(data)
			mw.Close()
			var raw json.RawMessage
			e = readTorr(s, r.Context(), "/torrent/upload", &b, mw.FormDataContentType(), &raw)
			if e == nil {
				if len(raw) > 0 && raw[0] == '[' {
					var arr []map[string]any
					e = json.Unmarshal(raw, &arr)
					if len(arr) == 1 {
						result = arr[0]
					} else {
						e = fmt.Errorf("upload_failed")
					}
				} else {
					e = json.Unmarshal(raw, &result)
				}
			}
		default:
			input := map[string]any{"action": "get", "hash": v.Hash}
			if v.Magnet != "" {
				u, err := url.Parse(v.Magnet)
				if err != nil || u.Scheme != "magnet" || !strings.HasPrefix(u.Query().Get("xt"), "urn:btih:") || len(v.Magnet) > 65536 {
					fail(w, 400, "invalid_magnet")
					return
				}
				input = map[string]any{"action": "add", "link": v.Magnet, "save_to_db": false}
			}
			if v.Magnet == "" {
				fail(w, 400, "magnet_or_torrent_required")
				return
			} // no ownership-free hash lookup
			b, _ := json.Marshal(input)
			e = readTorr(s, r.Context(), "/torrents", bytes.NewReader(b), "application/json", &result)
		}
		hash, _ := result["hash"].(string)
		if e != nil || hash == "" {
			fail(w, 502, "torrserver_unavailable")
			return
		}
		id := Token()
		_, e = s.DB.Exec("INSERT INTO playback VALUES(?,?,?)", id, i.User, hash)
		if e != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		s.DB.Exec("UPDATE playback_requests SET handle=? WHERE user_id=? AND id=?", id, i.User, v.RequestID)
		respond(w, 200, map[string]any{"id": id, "hash": hash, "torrent": safeTorrent(result)})
		return
	}
	parts := strings.Split(path, "/")
	if len(parts) < 2 || len(parts) > 3 {
		fail(w, 404, "not_found")
		return
	}
	id := parts[1]
	var hash string
	if s.DB.QueryRow("SELECT hash FROM playback WHERE id=? AND user_id=?", id, i.User).Scan(&hash) != nil {
		fail(w, 404, "not_found")
		return
	}
	if r.Method != "GET" && r.Method != "HEAD" {
		fail(w, 405, "method_not_allowed")
		return
	}
	index := 0
	stats := len(parts) == 3 && parts[2] == "stats"
	if len(parts) == 3 {
		if !stats && parts[2] != "stream" {
			fail(w, 404, "not_found")
			return
		}
		if stats && r.Method != "GET" {
			fail(w, 405, "method_not_allowed")
			return
		}
		var err error
		index, err = strconv.Atoi(r.URL.Query().Get("file"))
		if err != nil || (stats && index <= 0) {
			fail(w, 400, "invalid_file")
			return
		}
	}
	var progress *streamProgress
	var view *activeStream
	if len(parts) == 3 && !stats {
		viewID := r.URL.Query().Get("view")
		if len(viewID) > 64 {
			fail(w, 400, "invalid_view")
			return
		}
		key := streamKey{
			session: i.Session, hash: hash, view: viewID, index: index,
			download: r.URL.Query().Get("download") == "1",
		}
		var scope string
		view, scope = s.acquireStream(i, key, r.Method == "HEAD")
		switch scope {
		case "stopped":
			w.Header().Set("X-MJWatch-Error", "stream_stopped")
			fail(w, 410, "stream_stopped")
			return
		case "overlap":
			w.Header().Set("Retry-After", "1")
			fail(w, 503, "stream_busy")
			return
		case "server", "account":
			w.Header().Set("Retry-After", "5")
			w.Header().Set("X-MJWatch-Error", "stream_limit")
			w.Header().Set("X-MJWatch-Limit-Scope", scope)
			fail(w, 429, "stream_limit")
			return
		}
		if view == nil {
			// HEAD is a metadata probe and has no viewing slot.
			ctx, finish := context.WithTimeout(r.Context(), 25*time.Second)
			defer finish()
			r = r.WithContext(ctx)
		} else {
			defer s.releaseStream(key, view)
			progress = &streamProgress{Writer: w, started: time.Now()}
			ctx, finish := s.watchStream(r.Context(), w, i.Session, view.stopped, progress, streamIdleTimeout)
			defer finish()
			r = r.WithContext(ctx)
		}
	}

	b, _ := json.Marshal(map[string]string{"action": "get", "hash": hash})
	var torrent map[string]any
	if e := readTorr(s, r.Context(), "/torrents", bytes.NewReader(b), "application/json", &torrent); e != nil {
		var state string
		var created int64
		s.DB.QueryRow("SELECT state,created FROM playback_jobs WHERE handle=?", id).Scan(&state, &created)
		if !stats && state == "pending" && time.Now().Unix()-created < 120 {
			respond(w, 200, map[string]any{"id": id, "hash": hash, "torrent": map[string]any{"hash": hash, "file_stats": []any{}}, "preparing": true})
			return
		}
		if status, ok := e.(*upstreamFailure); ok && status.Code == 404 {
			fail(w, 404, "torrent_missing")
		} else {
			fail(w, 502, "torrserver_unavailable")
		}
		return
	}
	if len(parts) == 2 {
		respond(w, 200, map[string]any{"id": id, "hash": hash, "torrent": safeTorrent(torrent)})
		return
	}
	found := false
	var filename string
	if files, ok := torrent["file_stats"].([]any); ok {
		for _, f := range files {
			if m, ok := f.(map[string]any); ok && m["id"] == float64(index) {
				found = true
				filename, _ = m["path"].(string)
				break
			}
		}
	}
	if !found {
		fail(w, 404, "file_not_found")
		return
	}
	if stats {
		respond(w, 200, safePlaybackStats(torrent))
		return
	}
	if view != nil {
		s.mu.Lock()
		view.File = filename
		s.mu.Unlock()
	}
	ctx := r.Context()

	req, _ := http.NewRequestWithContext(ctx, r.Method, s.Config.TorrURL+"/stream?"+url.Values{"link": {hash}, "index": {strconv.Itoa(index)}, "play": {""}}.Encode(), nil)
	if s.Config.TorrUser != "" {
		req.SetBasicAuth(s.Config.TorrUser, s.Config.TorrPassword)
	}
	for _, h := range []string{"Range", "If-Range"} {
		if v := r.Header.Get(h); v != "" {
			req.Header.Set(h, v)
		}
	}
	client := *s.Client
	client.Timeout = 0
	resp, e := client.Do(req)
	if e != nil {
		fail(w, 502, "stream_unavailable")
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 && resp.StatusCode != 206 && resp.StatusCode != 416 {
		fail(w, 502, "stream_unavailable")
		return
	}
	for _, h := range []string{"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "ETag", "Last-Modified"} {
		if v := resp.Header.Get(h); v != "" {
			w.Header().Set(h, v)
		}
	}
	w.WriteHeader(resp.StatusCode)
	if r.Method != "HEAD" {
		started := time.Now()
		copied, err := io.CopyBuffer(progress, resp.Body, make([]byte, 32*1024))
		outcome := "complete"
		if err != nil {
			outcome = "interrupted"
		}
		if ctx.Err() != nil {
			outcome = "cancelled"
		}
		log.Printf("event=stream_end outcome=%s bytes=%d duration_ms=%d", outcome, copied, time.Since(started).Milliseconds())
	}
}

func readTorr(s *Server, ctx context.Context, path string, b io.Reader, mime string, v any) error {
	resp, e := s.torr(ctx, path, b, mime)
	return readJSONResponse(resp, e, v)
}

func safeTorrent(v map[string]any) map[string]any {
	out := map[string]any{"hash": v["hash"], "file_stats": []any{}}
	if files, ok := v["file_stats"].([]any); ok {
		safe := []any{}
		for _, f := range files {
			if row, ok := f.(map[string]any); ok {
				safe = append(safe, map[string]any{"id": row["id"], "path": row["path"], "length": row["length"]})
			}
		}
		out["file_stats"] = safe
	}
	return out
}

func safePlaybackStats(torrent map[string]any) map[string]float64 {
	stats := make(map[string]float64)
	if speed, ok := torrent["download_speed"].(float64); ok && speed >= 0 {
		stats["download_speed"] = speed
	}
	for _, key := range []string{"active_peers", "total_peers", "connected_seeders"} {
		if count, ok := torrent[key].(float64); ok && count >= 0 && count <= math.MaxInt32 && count == math.Trunc(count) {
			stats[key] = count
		}
	}
	// TorrServer reports the current cache occupancy in preloaded_bytes.
	if bytes, ok := torrent["preloaded_bytes"].(float64); ok && bytes >= 0 && bytes < float64(math.MaxInt64) && bytes == math.Trunc(bytes) {
		stats["preloaded_bytes"] = bytes
	}
	return stats
}

type upstreamFailure struct{ Code int }

func (e *upstreamFailure) Error() string { return "torrserver_unavailable" }
func (s *Server) prepareMagnet(id, hash, magnet string) {
	defer func() { <-s.playbackSlots }()
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	body, _ := json.Marshal(map[string]any{"action": "add", "link": magnet, "save_to_db": false})
	req, _ := http.NewRequestWithContext(ctx, "POST", s.Config.TorrURL+"/torrents", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	if s.Config.TorrUser != "" {
		req.SetBasicAuth(s.Config.TorrUser, s.Config.TorrPassword)
	}
	client := *s.Client
	client.Timeout = 90 * time.Second
	resp, err := client.Do(req)
	var result map[string]any
	err = readJSONResponse(resp, err, &result)
	state := "failed"
	if err == nil && result["hash"] == hash {
		state = "ready"
	}
	s.DB.Exec("UPDATE playback_jobs SET state=? WHERE handle=?", state, id)
}
