package service

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

func fixture(t *testing.T) (*Server, string) {
	t.Helper()
	db, e := Open(filepath.Join(t.TempDir(), "state.db"))
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { db.Close() })
	out, e := Bootstrap(db, "owner", "phone")
	if e != nil {
		t.Fatal(e)
	}
	return New(db, Config{}), out["accessToken"].(string)
}
func call(t *testing.T, s *Server, token, method, path string, body any) (int, map[string]any) {
	t.Helper()
	b, _ := json.Marshal(body)
	r := httptest.NewRequest(method, "/api/v1/"+path, bytes.NewReader(b))
	r.RemoteAddr = "127.0.0.1:1234"
	if token != "" {
		r.Header.Set("Authorization", "Bearer "+token)
	}
	w := httptest.NewRecorder()
	s.ServeHTTP(w, r)
	v := map[string]any{}
	json.Unmarshal(w.Body.Bytes(), &v)
	return w.Code, v
}
func TestAuthorizationSyncIsolationAndPairing(t *testing.T) {
	s, owner := fixture(t)
	code, created := call(t, s, owner, "POST", "admin/users", map[string]string{"name": "viewer"})
	if code != 200 {
		t.Fatal(code, created)
	}
	code, user := call(t, s, "", "POST", "auth/accept-invite", map[string]string{"invite": created["invite"].(string), "password": "correct-horse-battery", "device": "TV"})
	if code != 200 {
		t.Fatal(code, user)
	}
	token := user["accessToken"].(string)
	code, _ = call(t, s, "", "POST", "auth/accept-invite", map[string]string{"invite": created["invite"].(string), "password": "correct-horse-battery", "device": "TV"})
	if code != 401 {
		t.Fatal("invite reused", code)
	}
	for _, path := range []string{"admin/users", "admin/tmdb", "admin/settings", "admin/status", "admin/torrserver/settings"} {
		code, _ := call(t, s, token, "GET", path, nil)
		if code != 403 {
			t.Fatal(path, code)
		}
	}
	code, _ = call(t, s, owner, "DELETE", "admin/users/"+func() string { i, _ := s.identity(owner); return i.User }(), nil)
	if code != 409 {
		t.Fatal("last owner", code)
	}
	m := Mutation{MutationID: Token(), Kind: "progress", Key: "hash:abc|movie.mp4", Base: 0, Operation: "put", Value: json.RawMessage(`{"positionMs":12000,"path":"movie.mp4"}`), ServerEpoch: value(s.DB, "server_epoch"), HistoryEpoch: 1, PlaySession: "play", Sequence: 1}
	code, result := call(t, s, token, "POST", "mutations", m)
	if code != 200 {
		t.Fatal(code, result)
	}
	if result["entity"].(map[string]any)["value"].(map[string]any)["deviceName"] != "TV" {
		t.Fatal("progress device provenance missing")
	}
	code, again := call(t, s, token, "POST", "mutations", m)
	if code != 200 || result["entity"].(map[string]any)["revision"] != again["entity"].(map[string]any)["revision"] {
		t.Fatal("idempotency", again)
	}
	_, snapshot := call(t, s, owner, "GET", "snapshot", nil)
	if len(snapshot["entities"].([]any)) != 0 {
		t.Fatal("cross-user leak")
	}
	m.MutationID = Token()
	m.PlaySession = "offline"
	m.Sequence = 1
	m.Value = json.RawMessage(`{"positionMs":5000}`)
	code, _ = call(t, s, token, "POST", "mutations", m)
	if code != 409 {
		t.Fatal("stale write", code)
	}
	m.Base = 1
	code, _ = call(t, s, token, "POST", "mutations", m)
	if code != 200 {
		t.Fatal("backward seek rejected", code)
	}
	m.Base = 2
	m.MutationID = Token()
	m.Operation = "delete"
	code, _ = call(t, s, token, "POST", "mutations", m)
	if code != 200 {
		t.Fatal(code)
	}
	m.MutationID = Token()
	m.Base = 3
	m.Operation = "put"
	m.Sequence = 2
	code, _ = call(t, s, token, "POST", "mutations", m)
	if code != 409 {
		t.Fatal("tombstone resurrected", code)
	}
	code, _ = call(t, s, token, "DELETE", "history", nil)
	if code != 200 {
		t.Fatal(code)
	}
	m.MutationID = Token()
	m.Operation = "restore"
	code, _ = call(t, s, token, "POST", "mutations", m)
	if code != 409 {
		t.Fatal("old epoch accepted", code)
	}
	_, pair := call(t, s, "", "POST", "pairing", map[string]string{"device": "Living room TV"})
	id := pair["id"].(string)
	code, _ = call(t, s, "", "GET", "pairing/"+id, nil)
	if code != 404 {
		t.Fatal("unprotected poll", code)
	}
	_, approved := call(t, s, token, "POST", "pairing/"+pair["code"].(string)+"/approve", map[string]string{"code": pair["code"].(string)})
	if approved["status"] != "approved" {
		t.Fatal(approved)
	}
	i, _ := s.identity(token)
	code, _ = call(t, s, token, "DELETE", "devices/"+i.Device, nil)
	if code != 200 {
		t.Fatal(code)
	}
	code, _ = call(t, s, token, "GET", "me", nil)
	if code != 401 {
		t.Fatal("revoked device accepted")
	}
}
func TestHistoryDeletionRedactsSyncPayloads(t *testing.T) {
	s, token := fixture(t)
	epoch := value(s.DB, "server_epoch")
	send := func(m Mutation) map[string]any {
		t.Helper()
		code, result := call(t, s, token, "POST", "mutations", m)
		if code != http.StatusOK {
			t.Fatal(code, result)
		}
		return result["entity"].(map[string]any)
	}
	m := Mutation{MutationID: Token(), Kind: "progress", Key: "deleted", Operation: "put", Value: json.RawMessage(`{"positionMs":12000}`), ServerEpoch: epoch, HistoryEpoch: 1}
	send(m)
	other := m
	other.MutationID, other.Key = Token(), "kept"
	send(other)
	_, snapshot := call(t, s, token, "GET", "snapshot", nil)

	deletion := m
	deletion.MutationID, deletion.Base, deletion.Operation = Token(), 1, "delete"
	send(deletion)
	assertRedacted := func(entity map[string]any) {
		t.Helper()
		if entity["deleted"] != true || len(entity["value"].(map[string]any)) != 0 {
			t.Fatal("history payload retained", entity)
		}
	}
	assertRedacted(send(m))
	_, page := call(t, s, token, "GET", "snapshot?snapshotId="+snapshot["snapshotId"].(string), nil)
	for _, row := range page["entities"].([]any) {
		entity := row.(map[string]any)
		if entity["entityKey"] == m.Key {
			assertRedacted(entity)
		} else if entity["value"].(map[string]any)["positionMs"] != float64(12000) {
			t.Fatal("unrelated progress was removed", entity)
		}
	}
	code, result := call(t, s, token, "DELETE", "history", nil)
	if code != http.StatusOK {
		t.Fatal(code, result)
	}
	assertRedacted(send(other))
	_, changes := call(t, s, token, "GET", "sync?cursor=0&serverEpoch="+epoch, nil)
	for _, row := range changes["entities"].([]any) {
		assertRedacted(row.(map[string]any))
	}
}

func TestRangeOwnershipAndStreamRevocation(t *testing.T) {
	s, owner := fixture(t)
	// Prepare actors before playback starts its background database writer.
	other, err := Bootstrap(s.DB, "owner", "other")
	if err != nil {
		t.Fatal("second owner device", err)
	}
	otherID := other["accessToken"].(string)
	code, newUser := call(t, s, owner, "POST", "admin/users", map[string]string{"name": "other-user"})
	if code != 200 {
		t.Fatal(code, newUser)
	}
	code, login := call(t, s, "", "POST", "auth/accept-invite", map[string]string{"invite": newUser["invite"].(string), "password": "other-password-long", "device": "other"})
	if code != 200 {
		t.Fatal(code, login)
	}
	media := bytes.Repeat([]byte("video"), 10000)
	torr := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/torrents" {
			w.Header().Set("Content-Type", "application/json")
			io.WriteString(w, `{"hash":"abc","file_stats":[{"id":17,"path":"movie.mp4","length":50000}]}`)
		} else if r.URL.Path == "/stream" {
			if r.URL.Query().Get("index") != "17" {
				t.Error("wrong server file ID")
			}
			http.ServeContent(w, r, "movie.mp4", time.Time{}, bytes.NewReader(media))
		} else {
			t.Error("unapproved upstream", r.URL.Path)
		}
	}))
	defer torr.Close()
	s.Config.TorrURL = torr.URL
	code, v := call(t, s, owner, "POST", "playback", map[string]string{"requestId": Token(), "magnet": "magnet:?xt=urn:btih:0123456789012345678901234567890123456789"})
	if code != 200 {
		t.Fatal(code, v)
	}
	id := v["id"].(string)
	code, _ = call(t, s, owner, "GET", "playback/"+id+"/stream?file=3", nil)
	if code != 404 {
		t.Fatal("unknown file", code)
	}
	for _, span := range []string{"20000-20099", "100-199"} {
		r := httptest.NewRequest("GET", "/api/v1/playback/"+id+"/stream?file=17", nil)
		r.Header.Set("Authorization", "Bearer "+owner)
		r.Header.Set("Range", "bytes="+span)
		w := httptest.NewRecorder()
		s.ServeHTTP(w, r)
		start, _ := strconv.Atoi(strings.SplitN(span, "-", 2)[0])
		if w.Code != 206 || !bytes.Equal(w.Body.Bytes(), media[start:start+100]) || w.Header().Get("Content-Range") != "bytes "+span+"/50000" {
			t.Fatal(w.Code, w.Header(), w.Body.Len())
		}
	}
	// Same user, own handles remain available across devices.
	code, _ = call(t, s, otherID, "GET", "playback/"+id, nil)
	if code != 200 {
		t.Fatal(code)
	}
	code, _ = call(t, s, login["accessToken"].(string), "GET", "playback/"+id, nil)
	if code != 404 {
		t.Fatal("playback IDOR", code)
	}
	for _, path := range []string{"playback/" + id + "/settings", "playback/" + id + "/../settings", "admin/torrserver"} {
		code, _ = call(t, s, owner, "POST", path, nil)
		if code == 200 {
			t.Fatal("arbitrary proxy", path)
		}
	}
}
func TestBackupOnLiveWALAndRestoreInvalidatesCredentials(t *testing.T) {
	s, token := fixture(t)
	dir := t.TempDir()
	s.Config.SecretKey = filepath.Join(dir, "secret.key")
	s.Config.Certificate = filepath.Join(dir, "tls.crt")
	s.Config.PrivateKey = filepath.Join(dir, "tls.key")
	s.Config.BackupDir = dir
	for _, name := range []string{"secret.key", "tls.crt", "tls.key", "ca.crt", "ca.key"} {
		if e := os.WriteFile(filepath.Join(dir, name), bytes.Repeat([]byte("x"), 32), 0600); e != nil {
			t.Fatal(e)
		}
	}
	pass := "restore-passphrase-is-separate"
	archive := filepath.Join(dir, "test.mjwb")
	if e := CreateBackup(context.Background(), s.DB, s.Config, pass, archive); e != nil {
		t.Fatal(e)
	}
	raw, _ := os.ReadFile(archive)
	if bytes.Contains(raw, []byte("SQLite format")) || bytes.Contains(raw, []byte(token)) {
		t.Fatal("unencrypted archive")
	}
	dest := filepath.Join(dir, "restored")
	if e := ExtractBackup(archive, "incorrect-passphrase", dest); e == nil {
		t.Fatal("wrong passphrase accepted")
	}
	if e := ExtractBackup(archive, pass, dest); e != nil {
		t.Fatal(e)
	}
	db, e := Open(filepath.Join(dest, "state.db"))
	if e != nil {
		t.Fatal(e)
	}
	defer db.Close()
	restored := New(db, Config{})
	if _, e := restored.identity(token); e == nil {
		t.Fatal("restored session survived")
	}
	if value(db, "server_epoch") == value(s.DB, "server_epoch") {
		t.Fatal("epoch not rotated")
	}
	var n int
	db.QueryRow("SELECT COUNT(*) FROM users").Scan(&n)
	if n != 1 {
		t.Fatal("lost owner")
	}
	tampered := append([]byte{}, raw...)
	tampered[len(tampered)-1] ^= 1
	os.WriteFile(filepath.Join(dir, "bad.mjwb"), tampered, 0600)
	if e := ExtractBackup(filepath.Join(dir, "bad.mjwb"), pass, filepath.Join(dir, "bad")); e == nil {
		t.Fatal("tampered archive accepted")
	}
	if e := ExtractBackup(archive, pass, filepath.Join(dir, "restored-again")); e != nil {
		t.Fatal(e)
	}
}
func TestSecretsNeverReturnedAndTMDBBoundaries(t *testing.T) {
	s, owner := fixture(t)
	s.Config.SecretKey = filepath.Join(t.TempDir(), "key")
	os.WriteFile(s.Config.SecretKey, bytes.Repeat([]byte("k"), 32), 0600)
	sealed, e := s.sealCredential("server-only-secret")
	if e != nil {
		t.Fatal(e)
	}
	s.DB.Exec("INSERT INTO settings VALUES('tmdb_credential',?)", sealed)
	for _, path := range []string{"admin/tmdb", "config", "capabilities"} {
		code, v := call(t, s, owner, "GET", path, nil)
		raw, _ := json.Marshal(v)
		if code != 200 || strings.Contains(string(raw), "server-only-secret") || strings.Contains(string(raw), sealed) {
			t.Fatal("secret leak", path, v)
		}
	}
	for _, path := range []string{"metadata/images/w500/../../evil.jpg", "metadata/images/w500/https://evil.test/a.jpg", "metadata/https://evil.test", "metadata/tv/1/seasons/1/episodes/1/../../settings"} {
		code, _ := call(t, s, owner, "GET", path, nil)
		if code == 200 {
			t.Fatal("open proxy", path)
		}
	}
	s.DB.Exec("INSERT OR REPLACE INTO settings VALUES('tmdb',?)", `{"enabled":true,"language":"ru-RU","cacheMiB":1}`)
	for _, path := range []string{"metadata/images/w500/../../evil.jpg", "metadata/images/w500/http:evil.jpg", "metadata/https://evil.test"} {
		code, _ := call(t, s, owner, "GET", path, nil)
		if code != 404 {
			t.Fatal("invalid upstream path", code)
		}
	}
}

func TestPlaybackStatsExposeOnlyAuthorizedTorrentCounters(t *testing.T) {
	s, owner := fixture(t)
	identity, err := s.identity(owner)
	if err != nil {
		t.Fatal(err)
	}
	id := Token()
	const hash = "0123456789012345678901234567890123456789"
	if _, err := s.DB.Exec("INSERT INTO playback VALUES(?,?,?)", id, identity.User, hash); err != nil {
		t.Fatal(err)
	}
	code, invite := call(t, s, owner, "POST", "admin/users", map[string]string{"name": "other-viewer"})
	if code != 200 {
		t.Fatal(code, invite)
	}
	code, login := call(t, s, "", "POST", "auth/accept-invite", map[string]string{"invite": invite["invite"].(string), "password": "other-password-long", "device": "other"})
	if code != 200 {
		t.Fatal(code, login)
	}
	calls := 0
	s.Config.TorrURL = "http://torrserver.invalid"
	s.Client.Transport = roundTrip(func(r *http.Request) (*http.Response, error) {
		calls++
		var request map[string]string
		if r.Method != "POST" || r.URL.Path != "/torrents" || json.NewDecoder(r.Body).Decode(&request) != nil || request["action"] != "get" || request["hash"] != hash {
			t.Fatalf("unexpected upstream request %s %s %v", r.Method, r.URL, request)
		}
		body := `{"download_speed":1200000.5,"active_peers":2,"total_peers":9,"connected_seeders":0,"preloaded_bytes":999,"preload_size":2000,"data":"private","file_stats":[{"id":17,"path":"private.mp4"}]}`
		return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(body))}, nil
	})
	path := "playback/" + id + "/stats?file="
	for _, check := range []struct {
		token, method, file string
		want                int
	}{
		{"", "GET", "17", 401},
		{login["accessToken"].(string), "GET", "17", 404},
		{owner, "POST", "17", 405},
		{owner, "GET", "-1", 400},
	} {
		if code, body := call(t, s, check.token, check.method, path+check.file, nil); code != check.want {
			t.Fatal(code, body)
		}
	}
	if calls != 0 {
		t.Fatal("unauthorized requests reached TorrServer")
	}
	if code, _ := call(t, s, owner, "GET", path+"99", nil); code != 404 {
		t.Fatal("unknown file", code)
	}
	code, body := call(t, s, owner, "GET", path+"17", nil)
	if code != 200 || len(body) != 5 || body["download_speed"] != 1200000.5 || body["active_peers"] != 2.0 || body["total_peers"] != 9.0 || body["connected_seeders"] != 0.0 || body["preloaded_bytes"] != 999.0 {
		t.Fatal(code, body)
	}
}
