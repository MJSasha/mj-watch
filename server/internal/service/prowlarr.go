package service

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"io"
	"net/http"
	"net/url"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

const prowlarrOrigin = "http://127.0.0.1:19696"

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

func (s *Server) prowlarrGet(ctx context.Context, key, path string) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, prowlarrOrigin+path, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("X-Api-Key", key)
	return s.Client.Do(req)
}

func (s *Server) releaseSearch(w http.ResponseWriter, r *http.Request) {
	provider := s.searchProvider()
	if provider.Provider != "prowlarr" {
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
	ctx, cancel := context.WithTimeout(r.Context(), 18*time.Second)
	defer cancel()
	results := make([]prowlarrRelease, 0)
	if query == "" {
		var indexers []struct {
			Enable, SupportsSearch bool
			Protocol               string
		}
		resp, err := s.prowlarrGet(ctx, provider.APIKey, "/api/v1/indexer")
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
		resp, err := s.prowlarrGet(ctx, provider.APIKey, "/api/v1/search?"+parameters.Encode())
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
			if len(results) == 500 {
				break
			}
		}
	}
	respond(w, 200, map[string]any{"provider": "prowlarr", "Results": results})
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
	resp, err := s.prowlarrGet(r.Context(), key, path)
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
