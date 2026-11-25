package service

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

var jacredIndexer = regexp.MustCompile(`^[a-zA-Z0-9_-]{1,64}$`)

type jacredAccess struct {
	APIKey       string
	Collect      *bool
	Synchronized *bool
	Interval     int
	Manual       bool
}

func (s *Server) readJacredAccess() jacredAccess {
	var access jacredAccess
	if s.Config.SecretKey != "" {
		if readJacredFile(filepath.Join(filepath.Dir(s.Config.SecretKey), "jacred-access.json"), &access) {
			return access
		}
	}
	return jacredAccess{}
}

func readJacredFile(path string, value any) bool {
	file, err := os.Open(path)
	if err != nil {
		return false
	}
	defer file.Close()
	return json.NewDecoder(io.LimitReader(file, 64<<10)).Decode(value) == nil
}

func (s *Server) jacredGet(ctx context.Context, path, key string, parameters url.Values, result any) error {
	parameters.Set("apikey", key)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet,
		"http://127.0.0.1:19117"+path+"?"+parameters.Encode(), nil)
	if err != nil {
		return err
	}
	resp, err := s.Client.Do(req)
	return readJSONResponse(resp, err, result)
}

func (s *Server) jacredSearch(w http.ResponseWriter, r *http.Request) {
	source := r.URL.Query().Get("indexer")
	query := strings.TrimSpace(r.URL.Query().Get("query"))
	if !jacredIndexer.MatchString(source) || len(query) > 512 {
		fail(w, 400, "invalid_search")
		return
	}
	access := s.readJacredAccess()
	if access.APIKey == "" {
		fail(w, 503, "jacred_not_installed")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 12*time.Second)
	defer cancel()
	var body struct {
		Results []json.RawMessage
	}
	if s.jacredGet(ctx, "/api/v2.0/indexers/"+source+"/results", access.APIKey, url.Values{"Query": {query}}, &body) != nil || body.Results == nil {
		fail(w, 503, "jacred_unavailable")
		return
	}
	if len(body.Results) > 500 {
		body.Results = body.Results[:500]
	}
	respond(w, 200, body)
}

type jacredIndexStatus struct {
	State     string           `json:"state"`
	StartedAt int64            `json:"startedAt"`
	UpdatedAt int64            `json:"updatedAt"`
	Results   []jacredIndexRow `json:"results"`
	Trigger   string           `json:"trigger"`
	History   []jacredIndexRun `json:"history"`
}

type jacredIndexRun struct {
	State     string `json:"state"`
	StartedAt int64  `json:"startedAt"`
	UpdatedAt int64  `json:"updatedAt"`
	Trigger   string `json:"trigger"`
	Total     int    `json:"total"`
	Failed    int    `json:"failed"`
}

type jacredIndexRow struct {
	Tracker  string `json:"tracker"`
	State    string `json:"state"`
	HTTPCode int    `json:"httpCode,omitempty"`
}

func (s *Server) jacredDiagnostics(w http.ResponseWriter, r *http.Request) {
	query := strings.TrimSpace(r.URL.Query().Get("query"))
	if len(query) > 512 {
		fail(w, 400, "invalid_search")
		return
	}
	access := s.readJacredAccess()
	summary := map[string]any{"jacredDiagnostics": true, "installed": access.APIKey != "", "searchReady": false}
	if access.APIKey == "" {
		respond(w, 200, summary)
		return
	}
	summary["collect"], summary["syncConfigured"], summary["interval"] = access.Collect, access.Synchronized, access.Interval
	summary["manualCollect"] = access.Manual
	if s.Config.Database != "" {
		_, err := os.Lstat(filepath.Join(filepath.Dir(s.Config.Database), "jacred-collect.request"))
		summary["collectQueued"] = err == nil
	}
	now := time.Now()
	summary["checkedAt"] = now.Unix()
	var index jacredIndexStatus
	if readJacredFile(filepath.Join(filepath.Dir(s.Config.SecretKey), "jacred-index.json"), &index) {
		if index.State == "running" && now.Unix()-index.UpdatedAt > 180 {
			index.State = "interrupted"
		}
		summary["index"] = index
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	var search struct{ Results []json.RawMessage }
	searchReady := s.jacredGet(ctx, "/api/v2.0/indexers/all/results", access.APIKey, url.Values{"Query": {query}}, &search) == nil && search.Results != nil
	summary["searchReady"] = searchReady
	if searchReady && query != "" {
		summary["probeCount"] = len(search.Results)
	}
	var stats []struct {
		Tracker string `json:"trackerName"`
		Count   int64  `json:"alltorrents"`
	}
	if s.jacredGet(ctx, "/stats/torrents", access.APIKey, url.Values{}, &stats) == nil && stats != nil {
		var meta struct {
			UpdatedAt time.Time
		}
		if s.jacredGet(ctx, "/stats/meta", access.APIKey, url.Values{}, &meta) == nil && !meta.UpdatedAt.IsZero() {
			summary["statsUpdatedAt"] = meta.UpdatedAt.Unix()
		}
		// JacRed returns an empty array before it has written the first report.
		if len(stats) != 0 || !meta.UpdatedAt.IsZero() {
			var total int64
			for _, row := range stats {
				total += row.Count
			}
			summary["catalogCount"] = total
			summary["trackers"] = stats
		}
	}
	respond(w, 200, summary)
}

func (s *Server) queueJacredCollection(w http.ResponseWriter) {
	access := s.readJacredAccess()
	if access.APIKey == "" || !access.Manual || s.Config.Database == "" {
		fail(w, 503, "jacred_collect_unavailable")
		return
	}
	if access.Collect == nil || !*access.Collect {
		fail(w, 409, "jacred_collect_disabled")
		return
	}
	var index jacredIndexStatus
	if readJacredFile(filepath.Join(filepath.Dir(s.Config.SecretKey), "jacred-index.json"), &index) &&
		index.State == "running" && time.Now().Unix()-index.UpdatedAt <= 180 {
		fail(w, 409, "jacred_collect_running")
		return
	}
	request := filepath.Join(filepath.Dir(s.Config.Database), "jacred-collect.request")
	file, err := os.OpenFile(request, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err == nil {
		file.Close()
	} else if !os.IsExist(err) {
		fail(w, 503, "jacred_collect_unavailable")
		return
	}
	respond(w, 202, map[string]bool{"collectQueued": true})
}
