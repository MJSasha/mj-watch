package service

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestTMDBCreditsUseAuthenticatedCachedProxy(t *testing.T) {
	s, owner := fixture(t)
	s.Config.SecretKey = filepath.Join(t.TempDir(), "key")
	if err := os.WriteFile(s.Config.SecretKey, bytes.Repeat([]byte("k"), 32), 0600); err != nil {
		t.Fatal(err)
	}
	sealed, err := s.sealCredential("credits-secret")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.DB.Exec("INSERT INTO settings VALUES('tmdb_credential',?),('tmdb',?)", sealed, `{"enabled":true,"language":"ru-RU","cacheMiB":1}`); err != nil {
		t.Fatal(err)
	}

	calls := 0
	var upstream string
	s.Client.Transport = roundTrip(func(r *http.Request) (*http.Response, error) {
		calls++
		if r.URL.Host != "api.themoviedb.org" || r.URL.Path != "/3/"+upstream || r.URL.RawQuery != "language=ru-RU" || r.Header.Get("Authorization") != "Bearer credits-secret" {
			t.Fatalf("unexpected upstream request %s", r.URL)
		}
		body := `{"cast":[{"id":16,"name":"Performer","profile_path":"/profile.jpg"}]}`
		if strings.HasPrefix(upstream, "person/") {
			body = `{"cast":[{"id":42,"title":"Film","media_type":"movie","poster_path":"/poster.jpg"}]}`
		}
		return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(body))}, nil
	})

	if code, _ := call(t, s, "", "GET", "metadata/movie/42/credits", nil); code != 401 {
		t.Fatalf("unauthenticated credits returned %d", code)
	}
	for _, path := range []string{"metadata/person/16/credits", "metadata/movie/42/seasons/1/credits", "metadata/person/0/combined_credits"} {
		if code, _ := call(t, s, owner, "GET", path, nil); code != 404 {
			t.Fatalf("unrecognized route %s returned %d", path, code)
		}
	}
	for _, route := range []struct{ path, image string }{
		{"movie/42/credits", "profile.jpg"},
		{"tv/42/credits", "profile.jpg"},
		{"person/16/combined_credits", "poster.jpg"},
	} {
		upstream = route.path
		before := calls
		for range 2 {
			code, response := call(t, s, owner, "GET", "metadata/"+route.path+"?artwork=logos", nil)
			raw, _ := json.Marshal(response["cast"])
			if code != 200 || !strings.Contains(string(raw), "/api/v1/metadata/images/w500/"+route.image) {
				t.Fatalf("unexpected response for %s (%d): %v", route.path, code, response)
			}
		}
		if calls != before+1 {
			t.Fatalf("expected one upstream request for %s, got %d", route.path, calls-before)
		}
	}
}
