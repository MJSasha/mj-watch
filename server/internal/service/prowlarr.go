package service

import (
	"bytes"
	"cmp"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"io"
	"net/http"
	"net/url"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"time"
)

const prowlarrOrigin = "http://127.0.0.1:19696"
const prowlarrPageSize = 20

var prowlarrDownloadPath = regexp.MustCompile(`^/[1-9][0-9]*/download$`)

type searchProvider struct {
	Provider string
	APIKey   string
}

func (s *Server) searchProvider() searchProvider {
	var provider searchProvider
	if s.Config.SecretKey != "" {
		readJacredFile(filepath.Join(filepath.Dir(s.Config.SecretKey), "search-provider.json"), &provider)
	}
	return provider
}

func prowlarrGet(ctx context.Context, client *http.Client, key, path string) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, prowlarrOrigin+path, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("X-Api-Key", key)
	return client.Do(req)
}

func (s *Server) releaseSearch(w http.ResponseWriter, r *http.Request, i identity) {
	provider := s.searchProvider()
	cursor := r.URL.Query().Get("cursor")
	if provider.Provider != "prowlarr" {
		if cursor != "" {
			fail(w, 409, "search_expired")
			return
		}
		s.searchJacred(w, r, "all")
		return
	}
	if provider.APIKey == "" {
		fail(w, 503, "prowlarr_unavailable")
		return
	}
	query := strings.TrimSpace(r.URL.Query().Get("query"))
	if len(query) > 512 {
		fail(w, 400, "invalid_search")
		return
	}
	pageSize := r.URL.Query().Get("pageSize")
	paged := pageSize == strconv.Itoa(prowlarrPageSize)
	if (pageSize != "" && !paged) || (cursor != "" && !paged) {
		fail(w, 400, "invalid_search_page")
		return
	}
	owner := i.Session
	upstream := Hash(provider.APIKey)
	if cursor != "" {
		id, offset, separator := strings.Cut(cursor, ".")
		start, err := strconv.Atoi(offset)
		if !separator || len(id) != base64.RawURLEncoding.EncodedLen(32) || err != nil ||
			start < prowlarrPageSize || start%prowlarrPageSize != 0 {
			fail(w, 400, "invalid_search_page")
			return
		}
		s.mu.Lock()
		snapshot := s.prowlarrPages[id]
		s.mu.Unlock()
		if snapshot.owner != owner || snapshot.query != query || snapshot.upstream != upstream ||
			!snapshot.expires.After(time.Now()) || start >= len(snapshot.rows) {
			fail(w, 409, "search_expired")
			return
		}
		writeProwlarrPage(w, snapshot.rows, id, start)
		return
	}
	results := make([]prowlarrRelease, 0)
	if query == "" {
		ctx, cancel := context.WithTimeout(r.Context(), 18*time.Second)
		defer cancel()
		var indexers []struct {
			Enable, SupportsSearch bool
			Protocol               string
		}
		resp, err := prowlarrGet(ctx, s.Client, provider.APIKey, "/api/v1/indexer")
		if readJSONResponse(resp, err, &indexers) != nil {
			fail(w, 503, "prowlarr_unavailable")
			return
		}
		configured := false
		for _, indexer := range indexers {
			configured = configured || (indexer.Enable && indexer.SupportsSearch && indexer.Protocol == "torrent")
		}
		if !configured {
			fail(w, 503, "prowlarr_indexer_required")
			return
		}
	} else {
		var rows []struct {
			Title, Indexer, Protocol, MagnetURL, DownloadURL, InfoHash, Guid string
			Size                                                             int64
			Seeders                                                          *int
		}
		parameters := url.Values{"query": {query}, "type": {"search"}, "limit": {"500"}}
		ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
		defer cancel()
		client := *s.Client
		client.Timeout = 0 // Use the search deadline instead of the shared client timeout.
		resp, err := prowlarrGet(ctx, &client, provider.APIKey, "/api/v1/search?"+parameters.Encode())
		if readJSONResponse(resp, err, &rows) != nil || rows == nil {
			fail(w, 503, "prowlarr_unavailable")
			return
		}
		for _, row := range rows {
			if row.Protocol != "torrent" || row.Title == "" {
				continue
			}
			item := prowlarrRelease{Title: row.Title, Tracker: row.Indexer, Hash: row.InfoHash,
				Size: row.Size, Seeds: row.Seeders}
			if row.Guid != "" {
				identity := sha256.Sum256([]byte(row.Indexer + "\x00" + row.Guid))
				item.ID = base64.RawURLEncoding.EncodeToString(identity[:])
			}
			for _, raw := range []string{row.MagnetURL, row.DownloadURL} {
				if strings.HasPrefix(raw, "magnet:?") && len(raw) <= 65536 {
					item.Magnet = raw
					break
				}
				if path := prowlarrProxyPath(raw); path != "" && item.Download == "" {
					item.Download = prowlarrDownloadToken(provider.APIKey, path)
				}
			}
			if item.Magnet != "" || item.Download != "" {
				results = append(results, item)
			}
		}
		seedCount := func(release prowlarrRelease) int {
			if release.Seeds == nil || *release.Seeds < 0 {
				return -1
			}
			return *release.Seeds
		}
		slices.SortStableFunc(results, func(a, b prowlarrRelease) int {
			return cmp.Compare(seedCount(b), seedCount(a))
		})
		if len(results) > 500 {
			results = results[:500]
		}
	}
	if paged && len(results) > prowlarrPageSize {
		id := s.saveProwlarrSnapshot(owner, query, upstream, results)
		writeProwlarrPage(w, results, id, 0)
		return
	}
	respond(w, 200, map[string]any{"provider": "prowlarr", "Results": results})
}

type prowlarrSnapshot struct {
	owner, query, upstream string
	expires                time.Time
	rows                   []prowlarrRelease
}

func (s *Server) saveProwlarrSnapshot(owner, query, upstream string, rows []prowlarrRelease) string {
	now := time.Now()
	id := Token()
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.prowlarrPages == nil {
		s.prowlarrPages = make(map[string]prowlarrSnapshot)
	}
	oldest, expiry := "", now.Add(10*time.Minute)
	for key, entry := range s.prowlarrPages {
		if !entry.expires.After(now) {
			delete(s.prowlarrPages, key)
		} else if oldest == "" || entry.expires.Before(expiry) {
			oldest, expiry = key, entry.expires
		}
	}
	if len(s.prowlarrPages) >= 16 {
		delete(s.prowlarrPages, oldest)
	}
	s.prowlarrPages[id] = prowlarrSnapshot{owner, query, upstream, now.Add(10 * time.Minute), rows}
	return id
}

func writeProwlarrPage(w http.ResponseWriter, rows []prowlarrRelease, id string, start int) {
	end := min(start+prowlarrPageSize, len(rows))
	result := map[string]any{"provider": "prowlarr", "Results": rows[start:end]}
	if end < len(rows) {
		result["nextCursor"] = id + "." + strconv.Itoa(end)
	}
	respond(w, 200, result)
}

type prowlarrRelease struct {
	ID       string `json:"id,omitempty"`
	Title    string `json:"title"`
	Tracker  string `json:"tracker"`
	Hash     string `json:"hash,omitempty"`
	Magnet   string `json:"magnet,omitempty"`
	Download string `json:"download,omitempty"`
	Size     int64  `json:"size"`
	Seeds    *int   `json:"seeds,omitempty"`
}

func prowlarrProxyPath(raw string) string {
	u, err := url.Parse(raw)
	if err != nil || len(raw) > 4096 || u.User != nil || u.Scheme != "http" ||
		u.Host != "127.0.0.1:19696" || !prowlarrDownloadPath.MatchString(u.Path) {
		return ""
	}
	q := u.Query()
	if q.Get("link") == "" || q.Get("file") == "" {
		return ""
	}
	return u.Path + "?" + url.Values{"link": {q.Get("link")}, "file": {q.Get("file")}}.Encode()
}

func prowlarrDownloadToken(key, path string) string {
	mac := hmac.New(sha256.New, []byte(key))
	mac.Write([]byte(path))
	return base64.RawURLEncoding.EncodeToString(mac.Sum(nil)) + "." + base64.RawURLEncoding.EncodeToString([]byte(path))
}

func (s *Server) prowlarrDownload(w http.ResponseWriter, r *http.Request) {
	key := s.searchProvider().APIKey
	token := r.URL.Query().Get("token")
	if key == "" || len(token) > 8192 {
		fail(w, 400, "invalid_release")
		return
	}
	_, encoded, _ := strings.Cut(token, ".")
	payload, err := base64.RawURLEncoding.DecodeString(encoded)
	path := string(payload)
	if err != nil || path == "" || prowlarrProxyPath(prowlarrOrigin+path) != path ||
		!hmac.Equal([]byte(prowlarrDownloadToken(key, path)), []byte(token)) {
		fail(w, 400, "invalid_release")
		return
	}
	select {
	case s.uploadSlots <- struct{}{}:
		defer func() { <-s.uploadSlots }()
	default:
		w.Header().Set("Retry-After", "5")
		fail(w, 429, "playback_busy")
		return
	}
	resp, err := prowlarrGet(r.Context(), s.Client, key, path)
	if err != nil {
		fail(w, 503, "prowlarr_download_failed")
		return
	}
	defer resp.Body.Close()
	var data []byte
	switch {
	case resp.StatusCode >= 300 && resp.StatusCode < 400:
		location := resp.Header.Get("Location")
		if strings.HasPrefix(location, "magnet:?") && len(location) <= 65536 {
			data = []byte(location)
		}
	case resp.StatusCode == 200:
		const limit = 10 << 20
		data, err = io.ReadAll(io.LimitReader(resp.Body, limit+1))
		if err != nil || len(data) > limit || (len(data) > 0 && data[0] != 'd' && !bytes.HasPrefix(data, []byte("magnet:?"))) {
			data = nil
		}
	}
	if len(data) == 0 {
		fail(w, 503, "prowlarr_download_failed")
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Cache-Control", "no-store")
	w.Write(data)
}
