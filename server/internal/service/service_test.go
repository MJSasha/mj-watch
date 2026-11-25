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

func TestManagedJacredSearch(t *testing.T) {
	s, owner := fixture(t)
	dir := t.TempDir()
	s.Config.SecretKey = filepath.Join(dir, "secret.key")
	path := "search/jacred?indexer=all&query=Film%20%26%20Series"
	if code, _ := call(t, s, "", "GET", path, nil); code != 401 {
		t.Fatal(code)
	}
	if code, body := call(t, s, owner, "GET", path, nil); code != 503 || body["code"] != "jacred_not_installed" {
		t.Fatal(code, body)
	}
	if err := os.WriteFile(filepath.Join(dir, "jacred-access.json"), []byte(`{"apiKey":"local-test-key"}`), 0600); err != nil {
		t.Fatal(err)
	}
	s.Client.Transport = roundTrip(func(r *http.Request) (*http.Response, error) {
		q := r.URL.Query()
		if r.URL.Host != "127.0.0.1:19117" || r.URL.Path != "/api/v2.0/indexers/all/results" ||
			q.Get("apikey") != "local-test-key" || q.Get("Query") != "Film & Series" || r.Header.Get("Authorization") != "" {
			t.Fatal("Unexpected JacRed request")
		}
		return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(`{"results":[{"Title":"Film"}],"private":"ignored"}`))}, nil
	})
	if code, _ := call(t, s, owner, "GET", "search/jacred?indexer=../stats", nil); code != 400 {
		t.Fatal(code)
	}
	if code, body := call(t, s, owner, "GET", path, nil); code != 200 || len(body) != 1 || len(body["Results"].([]any)) != 1 {
		t.Fatal(code, body)
	}
}

func TestJacredBackgroundJobs(t *testing.T) {
	s, owner := fixture(t)
	dir := t.TempDir()
	s.Config.SecretKey = filepath.Join(dir, "secret.key")
	if err := os.WriteFile(filepath.Join(dir, "jacred-access.json"),
		[]byte(`{"apiKey":"private-key","collect":true,"trackers":["rutor","lostfilm"]}`), 0600); err != nil {
		t.Fatal(err)
	}
	const path = "admin/jacred/jobs"
	start := jacredJobTarget{Tracker: "rutor", Job: jacredUpdateTasks}
	if code, _ := call(t, s, "", "POST", path, start); code != 401 {
		t.Fatal(code)
	}
	code, user := call(t, s, owner, "POST", "admin/users", map[string]string{"name": "viewer"})
	if code != 200 {
		t.Fatal(code, user)
	}
	code, account := call(t, s, "", "POST", "auth/accept-invite", map[string]string{
		"invite": user["invite"].(string), "password": "viewer-test-password", "device": "phone",
	})
	if code != 200 {
		t.Fatal(code, account)
	}
	viewer := account["accessToken"].(string)
	for _, method := range []string{http.MethodGet, http.MethodPost} {
		if code, _ := call(t, s, viewer, method, path, start); code != 403 {
			t.Fatal(method, code)
		}
	}
	starts := 0
	jobList := `{"jobs":[{"id":"rutor:ParseAllTask","tracker":"rutor","job":"ParseAllTask","startedAtUtc":"2026-09-08T13:48:01.5624351Z","elapsedSeconds":12,"pagesCompleted":6,"pagesTotal":154,"percent":4,"currentCategory":"32","currentPage":5,"private":"ignored"}]}`
	s.Client.Transport = roundTrip(func(r *http.Request) (*http.Response, error) {
		if r.URL.Host != "127.0.0.1:19117" || r.Method != http.MethodGet || r.Header.Get("Authorization") != "" {
			t.Fatal("Unexpected JacRed request")
		}
		body := jobList
		switch r.URL.Path {
		case "/health/background-jobs":
		case "/cron/rutor/UpdateTasksParse":
			starts++
			if starts == 1 {
				body = "ok"
			} else {
				body = "work"
			}
		default:
			t.Fatal("Unexpected JacRed path", r.URL.Path)
		}
		return &http.Response{StatusCode: http.StatusOK, Body: io.NopCloser(strings.NewReader(body))}, nil
	})
	code, report := call(t, s, owner, "GET", path, nil)
	if code != 200 {
		t.Fatal(code, report)
	}
	jobs := report["jobs"].([]any)
	available := report["availableStarts"].([]any)
	if len(jobs) != 1 || len(available) != 1 {
		t.Fatal(report)
	}
	job, option := jobs[0].(map[string]any), available[0].(map[string]any)
	if job["tracker"] != "rutor" || job["job"] != "ParseAllTask" || job["percent"] != float64(4) ||
		job["private"] != nil || option["tracker"] != "rutor" || option["job"] != jacredUpdateTasks {
		t.Fatal(report)
	}
	for _, target := range []jacredJobTarget{{Tracker: "rutor", Job: "ParseAllTask"}, {Tracker: "../maintenance", Job: jacredUpdateTasks}} {
		if code, _ := call(t, s, owner, "POST", path, target); code != 400 {
			t.Fatal(code, target)
		}
	}
	if code, body := call(t, s, owner, "POST", path, jacredJobTarget{Tracker: "nnmclub", Job: jacredUpdateTasks}); code != 409 || body["code"] != "jacred_job_disabled" {
		t.Fatal(code, body)
	}
	if code, body := call(t, s, owner, "POST", path, start); code != 202 || body["state"] != "started" {
		t.Fatal(code, body)
	}
	if code, body := call(t, s, owner, "POST", path, start); code != 409 || body["code"] != "jacred_job_busy" {
		t.Fatal(code, body)
	}
	jobList = `{"jobs":[]}`
	if code, body := call(t, s, owner, "GET", path, nil); code != 200 || len(body["jobs"].([]any)) != 0 {
		t.Fatal(code, body)
	}
	if starts != 2 {
		t.Fatal("Unexpected number of starts", starts)
	}
}

func TestJacredDiagnosticsIsAdminOnly(t *testing.T) {
	s, owner := fixture(t)
	dir := t.TempDir()
	s.Config.SecretKey = filepath.Join(dir, "secret.key")
	s.Config.Database = filepath.Join(dir, "state.db")
	for name, contents := range map[string]string{
		"jacred-access.json": `{"apiKey":"private-key","collect":true,"synchronized":false,"interval":120,"manual":true}`,
		"jacred-index.json":  `{"state":"complete","results":[{"tracker":"rutor","state":"http_error","httpCode":403}],"raw":"private-log"}`,
	} {
		if err := os.WriteFile(filepath.Join(dir, name), []byte(contents), 0600); err != nil {
			t.Fatal(err)
		}
	}
	const path = "admin/jacred"
	if code, _ := call(t, s, "", "GET", path, nil); code != 401 {
		t.Fatal(code)
	}
	if code, _ := call(t, s, "", "GET", path+"/stats", nil); code != 401 {
		t.Fatal(code)
	}
	if code, _ := call(t, s, "", "POST", path+"/stats", nil); code != 401 {
		t.Fatal(code)
	}
	code, invite := call(t, s, owner, "POST", "admin/users", map[string]string{"name": "viewer"})
	if code != 200 {
		t.Fatal(code, invite)
	}
	code, account := call(t, s, "", "POST", "auth/accept-invite", map[string]string{
		"invite": invite["invite"].(string), "password": "viewer-test-password", "device": "phone",
	})
	if code != 200 {
		t.Fatal(code, account)
	}
	calls := 0
	statsAvailable := true
	s.Client.Transport = roundTrip(func(r *http.Request) (*http.Response, error) {
		calls++
		if r.URL.Host != "127.0.0.1:19117" || r.URL.Query().Get("apikey") != "private-key" || r.Header.Get("Authorization") != "" {
			t.Fatal("Unexpected diagnostics request")
		}
		body := `{"results":[]}`
		switch r.URL.Path {
		case "/stats/torrents":
			if !statsAvailable {
				return &http.Response{StatusCode: 503, Body: io.NopCloser(strings.NewReader("offline"))}, nil
			}
			body = `[{"trackerName":"rutor","alltorrents":7,"private":"upstream-private-field"}]`
		case "/stats/meta":
			body = `{"updatedAt":"2026-11-25T16:45:00Z"}`
		case "/api/v2.0/indexers/all/results":
		default:
			t.Fatal("Unexpected diagnostics endpoint")
		}
		return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(body))}, nil
	})
	if code, _ := call(t, s, account["accessToken"].(string), "GET", path, nil); code != 403 || calls != 0 {
		t.Fatal(code, calls)
	}
	if code, _ := call(t, s, account["accessToken"].(string), "GET", path+"/stats", nil); code != 403 || calls != 0 {
		t.Fatal(code, calls)
	}
	if code, _ := call(t, s, account["accessToken"].(string), "POST", path+"/stats", nil); code != 403 || calls != 0 {
		t.Fatal(code, calls)
	}
	if code, _ := call(t, s, account["accessToken"].(string), "POST", path+"/collect", nil); code != 403 {
		t.Fatal(code)
	}
	request := filepath.Join(dir, "jacred-collect.request")
	if _, err := os.Stat(request); !os.IsNotExist(err) {
		t.Fatal(err)
	}
	for range 2 {
		if code, body := call(t, s, owner, "POST", path+"/collect", nil); code != 202 || body["collectQueued"] != true {
			t.Fatal(code, body)
		}
	}
	code, result := call(t, s, owner, "GET", path, nil)
	if code != 200 || calls != 3 || result["searchReady"] != true || result["catalogCount"] != float64(7) || result["collectQueued"] != true || result["statsUpdatedAt"] == nil {
		t.Fatal(code, calls, result)
	}
	row := result["index"].(map[string]any)["results"].([]any)[0].(map[string]any)
	if row["httpCode"] != float64(403) {
		t.Fatal(row)
	}
	payload, _ := json.Marshal(result)
	for _, value := range []string{"private-key", "private-log", "upstream-private-field"} {
		if bytes.Contains(payload, []byte(value)) {
			t.Fatal("Diagnostics exposed private upstream information")
		}
	}
	calls = 0
	code, stats := call(t, s, owner, "GET", path+"/stats", nil)
	if code != 200 || calls != 2 || stats["catalogCount"] != float64(7) || stats["searchReady"] != nil {
		t.Fatal(code, calls, stats)
	}
	statsAvailable = false
	if code, body := call(t, s, owner, "GET", path+"/stats", nil); code != 503 || body["code"] != "jacred_stats_unavailable" {
		t.Fatal(code, body)
	}
	calls = 0
	code, overview := call(t, s, owner, "GET", path+"?view=overview", nil)
	if code != 200 || calls != 1 || overview["separateCatalogStats"] != true ||
		overview["searchReady"] != true || overview["catalogCount"] != nil || overview["index"] == nil {
		t.Fatal(code, calls, overview)
	}
}

func TestJacredRefreshesCatalogWithCollectionDisabled(t *testing.T) {
	s, owner := fixture(t)
	dir := t.TempDir()
	s.Config.SecretKey = filepath.Join(dir, "secret.key")
	s.Config.Database = filepath.Join(dir, "state.db")
	if err := os.WriteFile(filepath.Join(dir, "jacred-access.json"), []byte(`{"apiKey":"private-key","collect":false}`), 0600); err != nil {
		t.Fatal(err)
	}
	entered := make(chan struct{}, 2)
	finish := make(chan struct{}, 1)
	defer close(finish)
	s.Client.Transport = roundTrip(func(r *http.Request) (*http.Response, error) {
		body := `{"updatedAt":"2026-11-25T16:45:00Z"}`
		switch r.URL.Path {
		case "/dev/TracksStats":
			if r.URL.Host != "127.0.0.1:19117" || r.URL.RawQuery != "refresh=true" || r.Header.Get("Authorization") != "" {
				t.Error("unexpected refresh request")
			}
			entered <- struct{}{}
			<-finish
			body = `{"ok":true}`
		case "/stats/torrents":
			body = `[{"trackerName":"rutor","alltorrents":7}]`
		case "/stats/meta":
		default:
			t.Errorf("unexpected upstream path %s", r.URL.Path)
		}
		return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(body))}, nil
	})
	const path = "admin/jacred/stats"
	for range 2 {
		if code, body := call(t, s, owner, "POST", path, nil); code != 202 || body["refreshState"] != "running" {
			t.Fatal(code, body)
		}
	}
	select {
	case <-entered:
	case <-time.After(2 * time.Second):
		t.Fatal("JacRed never received the refresh")
	}
	if code, body := call(t, s, owner, "GET", path, nil); code != 200 || body["refreshState"] != "running" {
		t.Fatal(code, body)
	}
	finish <- struct{}{}
	for deadline := time.Now().Add(2 * time.Second); ; time.Sleep(10 * time.Millisecond) {
		code, body := call(t, s, owner, "GET", path, nil)
		if code == 200 && body["refreshState"] == "complete" && body["catalogCount"] == float64(7) {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal(code, body)
		}
	}
	select {
	case <-entered:
		t.Fatal("duplicate refresh")
	default:
	}
	if _, err := os.Stat(filepath.Join(dir, "jacred-collect.request")); !os.IsNotExist(err) {
		t.Fatal("statistics refresh started collection", err)
	}
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
	for _, path := range []string{"admin/users", "admin/tmdb", "admin/settings", "admin/status", "admin/resources", "admin/torrserver/settings"} {
		code, _ := call(t, s, token, "GET", path, nil)
		if code != 403 {
			t.Fatal(path, code)
		}
	}
	if code, _ := call(t, s, "", "GET", "admin/resources", nil); code != 401 {
		t.Fatal("anonymous resource access", code)
	}
	if code, sample := call(t, s, owner, "GET", "admin/resources", nil); code != 200 ||
		sample["checkedAt"] == nil || sample["cpu"] == nil || sample["memory"] == nil || sample["disks"] == nil {
		t.Fatal("admin resource sample", code, sample)
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
