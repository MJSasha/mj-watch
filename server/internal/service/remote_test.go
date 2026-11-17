package service

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func sameUserDevice(t *testing.T, s *Server, token, name string) (string, string) {
	t.Helper()
	i, _ := s.identity(token)
	tx, e := s.DB.Begin()
	if e != nil {
		t.Fatal(e)
	}
	defer tx.Rollback()
	out, e := issue(tx, i.User, name)
	if e != nil {
		t.Fatal(e)
	}
	if e = tx.Commit(); e != nil {
		t.Fatal(e)
	}
	return out["accessToken"].(string), out["deviceId"].(string)
}
func TestDeviceAliasesAndTypes(t *testing.T) {
	s, owner := fixture(t)
	_, invite := call(t, s, owner, "POST", "admin/users", map[string]string{"name": "viewer"})
	_, login := call(t, s, "", "POST", "auth/accept-invite", map[string]string{"invite": invite["invite"].(string), "password": "correct-horse-battery", "device": "phone"})
	user := login["accessToken"].(string)
	tv, id := sameUserDevice(t, s, user, "TV")
	if code, _ := call(t, s, tv, "PUT", "devices/self/profile", map[string]string{"type": "tv"}); code != 200 {
		t.Fatal(code)
	}
	for _, name := range []string{"", "  ", strings.Repeat("😀", 33), "line\nbreak", "tab\tname"} {
		if code, _ := call(t, s, user, "PATCH", "devices/"+id, map[string]string{"name": name}); code != 400 {
			t.Fatal(name, code)
		}
	}
	name := strings.Repeat("😀", 32)
	if code, result := call(t, s, user, "PATCH", "devices/"+id, map[string]string{"name": " " + name + " "}); code != 200 || result["name"] != name {
		t.Fatal(code, result)
	}
	// Even the server owner cannot rename another account's TV through this API.
	if code, _ := call(t, s, owner, "PATCH", "devices/"+id, map[string]string{"name": "stolen"}); code != 404 {
		t.Fatal(code)
	}
	_, list := call(t, s, user, "GET", "devices", nil)
	if len(list["devices"].([]any)) != 2 {
		t.Fatal(list)
	}
	call(t, s, user, "DELETE", "devices/"+id, nil)
	if code, _ := call(t, s, user, "PATCH", "devices/"+id, map[string]string{"name": "revoked"}); code != 404 {
		t.Fatal(code)
	}
}
func TestRemoteTargetSessionAndReceiverTruth(t *testing.T) {
	s, phone := fixture(t)
	tv, id := sameUserDevice(t, s, phone, "Living room")
	tv2, id2 := sameUserDevice(t, s, phone, "Bedroom")
	open := func(token string) string {
		call(t, s, token, "PUT", "devices/self/profile", map[string]string{"type": "tv"})
		code, v := call(t, s, token, "POST", "remote/receiver/open", nil)
		if code != 200 {
			t.Fatal(code, v)
		}
		return v["instance"].(string)
	}
	instance := open(tv)
	instance2 := open(tv2)
	report := func(token, instance, ack string, state RemoteState) map[string]any {
		code, v := call(t, s, token, "POST", "remote/receiver", map[string]any{"instance": instance, "ack": ack, "ready": true, "state": state})
		if code != 200 {
			t.Fatal(code, v)
		}
		return v
	}
	idle := RemoteState{Status: "idle"}
	report(tv, instance, "", idle)
	report(tv2, instance2, "", idle)
	i, _ := s.identity(phone)
	s.DB.Exec("INSERT INTO playback VALUES('handle',?,'content-hash')", i.User)
	_, connected := call(t, s, phone, "POST", "remote/"+id+"/connect", nil)
	if connected["instance"] != instance {
		t.Fatal(connected)
	}
	c := RemoteCommand{ID: Token(), Instance: instance, Action: "load", Handle: "handle", FileID: 1, Title: "Movie"}
	c.Position = -1
	if code, _ := call(t, s, phone, "POST", "remote/"+id+"/commands", c); code != 400 {
		t.Fatal("negative resume position", code)
	}
	c.Position = 0
	if code, v := call(t, s, phone, "POST", "remote/"+id+"/commands", c); code != 200 || v["accepted"] != true {
		t.Fatal(code, v)
	}
	// Delivery is not playback; stale reports must not turn an accepted load into playing.
	_, observed := call(t, s, phone, "GET", "remote/"+id+"?instance="+instance, nil)
	if observed["state"].(map[string]any)["status"] != "idle" {
		t.Fatal(observed)
	}
	if observed["pendingId"] != c.ID {
		t.Fatal("command receipt was mistaken for receiver acknowledgment", observed)
	}
	delivery := report(tv, instance, "", idle)
	if ttl := delivery["commandTtlMs"].(float64); ttl <= 0 || ttl > 8000 {
		t.Fatal("invalid monotonic receiver budget", delivery)
	}
	queued := delivery["command"].(map[string]any)
	if queued["id"] != c.ID || queued["contentId"] != "content-hash:1" {
		t.Fatal(queued)
	}
	if len(report(tv2, instance2, "", idle)["command"].(map[string]any)) != 0 {
		t.Fatal("command delivered to second TV")
	}
	playing := RemoteState{Session: c.ID, ContentID: "content-hash:1", Title: "Movie", Status: "playing", Position: 36000, Duration: 120000, Seek: true, Volume: 5, VolumeMax: 15}
	report(tv, instance, c.ID, playing)
	_, acknowledged := call(t, s, phone, "GET", "remote/"+id+"?instance="+instance, nil)
	if acknowledged["pendingId"] != "" {
		t.Fatal("receiver did not acknowledge command", acknowledged)
	}
	// Retries and another controller joining the same file cannot restart it.
	if code, v := call(t, s, phone, "POST", "remote/"+id+"/commands", c); code != 200 || v["duplicate"] != true {
		t.Fatal(code, v)
	}
	c.ID = Token()
	if code, v := call(t, s, phone, "POST", "remote/"+id+"/commands", c); code != 200 || v["attached"] != true {
		t.Fatal(code, v)
	}
	c.FileID = 2
	c.ID = Token()
	if code, v := call(t, s, phone, "POST", "remote/"+id+"/commands", c); code != 409 || v["code"] != "session_changed" {
		t.Fatal(code, v)
	}
	// Explicitly confirmed replacement is a CAS, so a stale confirmation cannot replace new content.
	c.Session = playing.Session
	playing.Session = Token()
	report(tv, instance, "", playing)
	if code, _ := call(t, s, phone, "POST", "remote/"+id+"/commands", c); code != 409 {
		t.Fatal(code)
	}
	c.Action = "pause"
	c.Session = playing.Session
	c.ID = Token()
	if code, _ := call(t, s, phone, "POST", "remote/"+id+"/commands", c); code != 200 {
		t.Fatal(code)
	}
	report(tv, instance, c.ID, playing)
	// Selecting another target and dropping the controller send no stop command.
	call(t, s, phone, "POST", "remote/"+id2+"/connect", nil)
	if len(report(tv, instance, "", playing)["command"].(map[string]any)) != 0 {
		t.Fatal("selection stopped old TV")
	}
	s.DB.Exec("UPDATE remote_receivers SET heartbeat=? WHERE device_id=?", time.Now().Unix()-9, id)
	if code, _ := call(t, s, phone, "POST", "remote/"+id+"/connect", nil); code != 409 {
		t.Fatal(code)
	}
	report(tv, instance, "", playing)
	call(t, s, phone, "DELETE", "devices/"+id, nil)
	if code, _ := call(t, s, phone, "GET", "remote/"+id+"?instance="+instance, nil); code != 404 {
		t.Fatal(code)
	}
	if code, _ := call(t, s, tv, "POST", "remote/receiver", map[string]any{"instance": instance, "state": playing}); code != 401 {
		t.Fatal(code)
	}
	// No unauthenticated access, even if IDs/instances are known.
	if code, _ := call(t, s, "", "GET", "remote/"+id2+"?instance="+instance2, nil); code != 401 {
		t.Fatal(code)
	}
	_, other := call(t, s, phone, "POST", "admin/users", map[string]string{"name": "other"})
	_, foreign := call(t, s, "", "POST", "auth/accept-invite", map[string]string{"invite": other["invite"].(string), "password": "correct-horse-battery", "device": "other"})
	if code, _ := call(t, s, foreign["accessToken"].(string), "POST", "remote/"+id2+"/connect", nil); code != 404 {
		t.Fatal(code)
	}
	b, _ := json.Marshal(playing)
	if !json.Valid(b) {
		t.Fatal("bad state")
	}
}

func TestRemoteRestartRevokedControllerAndCommandBoundaries(t *testing.T) {
	s, phone := fixture(t)
	tv, id := sameUserDevice(t, s, phone, "TV")
	controller, controllerID := sameUserDevice(t, s, phone, "Second phone")
	call(t, s, tv, "PUT", "devices/self/profile", map[string]string{"type": "tv"})
	if code, _ := call(t, s, phone, "POST", "remote/receiver/open", nil); code != 403 {
		t.Fatal("phone became a receiver", code)
	}
	_, opened := call(t, s, tv, "POST", "remote/receiver/open", nil)
	instance := opened["instance"].(string)
	report := func(ack string, state RemoteState) map[string]any {
		code, v := call(t, s, tv, "POST", "remote/receiver", map[string]any{"instance": instance, "ack": ack, "ready": true, "state": state})
		if code != 200 {
			t.Fatal(code, v)
		}
		return v
	}
	state := RemoteState{Status: "playing", Session: Token(), ContentID: "content:1", Title: "Movie", Position: 12000, Duration: 90000, Seek: false, Volume: 5, VolumeMax: 15}
	report("", state)
	if code, joined := call(t, s, controller, "POST", "remote/"+id+"/connect", nil); code != 200 || joined["state"].(map[string]any)["session"] != state.Session {
		t.Fatal("second controller join", code, joined)
	}
	command := RemoteCommand{ID: Token(), Instance: instance, Session: state.Session, Action: "seek", Position: 0}
	if code, _ := call(t, s, phone, "POST", "remote/"+id+"/commands", command); code != 400 {
		t.Fatal("unsupported seek", code)
	}
	command.Action = "volume"
	command.Volume = 16
	if code, _ := call(t, s, phone, "POST", "remote/"+id+"/commands", command); code != 400 {
		t.Fatal("volume range", code)
	}
	if code, _ := call(t, s, phone, "POST", "remote/"+id+"/commands", map[string]any{"id": Token(), "uri": "http://secret.invalid/video"}); code != 400 {
		t.Fatal("untrusted URL accepted", code)
	}
	command.Action = "pause"
	if code, _ := call(t, s, controller, "POST", "remote/"+id+"/commands", command); code != 200 {
		t.Fatal(code)
	}
	call(t, s, phone, "DELETE", "devices/"+controllerID, nil)
	if cmd := report("", state)["command"].(map[string]any); len(cmd) != 0 {
		t.Fatal("revoked controller's queued command was delivered", cmd)
	}
	command.ID = Token()
	if code, _ := call(t, s, phone, "POST", "remote/"+id+"/commands", command); code != 200 {
		t.Fatal(code)
	}
	// A receiver restart fences both previous controller commands and old receiver heartbeats.
	_, fresh := call(t, s, tv, "POST", "remote/receiver/open", nil)
	if fresh["instance"] == instance {
		t.Fatal("instance reused")
	}
	if code, _ := call(t, s, tv, "POST", "remote/receiver", map[string]any{"instance": instance, "ready": true, "state": state}); code != 409 {
		t.Fatal("old receiver accepted", code)
	}
	instance = fresh["instance"].(string)
	report("", RemoteState{Status: "idle"})
	if code, _ := call(t, s, phone, "POST", "remote/"+id+"/commands", command); code != 409 {
		t.Fatal("old command after restart", code)
	}
	// Background/offline receivers keep their capability, but are not available to launch.
	call(t, s, tv, "POST", "remote/receiver", map[string]any{"instance": instance, "ready": false, "state": RemoteState{Status: "idle"}})
	_, devices := call(t, s, phone, "GET", "devices", nil)
	for _, item := range devices["devices"].([]any) {
		d := item.(map[string]any)
		if d["id"] == id && (d["available"] != false || d["remoteVideo"] != true) {
			t.Fatal(d)
		}
	}
}
