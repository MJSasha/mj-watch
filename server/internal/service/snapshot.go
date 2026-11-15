package service

import (
	"encoding/json"
	"net/http"
	"strconv"
	"time"
)

func (s *Server) snapshot(w http.ResponseWriter, r *http.Request, i identity) {
	if r.URL.Query().Get("fresh") == "1" && !s.limit("fresh-snapshot:"+i.User, 6, time.Minute) {
		fail(w, 429, "rate_limited")
		return
	}
	id := r.URL.Query().Get("snapshotId")
	offset := 0
	if raw := r.URL.Query().Get("offset"); raw != "" {
		n, e := strconv.Atoi(raw)
		if e != nil || n < 0 {
			fail(w, 400, "snapshot_offset")
			return
		}
		offset = n
	}
	tx, e := s.DB.Begin()
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	var watermark, history int64
	var epoch string
	if id == "" && r.URL.Query().Get("fresh") != "1" {
		tx.QueryRow("SELECT id FROM snapshots WHERE user_id=? AND created>? LIMIT 1", i.User, time.Now().Unix()-300).Scan(&id)
	}
	if id == "" {
		tx.Exec("DELETE FROM snapshots WHERE user_id=?", i.User)
		id = Token()
		tx.Exec("DELETE FROM snapshots WHERE created<?", time.Now().Unix()-3600)
		tx.QueryRow("SELECT COALESCE(MAX(cursor),0) FROM changes").Scan(&watermark)
		tx.QueryRow("SELECT epoch FROM users WHERE id=?", i.User).Scan(&history)
		tx.QueryRow("SELECT value FROM settings WHERE key='server_epoch'").Scan(&epoch)
		if _, e = tx.Exec("INSERT INTO snapshots VALUES(?,?,?,?,?,?)", id, i.User, watermark, history, epoch, time.Now().Unix()); e == nil {
			_, e = tx.Exec(`INSERT INTO snapshot_entities SELECT ?,ROW_NUMBER() OVER(ORDER BY kind,key)-1,kind,key,value,revision,deleted FROM entities WHERE user_id=?`, id, i.User)
		}
		if e != nil {
			fail(w, 507, "snapshot_storage")
			return
		}
	} else {
		if e = tx.QueryRow("SELECT watermark,history_epoch,server_epoch FROM snapshots WHERE id=? AND user_id=? AND created>?", id, i.User, time.Now().Unix()-3600).Scan(&watermark, &history, &epoch); e != nil {
			fail(w, 409, "resync_required")
			return
		}
	}
	rows, e := tx.Query("SELECT kind,key,value,revision,deleted FROM snapshot_entities WHERE snapshot_id=? AND ordinal>=? ORDER BY ordinal LIMIT 33", id, offset)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	out := []Entity{}
	more := false
	for rows.Next() {
		if len(out) == 32 {
			more = true
			break
		}
		var v Entity
		var raw string
		var d bool
		if e = rows.Scan(&v.Kind, &v.Key, &raw, &v.Revision, &d); e != nil {
			rows.Close()
			fail(w, 503, "database_unavailable")
			return
		}
		v.Value = json.RawMessage(raw)
		v.Deleted = d
		out = append(out, v)
	}
	rows.Close()

	if e = tx.Commit(); e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	respond(w, 200, map[string]any{"snapshotId": id, "nextOffset": offset + len(out), "entities": out, "cursor": watermark, "historyEpoch": history, "serverEpoch": epoch, "more": more})
}
