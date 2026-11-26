package service

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestManagedProwlarrSearchAndDownload(t *testing.T) {
	s, owner := fixture(t)
	dir := t.TempDir()
	s.Config.SecretKey = filepath.Join(dir, "secret.key")
	const key = "private-prowlarr-test-key"
	err := os.WriteFile(filepath.Join(dir, "search-provider.json"), []byte(`{"provider":"prowlarr","apiKey":"`+key+`"}`), 0600)
	if err != nil {
		t.Fatal(err)
	}
	const magnet = "magnet:?xt=urn:btih:1234567890123456789012345678901234567890"
	downloads := 0
	s.Client.Transport = roundTrip(func(r *http.Request) (*http.Response, error) {
		if r.URL.Host != "127.0.0.1:19696" || r.Header.Get("X-Api-Key") != key || r.URL.Query().Has("apikey") {
			t.Fatalf("unexpected Prowlarr request: %s", r.URL.Path)
		}
		switch r.URL.Path {
		case "/api/v1/indexer":
			return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(
				`[{"enable":true,"supportsSearch":true,"protocol":"torrent"}]`))}, nil
		case "/api/v1/search":
			if r.URL.Query().Get("query") != "Film & Series" {
				t.Fatal(r.URL.Query())
			}
			return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(`[
				{"title":"Film","indexer":"Test","protocol":"torrent","seeders":12,"downloadUrl":"` + prowlarrOrigin + `/1/download?apikey=` + key + `&link=opaque&file=Test"},
				{"title":"News","protocol":"usenet","magnetUrl":"` + magnet + `"},
				{"title":"External","protocol":"torrent","downloadUrl":"https://example.org/1/download?link=opaque&file=Test"}
			]`))}, nil
		case "/1/download":
			downloads++
			if r.URL.Query().Get("link") != "opaque" || r.URL.Query().Get("file") != "Test" {
				t.Fatal(r.URL.Query())
			}
			if downloads == 1 {
				return &http.Response{StatusCode: 302, Header: http.Header{"Location": {magnet}}, Body: io.NopCloser(bytes.NewReader(nil))}, nil
			}
			return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader("d4:infodee"))}, nil
		default:
			t.Fatalf("unexpected upstream path: %s", r.URL.Path)
			return nil, nil
		}
	})
	const search = "search/releases?indexer=all&query=Film%20%26%20Series"
	if code, _ := call(t, s, "", "GET", search, nil); code != 401 {
		t.Fatal(code)
	}
	if code, body := call(t, s, owner, "GET", "search/releases?indexer=all", nil); code != 200 || body["provider"] != "prowlarr" {
		t.Fatal(code, body)
	}
	code, body := call(t, s, owner, "GET", search, nil)
	if code != 200 || body["provider"] != "prowlarr" {
		t.Fatal(code, body)
	}
	results := body["Results"].([]any)
	if len(results) != 1 {
		t.Fatal(results)
	}
	release := results[0].(map[string]any)
	token := release["download"].(string)
	if release["title"] != "Film" || release["seeds"] != float64(12) ||
		token != prowlarrDownloadToken(key, "/1/download?file=Test&link=opaque") {
		t.Fatal(release)
	}
	const downloadRoute = "search/prowlarr-download?token="
	if code, _ := call(t, s, owner, "GET", downloadRoute+token+"x", nil); code != 400 || downloads != 0 {
		t.Fatalf("tampered reference was used: %d", code)
	}
	for _, expected := range []string{magnet, "d4:infodee"} {
		request := httptest.NewRequest("GET", "/api/v1/"+downloadRoute+token, nil)
		request.Header.Set("Authorization", "Bearer "+owner)
		w := httptest.NewRecorder()
		s.ServeHTTP(w, request)
		if w.Code != 200 || w.Body.String() != expected {
			t.Fatal(w.Code, w.Body.String())
		}
	}
}

func TestManagedReleaseSearchChoosesJacredIndexersOnServer(t *testing.T) {
	s, owner := fixture(t)
	dir := t.TempDir()
	s.Config.SecretKey = filepath.Join(dir, "secret.key")
	if err := os.WriteFile(filepath.Join(dir, "jacred-access.json"), []byte(`{"apiKey":"fixture"}`), 0600); err != nil {
		t.Fatal(err)
	}
	s.Client.Transport = roundTrip(func(r *http.Request) (*http.Response, error) {
		if r.URL.Path != "/api/v2.0/indexers/all/results" || r.URL.Query().Get("Query") != "Film" {
			t.Fatal(r.URL)
		}
		return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(`{"Results":[]}`))}, nil
	})
	if code, body := call(t, s, owner, "GET", "search/releases?query=Film&indexer=client-choice", nil); code != 200 {
		t.Fatal(code, body)
	}
}
