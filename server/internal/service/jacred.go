package service

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

var jacredIndexer = regexp.MustCompile(`^[a-zA-Z0-9_-]{1,64}$`)

func (s *Server) jacredSearch(w http.ResponseWriter, r *http.Request) {
	source := r.URL.Query().Get("indexer")
	query := strings.TrimSpace(r.URL.Query().Get("query"))
	if !jacredIndexer.MatchString(source) || len(query) > 512 {
		fail(w, 400, "invalid_search")
		return
	}
	if s.Config.SecretKey == "" {
		fail(w, 503, "jacred_not_installed")
		return
	}
	data, err := os.ReadFile(filepath.Join(filepath.Dir(s.Config.SecretKey), "jacred-access.json"))
	var config struct{ APIKey string }
	if err != nil || json.Unmarshal(data, &config) != nil || config.APIKey == "" {
		fail(w, 503, "jacred_not_installed")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 12*time.Second)
	defer cancel()
	parameters := url.Values{"Query": {query}, "apikey": {config.APIKey}}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet,
		"http://127.0.0.1:19117/api/v2.0/indexers/"+source+"/results?"+parameters.Encode(), nil)
	if err != nil {
		fail(w, 503, "jacred_unavailable")
		return
	}
	resp, err := s.Client.Do(req)
	var body struct {
		Results []json.RawMessage
	}
	if readJSONResponse(resp, err, &body) != nil || body.Results == nil {
		fail(w, 503, "jacred_unavailable")
		return
	}
	if len(body.Results) > 500 {
		body.Results = body.Results[:500]
	}
	respond(w, 200, body)
}
