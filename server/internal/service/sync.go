package service

import (
	"database/sql"
	"encoding/json"
	"net/http"

	"strconv"
	"time"
)

type Mutation struct {
	MutationID   string          `json:"mutationId"`
	Kind         string          `json:"kind"`
	Key          string          `json:"entityKey"`
	Base         int64           `json:"baseRevision"`
	Operation    string          `json:"operation"`
	Value        json.RawMessage `json:"value"`
	ServerEpoch  string          `json:"serverEpoch"`
	HistoryEpoch int64           `json:"historyEpoch"`
	PlaySession  string          `json:"playSession"`
	Sequence     int64           `json:"sequence"`
}
type Entity struct {
	Kind     string          `json:"kind"`
	Key      string          `json:"entityKey"`
	Value    json.RawMessage `json:"value"`
	Revision int64           `json:"revision"`
	Deleted  bool            `json:"deleted"`
	Cursor   int64           `json:"cursor,omitempty"`
}

func (s *Server) mutate(w http.ResponseWriter, r *http.Request, i identity) {
	var m Mutation
	if !decode(w, r, &m) {
		return
	}
	if len(m.MutationID) < 16 || len(m.MutationID) > 128 || (len(m.Key) == 0 || len(m.Key) > 1024) || (m.Kind != "opening" && m.Kind != "progress" && m.Kind != "favorite" && m.Kind != "preferences") || (m.Operation != "put" && m.Operation != "delete" && m.Operation != "restore") || len(m.Value) > 65536 || !validEntity(m.Kind, m.Value, m.Operation) {
		fail(w, 400, "invalid_mutation")
		return
	}
	if m.ServerEpoch != value(s.DB, "server_epoch") {
		fail(w, 409, "resync_required")
		return
	}
	overQuota := s.quotaExceeded()
	tx, e := s.DB.Begin()
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	var previous string
	e = tx.QueryRow("SELECT result FROM mutations WHERE user_id=? AND id=?", i.User, m.MutationID).Scan(&previous)
	if e == nil {
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(previous))
		return
	}

	var epoch int64
	if e = tx.QueryRow("SELECT epoch FROM users WHERE id=?", i.User).Scan(&epoch); e != nil {
		fail(w, 401, "login_required")
		return
	}
	if (m.Kind == "opening" || m.Kind == "progress") && m.HistoryEpoch != epoch {
		fail(w, 409, "history_epoch_changed")
		return
	}
	current := Entity{Kind: m.Kind, Key: m.Key, Value: json.RawMessage(`{}`)}
	var rawValue string
	var deleted int
	var play string
	var seq int64
	e = tx.QueryRow("SELECT value,revision,deleted,play_session,sequence FROM entities WHERE user_id=? AND kind=? AND key=?", i.User, m.Kind, m.Key).Scan(&rawValue, &current.Revision, &deleted, &play, &seq)
	if e == nil {
		current.Value = json.RawMessage(rawValue)
	}
	current.Deleted = deleted != 0
	if m.Operation == "delete" && current.Revision == 0 {
		fail(w, 404, "entity_missing")
		return
	}
	if overQuota && !(m.Operation == "delete" && current.Revision > 0 && !current.Deleted) {
		fail(w, 507, "database_quota")
		return
	}
	if e != nil && e != sql.ErrNoRows {
		fail(w, 503, "database_unavailable")
		return
	}
	if m.Kind == "progress" && m.PlaySession != "" && m.PlaySession == play && m.Sequence <= seq {
		out := map[string]any{"entity": current, "ignored": true}
		raw, _ := json.Marshal(out)
		_, e = tx.Exec("INSERT INTO mutations VALUES(?,?,?)", i.User, m.MutationID, string(raw))
		if e == nil {
			e = tx.Commit()
		}
		if e != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		respond(w, 200, out)
		return
	}
	if m.Base != current.Revision || (current.Deleted && m.Operation != "restore") {
		respond(w, 409, map[string]any{"code": "conflict", "entity": current})
		return
	}
	current.Revision++
	current.Value = m.Value
	if m.Kind == "progress" && m.Operation != "delete" {
		var payload map[string]any
		if json.Unmarshal(m.Value, &payload) != nil {
			fail(w, 400, "invalid_entity")
			return
		}
		var device string
		if tx.QueryRow("SELECT name FROM devices WHERE id=? AND user_id=?", i.Device, i.User).Scan(&device) != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		payload["deviceName"] = device
		current.Value, _ = json.Marshal(payload)
	}
	current.Deleted = m.Operation == "delete"
	if current.Deleted {
		current.Value = json.RawMessage(`{}`)
	}
	if _, e = tx.Exec(`INSERT INTO entities VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(user_id,kind,key) DO UPDATE SET value=excluded.value,revision=excluded.revision,deleted=excluded.deleted,play_session=excluded.play_session,sequence=excluded.sequence`, i.User, m.Kind, m.Key, string(current.Value), current.Revision, current.Deleted, m.PlaySession, m.Sequence); e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	res, e := tx.Exec("INSERT INTO changes(user_id,kind,key,value,revision,deleted,created) VALUES(?,?,?,?,?,?,?)", i.User, m.Kind, m.Key, string(current.Value), current.Revision, current.Deleted, time.Now().Unix())
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	current.Cursor, _ = res.LastInsertId()
	out := map[string]any{"entity": current}
	raw, _ := json.Marshal(out)
	if _, e = tx.Exec("INSERT INTO mutations VALUES(?,?,?)", i.User, m.MutationID, string(raw)); e == nil {
		e = tx.Commit()
	}
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	respond(w, 200, out)
}
func validEntity(kind string, b json.RawMessage, op string) bool {
	if op == "delete" {
		return true
	}
	var v map[string]any
	if json.Unmarshal(b, &v) != nil || v == nil {
		return false
	}
	allowed := map[string]bool{}
	switch kind {
	case "opening", "favorite":
		for _, k := range []string{"id", "title", "source", "openedAt", "bookmarked", "metadataId", "hash"} {
			allowed[k] = true
		}
	case "progress":
		for _, k := range []string{"key", "releaseId", "fileId", "path", "positionMs", "durationMs", "updatedAt", "completed", "hash", "size"} {
			allowed[k] = true
		}
		pos, ok := v["positionMs"].(float64)
		if !ok || pos < 0 || pos > 1e12 {
			return false
		}
	case "preferences":
		for _, k := range []string{"audioLanguage", "subtitleLanguage", "quality"} {
			allowed[k] = true
		}
	}
	for k, val := range v {
		switch val.(type) {
		case nil, string, float64, bool:
		default:
			return false
		}
		if !allowed[k] {
			return false
		}
		if text, ok := val.(string); ok && len(text) > 2048 {
			return false
		}
	}
	return true
}
func (s *Server) pull(w http.ResponseWriter, r *http.Request, i identity, snapshot bool) {
	if snapshot {
		s.snapshot(w, r, i)
		return
	}
	epoch := value(s.DB, "server_epoch")
	if !snapshot && r.URL.Query().Get("serverEpoch") != epoch {
		fail(w, 409, "resync_required")
		return
	}
	cursor, e := strconv.ParseInt(r.URL.Query().Get("cursor"), 10, 64)
	if !snapshot && (e != nil || cursor < 0) {
		fail(w, 400, "invalid_cursor")
		return
	}
	tx, e := s.DB.Begin()
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	var watermark int64
	tx.QueryRow("SELECT COALESCE(MAX(cursor),0) FROM changes").Scan(&watermark)
	query := "SELECT kind,key,value,revision,deleted,0 FROM entities WHERE user_id=? ORDER BY kind,key"
	args := []any{i.User}
	if !snapshot {
		query = "SELECT kind,key,value,revision,deleted,cursor FROM changes WHERE user_id=? AND cursor>? ORDER BY cursor LIMIT 33"
		args = append(args, cursor)
	}
	rows, e := tx.Query(query, args...)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	out := []Entity{}
	more := false
	for rows.Next() {
		if !snapshot && len(out) == 32 {
			more = true
			break
		}
		var v Entity
		var rawValue string
		var d int
		if rows.Scan(&v.Kind, &v.Key, &rawValue, &v.Revision, &d, &v.Cursor) != nil {
			rows.Close()
			fail(w, 503, "database_unavailable")
			return
		}
		v.Value = json.RawMessage(rawValue)
		v.Deleted = d != 0
		out = append(out, v)
		if !snapshot {
			cursor = v.Cursor
		}
	}
	rows.Close()
	if snapshot || !more {
		cursor = watermark
	}
	tx.Commit()
	respond(w, 200, map[string]any{"entities": out, "cursor": cursor, "more": more, "serverEpoch": epoch, "historyEpoch": i.Epoch})
}
func (s *Server) clearHistory(w http.ResponseWriter, r *http.Request, i identity) {
	if !s.limit("clear-history:"+i.User, 5, time.Minute) {
		fail(w, 429, "rate_limited")
		return
	}
	tx, e := s.DB.Begin()
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	_, e = tx.Exec("UPDATE users SET epoch=epoch+1 WHERE id=?", i.User)
	if e == nil {
		_, e = tx.Exec("DELETE FROM snapshots WHERE user_id=?", i.User)
	}
	if e == nil {
		_, e = tx.Exec("UPDATE entities SET deleted=1,value='{}',revision=revision+1 WHERE user_id=? AND deleted=0 AND kind IN ('opening','progress')", i.User)
	}
	if e == nil {
		_, e = tx.Exec("INSERT INTO changes(user_id,kind,key,value,revision,deleted,created) SELECT e.user_id,e.kind,e.key,e.value,e.revision,e.deleted,? FROM entities e WHERE e.user_id=? AND e.kind IN ('opening','progress') AND NOT EXISTS (SELECT 1 FROM changes c WHERE c.user_id=e.user_id AND c.kind=e.kind AND c.key=e.key AND c.revision=e.revision)", time.Now().Unix(), i.User)
	}
	if e == nil {
		e = tx.Commit()
	}
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	respond(w, 200, map[string]any{"historyEpoch": i.Epoch + 1})
}
