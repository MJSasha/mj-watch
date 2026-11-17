package service

import (
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// Receiver observations, not accepted commands, determine playback state.
type RemoteState struct {
	Session    string `json:"session"`
	ReleaseID  string `json:"releaseId"`
	MetadataID string `json:"metadataId"`
	ContentID  string `json:"contentId"`
	Title      string `json:"title"`
	Status     string `json:"status"`
	Position   int64  `json:"position"`
	Duration   int64  `json:"duration"`
	Seek       bool   `json:"seek"`
	Volume     int    `json:"volume"`
	VolumeMax  int    `json:"volumeMax"`
}
type RemoteCommand struct {
	ID         string `json:"id"`
	Instance   string `json:"instance"`
	Session    string `json:"session"`
	Action     string `json:"action"`
	Handle     string `json:"handle,omitempty"`
	FileID     int    `json:"fileId,omitempty"`
	Title      string `json:"title,omitempty"`
	ReleaseID  string `json:"releaseId,omitempty"`
	ContentID  string `json:"contentId,omitempty"`
	Position   int64  `json:"position,omitempty"`
	Volume     int    `json:"volume,omitempty"`
	Created    int64  `json:"created,omitempty"`
	Controller string `json:"controller,omitempty"`
	MetadataID string `json:"metadataId,omitempty"`
}

func (s *Server) remote(w http.ResponseWriter, r *http.Request, path string, i identity) {
	// ponytail: short DB operations share one lock; use per-receiver locks if contention matters.
	s.remoteMu.Lock()
	defer s.remoteMu.Unlock()
	if path == "remote/receiver/open" && r.Method == "POST" {
		var kind string
		if s.DB.QueryRow("SELECT type FROM device_profiles WHERE device_id=?", i.Device).Scan(&kind) != nil || kind != "tv" {
			fail(w, 403, "tv_required")
			return
		}
		instance := Token()
		idle, _ := json.Marshal(RemoteState{Status: "idle"})
		_, err := s.DB.Exec("INSERT INTO remote_receivers(device_id,instance,heartbeat,state) VALUES(?,?,0,?) ON CONFLICT(device_id) DO UPDATE SET instance=excluded.instance,heartbeat=0,state=excluded.state,command='{}'", i.Device, instance, string(idle))
		if err != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		respond(w, 200, map[string]string{"instance": instance})
		return
	}
	if path == "remote/receiver" && r.Method == "POST" {
		var v struct {
			Instance string      `json:"instance"`
			Ack      string      `json:"ack"`
			Ready    bool        `json:"ready"`
			State    RemoteState `json:"state"`
		}
		if !decodeLimit(w, r, &v, 8192) {
			return
		}
		if !validRemoteState(v.State) {
			fail(w, 400, "invalid_state")
			return
		}
		var instance, raw string
		if s.DB.QueryRow("SELECT instance,command FROM remote_receivers WHERE device_id=?", i.Device).Scan(&instance, &raw) != nil || instance != v.Instance {
			fail(w, 409, "receiver_changed")
			return
		}
		var command RemoteCommand
		json.Unmarshal([]byte(raw), &command)
		var allowed int
		if command.ID != "" {
			s.DB.QueryRow("SELECT COUNT(*) FROM devices d JOIN users u ON u.id=d.user_id WHERE d.id=? AND d.user_id=? AND d.revoked=0 AND u.blocked=0", command.Controller, i.User).Scan(&allowed)
		}
		if command.ID == v.Ack || command.Created < time.Now().Unix()-8 || allowed == 0 {
			raw = "{}"
		}
		data, _ := json.Marshal(v.State)
		heartbeat := int64(0)
		if v.Ready {
			heartbeat = time.Now().Unix()
		}
		_, err := s.DB.Exec("UPDATE remote_receivers SET heartbeat=?,state=?,command=? WHERE device_id=?", heartbeat, string(data), raw, i.Device)
		if err != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		respond(w, 200, map[string]any{"command": json.RawMessage(raw)})
		return
	}
	parts := strings.Split(path, "/")
	if len(parts) < 2 || len(parts) > 3 {
		fail(w, 404, "not_found")
		return
	}
	target := parts[1]
	var instance, name, raw, queued, kind string
	var heartbeat int64
	err := s.DB.QueryRow("SELECT r.instance,d.name,r.heartbeat,r.state,r.command,p.type FROM remote_receivers r JOIN devices d ON d.id=r.device_id JOIN device_profiles p ON p.device_id=d.id WHERE d.id=? AND d.user_id=? AND d.revoked=0", target, i.User).Scan(&instance, &name, &heartbeat, &raw, &queued, &kind)
	if err != nil {
		fail(w, 404, "receiver_not_found")
		return
	}
	if kind != "tv" || heartbeat <= time.Now().Unix()-8 {
		fail(w, 409, "receiver_offline")
		return
	}
	var queuedCommand RemoteCommand
	json.Unmarshal([]byte(queued), &queuedCommand)
	pendingID := ""
	if queuedCommand.Created >= time.Now().Unix()-8 {
		pendingID = queuedCommand.ID
	}
	snapshot := map[string]any{"deviceId": target, "name": name, "instance": instance, "state": json.RawMessage(raw), "pendingId": pendingID}
	if len(parts) == 3 && parts[2] == "connect" && r.Method == "POST" {
		respond(w, 200, snapshot)
		return
	}
	if len(parts) == 2 && r.Method == "GET" {
		if r.URL.Query().Get("instance") != instance {
			fail(w, 409, "receiver_changed")
			return
		}
		respond(w, 200, snapshot)
		return
	}
	if len(parts) != 3 || parts[2] != "commands" || r.Method != "POST" {
		fail(w, 404, "not_found")
		return
	}
	var c RemoteCommand
	if !decodeLimit(w, r, &c, 8192) {
		return
	}
	if len(c.ID) < 16 || len(c.ID) > 128 || c.Instance != instance {
		fail(w, 409, "receiver_changed")
		return
	}
	var exists int
	if s.DB.QueryRow("SELECT COUNT(*) FROM remote_receipts WHERE device_id=? AND request_id=?", target, c.ID).Scan(&exists) != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	if exists > 0 {
		respond(w, 200, map[string]any{"accepted": true, "duplicate": true})
		return
	}
	var state RemoteState
	json.Unmarshal([]byte(raw), &state)
	if c.Action == "load" {
		var hash string
		if s.DB.QueryRow("SELECT hash FROM playback WHERE id=? AND user_id=?", c.Handle, i.User).Scan(&hash) != nil || c.FileID < 0 || c.Position < 0 || len(c.Title) > 1024 || len(c.ReleaseID) > 2048 || len(c.MetadataID) > 128 {
			fail(w, 400, "invalid_media")
			return
		}
		c.ContentID = hash + ":" + strconv.Itoa(c.FileID)
		if state.ContentID == c.ContentID && state.Session != "" && state.Status != "ended" && state.Status != "error" {
			respond(w, 200, map[string]any{"attached": true})
			return
		}
	} else {
		if state.Session == "" || state.Status == "idle" || state.Status == "ended" {
			fail(w, 409, "session_changed")
			return
		}
		switch c.Action {
		case "play", "pause", "stop":
		case "seek":
			if !state.Seek || c.Position < 0 || c.Position > state.Duration {
				fail(w, 400, "seek_unavailable")
				return
			}
		case "volume":
			if state.VolumeMax <= 0 || c.Volume < 0 || c.Volume > state.VolumeMax {
				fail(w, 400, "volume_unavailable")
				return
			}
		default:
			fail(w, 400, "invalid_command")
			return
		}
	}
	if c.Session != state.Session {
		respond(w, 409, map[string]any{"code": "session_changed", "state": state, "name": name})
		return
	}
	var pending RemoteCommand
	json.Unmarshal([]byte(queued), &pending)
	if pending.ID != "" && pending.Created >= time.Now().Unix()-8 {
		fail(w, 409, "command_pending")
		return
	}
	c.Controller = i.Device
	c.Created = time.Now().Unix()
	data, _ := json.Marshal(c)
	tx, err := s.DB.Begin()
	if err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	_, err = tx.Exec("INSERT INTO remote_receipts VALUES(?,?,?)", target, c.ID, c.Created)
	if err == nil {
		_, err = tx.Exec("UPDATE remote_receivers SET command=? WHERE device_id=?", string(data), target)
	}
	if err == nil {
		_, err = tx.Exec("DELETE FROM remote_receipts WHERE created<?", time.Now().Unix()-86400)
	}
	if err == nil {
		err = tx.Commit()
	}
	if err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	respond(w, 200, map[string]any{"accepted": true})
}
func validRemoteState(v RemoteState) bool {
	if len(v.Session) > 128 || len(v.ContentID) > 2048 || len(v.Title) > 1024 || len(v.ReleaseID) > 2048 || len(v.MetadataID) > 128 || v.Position < 0 || v.Duration < 0 || v.Volume < 0 || v.VolumeMax < 0 || v.Volume > v.VolumeMax {
		return false
	}
	switch v.Status {
	case "idle", "loading", "playing", "paused", "ended", "error":
		return true
	}
	return false
}
