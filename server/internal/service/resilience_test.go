package service

import (
	"bytes"
	"context"
	"log"
	"strings"
	"testing"
	"time"
)

func TestLastSeenWritesAreThrottled(t *testing.T) {
	s, token := fixture(t)
	i, _ := s.identity(token)
	if _, err := s.DB.Exec(`CREATE TABLE seen_writes(n INTEGER); CREATE TRIGGER count_seen AFTER UPDATE OF last_seen ON devices BEGIN INSERT INTO seen_writes VALUES(1); END;`); err != nil {
		t.Fatal(err)
	}
	for n := 0; n < 2; n++ {
		if code, _ := call(t, s, token, "GET", "me", nil); code != 200 {
			t.Fatal(code)
		}
	}
	var writes int
	s.DB.QueryRow("SELECT COUNT(*) FROM seen_writes").Scan(&writes)
	if writes != 0 {
		t.Fatal("fresh device written", writes)
	}
	s.DB.Exec("UPDATE devices SET last_seen=? WHERE id=?", time.Now().Unix()-120, i.Device)
	s.DB.Exec("DELETE FROM seen_writes")
	for n := 0; n < 2; n++ {
		call(t, s, token, "GET", "me", nil)
	}
	s.DB.QueryRow("SELECT COUNT(*) FROM seen_writes").Scan(&writes)
	if writes != 1 {
		t.Fatal("stale device not throttled", writes)
	}
}

func admitTestStream(s *Server, user, session string) bool {
	view, _ := s.acquireStream(identity{User: user}, streamKey{session: session}, false)
	return view != nil
}

func releaseTestStream(s *Server, session string) {
	key := streamKey{session: session}
	s.releaseStream(key, s.streams[key])
}

func TestStreamAdmissionProtectsUpstreamAndLogsNoCredentials(t *testing.T) {
	s, token := fixture(t)
	s.maxStreams = 1
	if !admitTestStream(s, "other", "other-view") || admitTestStream(s, "third", "third-view") {
		t.Fatal("one-stream profile")
	}
	i, _ := s.identity(token)
	handle := Token()
	s.DB.Exec("INSERT INTO playback VALUES(?,?,?)", handle, i.User, "test-hash")
	var logs bytes.Buffer
	previous := log.Writer()
	log.SetOutput(&logs)
	defer log.SetOutput(previous)
	code, _ := call(t, s, token, "GET", "playback/"+handle+"/stream?file=17&secret=hidden", nil)
	if code != 429 {
		t.Fatal("overload contacted unconfigured upstream", code)
	}
	if !strings.Contains(logs.String(), "category=playback status=429") || strings.Contains(logs.String(), token) || strings.Contains(logs.String(), handle) || strings.Contains(logs.String(), "hidden") {
		t.Fatal("unsafe diagnostic", logs.String())
	}
	if !admitTestStream(s, "other", "other-view") || admitTestStream(s, "other", "other-view") {
		t.Fatal("seek overlap must allow exactly two requests")
	}
	releaseTestStream(s, "other-view")
	if admitTestStream(s, "third", "third-view") {
		t.Fatal("overlap released logical slot too early")
	}
	releaseTestStream(s, "other-view")
	if !admitTestStream(s, i.User, "owner-view") {
		t.Fatal("slot not released")
	}
	releaseTestStream(s, "owner-view")
	if len(s.streams) != 0 {
		t.Fatal("inactive counters retained")
	}
}

func TestMaintenanceExpiresOnlyEphemeralRecords(t *testing.T) {
	s, token := fixture(t)
	i, _ := s.identity(token)
	now := time.Now().UTC()
	s.DB.Exec("INSERT INTO cache VALUES('expired',x'00','image/jpeg',?)", now.Unix()-1)
	s.DB.Exec("INSERT INTO cache VALUES('fresh',x'00','image/jpeg',?)", now.Unix()+3600)
	s.DB.Exec("INSERT INTO mutations VALUES(?,'replay-id','{}')", i.User)
	s.DB.Exec("INSERT INTO changes(user_id,kind,key,value,revision,deleted,created) VALUES(?,'progress','movie','{}',1,0,?)", i.User, now.Unix()-86400)
	s.maintenanceTick(context.Background(), now)
	var count int
	s.DB.QueryRow("SELECT COUNT(*) FROM cache WHERE key='expired'").Scan(&count)
	if count != 0 {
		t.Fatal("expired cache retained")
	}
	for _, table := range []string{"cache", "changes", "mutations"} {
		if err := s.DB.QueryRow("SELECT COUNT(*) FROM " + table).Scan(&count); err != nil || count != 1 {
			t.Fatal("lost durable data", table, count, err)
		}
	}
	rows, err := s.DB.Query("EXPLAIN QUERY PLAN SELECT kind FROM changes WHERE user_id=? AND cursor>? ORDER BY cursor LIMIT 33", i.User, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	indexed := false
	for rows.Next() {
		var id, parent, unused int
		var detail string
		rows.Scan(&id, &parent, &unused, &detail)
		indexed = indexed || strings.Contains(detail, "changes_user_cursor")
	}
	if !indexed {
		t.Fatal("sync scans full journal")
	}
}
