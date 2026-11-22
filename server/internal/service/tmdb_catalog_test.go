package service

import (
	"bytes"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestCatalogCategoriesAreBoundedTypedAndCachedSeparately(t *testing.T) {
	s, owner := fixture(t)
	s.Config.SecretKey = filepath.Join(t.TempDir(), "key")
	os.WriteFile(s.Config.SecretKey, bytes.Repeat([]byte("k"), 32), 0600)
	sealed, err := s.sealCredential("catalog-secret")
	if err != nil {
		t.Fatal(err)
	}
	s.DB.Exec("INSERT INTO settings VALUES('tmdb_credential',?)", sealed)
	s.DB.Exec("INSERT INTO settings VALUES('tmdb',?)", `{"enabled":true,"language":"ru-RU","cacheMiB":1}`)
	calls := 0
	category := ""
	s.Client.Transport = roundTrip(func(r *http.Request) (*http.Response, error) {
		calls++
		if r.URL.Host != "api.themoviedb.org" || r.Header.Get("Authorization") != "Bearer catalog-secret" {
			t.Fatal("untrusted upstream or missing auth")
		}
		if r.URL.Query().Get("category") != "" {
			t.Fatal("internal category leaked upstream")
		}
		kind := "movie"
		if strings.HasSuffix(r.URL.Path, "/tv") {
			kind = "tv"
		}
		if category == "series" && kind != "tv" || category == "movies" && kind != "movie" {
			t.Fatal("wrong media source", r.URL)
		}
		if category == "anime" && (r.URL.Query().Get("with_genres") != "16" || r.URL.Query().Get("with_original_language") != "ja") {
			t.Fatal("anime query", r.URL)
		}
		body := fmt.Sprintf(`{"results":[{"id":1,"title":"Film","name":"Series","poster_path":"/fixture.jpg","kind":"%s"}]}`, kind)
		return &http.Response{StatusCode: 200, Header: http.Header{"Content-Type": {"application/json"}}, Body: io.NopCloser(strings.NewReader(body))}, nil
	})
	for _, category = range []string{"movies", "series", "anime", "cartoons"} {
		before := calls
		code, response := call(t, s, owner, "GET", "metadata/home?category="+category, nil)
		expected := 1
		if category == "anime" || category == "cartoons" {
			expected = 2
		}
		if code != 200 || response["category"] != category || calls-before != expected {
			t.Fatal(category, code, response, calls-before)
		}
		results := response["results"].([]any)
		if len(results) != expected {
			t.Fatal("incomplete category", response)
		}
		for _, value := range results {
			item := value.(map[string]any)
			if item["media_type"] != item["kind"] || item["poster_path"] != "/api/v1/metadata/images/w500/fixture.jpg" {
				t.Fatal("type or image lost", item)
			}
		}
		before = calls
		call(t, s, owner, "GET", "metadata/home?category="+category, nil)
		if calls != before {
			t.Fatal("category not cached")
		}
	}
	before := calls
	code, _ := call(t, s, owner, "GET", "metadata/home?category=https://evil.invalid", nil)
	if code != 400 || calls != before {
		t.Fatal("unbounded category", code)
	}
}

func TestTitleLogoAndExternalIDsDetailsUseSeparateCache(t *testing.T) {
	s, owner := fixture(t)
	s.Config.SecretKey = filepath.Join(t.TempDir(), "key")
	if err := os.WriteFile(s.Config.SecretKey, bytes.Repeat([]byte("k"), 32), 0600); err != nil {
		t.Fatal(err)
	}
	sealed, err := s.sealCredential("catalog-secret")
	if err != nil {
		t.Fatal(err)
	}
	s.DB.Exec("INSERT INTO settings VALUES('tmdb_credential',?)", sealed)
	s.DB.Exec("INSERT INTO settings VALUES('tmdb',?)", `{"enabled":true,"language":"ru-RU","cacheMiB":1}`)
	calls := 0
	s.Client.Transport = roundTrip(func(r *http.Request) (*http.Response, error) {
		calls++
		q := r.URL.Query()
		body := `{"id":42}`
		if r.URL.Path == "/3/tv/42" {
			if q.Get("append_to_response") == "external_ids" {
				body = `{"id":42,"number_of_seasons":1,"external_ids":{"imdb_id":"tt1234567"}}`
			} else if q.Get("append_to_response") != "" {
				t.Fatalf("unexpected upstream request %s", r.URL)
			}
		} else if q.Get("append_to_response") != "" {
			if r.URL.Path != "/3/movie/42" || q.Get("append_to_response") != "images" || q.Get("include_image_language") != "ru,en,null" || q.Get("artwork") != "" {
				t.Fatalf("unexpected upstream request %s", r.URL)
			}
			body = `{"id":42,"backdrop_path":"/scene.jpg","images":{"logos":[{"iso_639_1":"ru","file_path":"/russian.svg"},{"iso_639_1":"en","file_path":"/../invalid.png"}]}}`
		}
		return &http.Response{StatusCode: 200, Header: http.Header{"Content-Type": {"application/json"}}, Body: io.NopCloser(strings.NewReader(body))}, nil
	})
	code, plain := call(t, s, owner, "GET", "metadata/movie/42", nil)
	if code != 200 || plain["images"] != nil {
		t.Fatal(code, plain)
	}
	for range 2 {
		code, details := call(t, s, owner, "GET", "metadata/movie/42?artwork=logos", nil)
		if code != 200 || details["backdrop_path"] != "/api/v1/metadata/images/w1280/scene.jpg" {
			t.Fatal(code, details)
		}
		logos := details["images"].(map[string]any)["logos"].([]any)
		if logos[0].(map[string]any)["file_path"] != "/api/v1/metadata/images/w500/russian.png" || logos[1].(map[string]any)["file_path"] != nil {
			t.Fatal(logos)
		}
	}
	code, series := call(t, s, owner, "GET", "metadata/tv/42", nil)
	if code != 200 || series["external_ids"] != nil {
		t.Fatal(code, series)
	}
	for range 2 {
		code, series = call(t, s, owner, "GET", "metadata/tv/42?external=1", nil)
		external, _ := series["external_ids"].(map[string]any)
		if code != 200 || external["imdb_id"] != "tt1234567" {
			t.Fatal(code, series)
		}
	}
	if calls != 4 {
		t.Fatalf("got %d TMDB requests, want 4", calls)
	}
}
