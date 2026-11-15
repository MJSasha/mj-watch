package service

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

type roundTrip func(*http.Request) (*http.Response, error)

func (f roundTrip) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }
func TestTMDBRotationCacheAndBoundedRoutes(t *testing.T) {
	s, owner := fixture(t)
	s.Config.SecretKey = filepath.Join(t.TempDir(), "key")
	os.WriteFile(s.Config.SecretKey, bytes.Repeat([]byte("k"), 32), 0600)
	status := 200
	calls := 0
	expected := "valid-token"
	s.Client.Transport = roundTrip(func(r *http.Request) (*http.Response, error) {
		calls++
		if r.URL.Host != "api.themoviedb.org" && r.URL.Host != "image.tmdb.org" {
			t.Fatal("arbitrary upstream")
		}
		if r.URL.Host == "image.tmdb.org" && r.Header.Get("Authorization") != "" {
			t.Fatal("credential leaked to image CDN")
		}
		if r.URL.Host == "api.themoviedb.org" {
			if expected == strings.Repeat("a", 32) {
				if r.URL.Query().Get("api_key") != expected {
					t.Fatal("v3 key missing")
				}
			} else if r.Header.Get("Authorization") != "Bearer "+expected {
				t.Fatal("token missing")
			}
		}
		body := `{"results":[{"id":1,"media_type":"movie","poster_path":"/fixture.jpg"}]}`
		mime := "application/json"
		if r.URL.Host == "image.tmdb.org" {
			body = "image"
			mime = "image/jpeg"
		}
		return &http.Response{StatusCode: status, Header: http.Header{"Content-Type": {mime}}, Body: io.NopCloser(strings.NewReader(body))}, nil
	})
	_, config := call(t, s, owner, "GET", "admin/tmdb", nil)
	body := map[string]any{"enabled": true, "language": "ru-RU", "cacheMiB": int64(1), "credential": expected}
	request := func(body any, revision string) (int, map[string]any) {
		raw, _ := json.Marshal(body)
		r := requestForTest("PATCH", "/api/v1/admin/tmdb", bytes.NewReader(raw), owner)
		r.Header.Set("If-Match", revision)
		w := httptest.NewRecorder()
		s.ServeHTTP(w, r)
		var result map[string]any
		json.Unmarshal(w.Body.Bytes(), &result)
		return w.Code, result
	}
	code, result := request(body, config["revision"].(string))
	if code != 200 {
		t.Fatal(code, result)
	}
	_, config = call(t, s, owner, "GET", "admin/tmdb", nil)
	status = 401
	expected = "invalid-new-token"
	body["credential"] = expected
	code, _ = request(body, config["revision"].(string))
	if code != 422 {
		t.Fatal("bad candidate accepted", code)
	}
	stored, e := s.credential()
	if e != nil || stored != "valid-token" {
		t.Fatal("previous key overwritten")
	}
	status = 200
	expected = "valid-token"
	_, data := call(t, s, owner, "GET", "metadata/search?query="+url.QueryEscape("fixture"), nil)
	raw, _ := json.Marshal(data)
	if !strings.Contains(string(raw), "/api/v1/metadata/images/w500/fixture.jpg") || strings.Contains(string(raw), "image.tmdb.org") {
		t.Fatal("unproxied images", data)
	}
	before := calls
	call(t, s, owner, "GET", "metadata/search?query=fixture", nil)
	if calls != before {
		t.Fatal("cache miss")
	}
	code, _ = call(t, s, owner, "GET", "metadata/images/w500/fixture.jpg", nil)
	if code != 200 {
		t.Fatal(code)
	}
	_, config = call(t, s, owner, "GET", "admin/tmdb", nil)
	expected = strings.Repeat("a", 32)
	body["credential"] = expected
	code, result = request(body, config["revision"].(string))
	if code != 200 {
		t.Fatal(code, result)
	}
	status = 429
	code, _ = call(t, s, owner, "GET", "metadata/home", nil)
	if code != 429 {
		t.Fatal("rate limit not surfaced", code)
	}
	status = 200
	code, _ = call(t, s, owner, "DELETE", "admin/tmdb/credential", nil)
	if code != 200 {
		t.Fatal(code)
	}
	if s.tmdbPublic().Enabled || s.tmdbPublic().CredentialConfigured {
		t.Fatal("credential delete ineffective")
	}
}
func TestSnapshotWatermarkPaginationAndOwnership(t *testing.T) {
	s, owner := fixture(t)
	i, _ := s.identity(owner)
	for n := 0; n < 65; n++ {
		key := fmt.Sprintf("item-%03d", n)
		s.DB.Exec("INSERT INTO entities VALUES(?, 'opening',?, '{}',1,0,'',0)", i.User, key)
	}
	_, first := call(t, s, owner, "GET", "snapshot", nil)
	if len(first["entities"].([]any)) != 32 || first["more"] != true {
		t.Fatal(first)
	}
	s.DB.Exec("INSERT INTO entities VALUES(?, 'opening','later','{}',1,0,'',0)", i.User)
	id := first["snapshotId"].(string)
	_, second := call(t, s, owner, "GET", "snapshot?snapshotId="+id+"&offset=32", nil)
	_, third := call(t, s, owner, "GET", "snapshot?snapshotId="+id+"&offset=64", nil)
	if len(second["entities"].([]any)) != 32 || len(third["entities"].([]any)) != 1 || third["more"] != false || first["cursor"] != third["cursor"] {
		t.Fatal("snapshot changed across pages")
	}
	code, _ := call(t, s, owner, "GET", "snapshot?snapshotId="+id+"&offset=0", nil)
	if code != 200 {
		t.Fatal("shared snapshot expired before its TTL")
	}
}
func TestRetentionProducesTombstonesWithoutDeletingFavorites(t *testing.T) {
	s, owner := fixture(t)
	i, _ := s.identity(owner)
	s.DB.Exec("INSERT INTO settings VALUES('retention',?)", `{"historyDays":1,"databaseLimitMiB":1024,"backupKeep":7}`)
	for _, kind := range []string{"opening", "progress", "favorite"} {
		s.DB.Exec("INSERT INTO entities VALUES(?,?,?,'{}',1,0,'',0)", i.User, kind, "old")
		s.DB.Exec("INSERT INTO changes(user_id,kind,key,value,revision,deleted,created) VALUES(?,?,?,'{}',1,0,?)", i.User, kind, "old", time.Now().Add(-48*time.Hour).Unix())
	}
	s.maintenanceTick(context.Background(), time.Now().UTC())
	var deleted int
	s.DB.QueryRow("SELECT deleted FROM entities WHERE kind='favorite'").Scan(&deleted)
	if deleted != 0 {
		t.Fatal("favorite expired")
	}
	s.DB.QueryRow("SELECT COUNT(*) FROM entities WHERE deleted=1").Scan(&deleted)
	if deleted != 2 {
		t.Fatal("history did not tombstone")
	}
}

func requestForTest(method, path string, body io.Reader, token string) *http.Request {
	r := httptest.NewRequest(method, path, body)
	r.Header.Set("Authorization", "Bearer "+token)
	r.RemoteAddr = "127.0.0.1:1234"
	return r
}
