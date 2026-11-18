package service

import (
	"context"
	"dev.mjwatch/server/internal/resources"
	"encoding/json"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"syscall"
	"time"
)

type Retention struct {
	HistoryDays      int   `json:"historyDays"`
	DatabaseLimitMiB int64 `json:"databaseLimitMiB"`
	BackupKeep       int   `json:"backupKeep"`
	BackupHourUTC    int   `json:"backupHourUTC"`
	BackupEnabled    bool  `json:"backupEnabled"`
}

func (s *Server) retention() Retention {
	v := Retention{DatabaseLimitMiB: 1024, BackupKeep: 7, BackupHourUTC: 3}
	json.Unmarshal([]byte(value(s.DB, "retention")), &v)
	return v
}
func (s *Server) retentionAPI(w http.ResponseWriter, r *http.Request) {
	current := s.retention()
	raw, _ := json.Marshal(current)
	revision := Hash(string(raw))
	if r.Method == "GET" {
		respond(w, 200, map[string]any{"revision": revision, "settings": current, "backupCredentialConfigured": value(s.DB, "backup_passphrase") != ""})
		return
	}
	if r.Method != "PATCH" {
		fail(w, 405, "method_not_allowed")
		return
	}
	var v struct {
		HistoryDays      int    `json:"historyDays"`
		DatabaseLimitMiB int64  `json:"databaseLimitMiB"`
		BackupKeep       int    `json:"backupKeep"`
		BackupHourUTC    int    `json:"backupHourUTC"`
		BackupEnabled    bool   `json:"backupEnabled"`
		BackupPassphrase string `json:"backupPassphrase"`
	}
	if !decode(w, r, &v) {
		return
	}
	if v.HistoryDays < 0 || v.HistoryDays > 36500 || v.DatabaseLimitMiB < 32 || v.DatabaseLimitMiB > 1048576 || v.BackupKeep < 1 || v.BackupKeep > 365 || v.BackupHourUTC < 0 || v.BackupHourUTC > 23 || (v.BackupPassphrase != "" && (len(v.BackupPassphrase) < 16 || len(v.BackupPassphrase) > 1024)) {
		fail(w, 400, "invalid_settings")
		return
	}
	if r.Header.Get("If-Match") != revision {
		fail(w, 409, "revision_conflict")
		return
	}
	sealed := ""
	if v.BackupPassphrase != "" {
		var e error
		sealed, e = s.sealCredential(v.BackupPassphrase)
		if e != nil {
			fail(w, 503, "secret_storage_unavailable")
			return
		}
	}
	if v.BackupEnabled && sealed == "" && value(s.DB, "backup_passphrase") == "" {
		fail(w, 400, "backup_passphrase_required")
		return
	}
	next := Retention{v.HistoryDays, v.DatabaseLimitMiB, v.BackupKeep, v.BackupHourUTC, v.BackupEnabled}
	data, _ := json.Marshal(next)
	tx, e := s.DB.Begin()
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	_, e = tx.Exec("INSERT OR REPLACE INTO settings VALUES('retention',?)", string(data))
	if e == nil && sealed != "" {
		_, e = tx.Exec("INSERT OR REPLACE INTO settings VALUES('backup_passphrase',?)", sealed)
	}
	if e == nil {
		e = tx.Commit()
	}
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	respond(w, 200, map[string]bool{"saved": true})
}
func (s *Server) quotaExceeded() bool {
	v := s.retention()
	var pages, size int64
	if s.DB.QueryRow("PRAGMA page_count").Scan(&pages) != nil || s.DB.QueryRow("PRAGMA page_size").Scan(&size) != nil {
		return true
	}
	return pages*size >= v.DatabaseLimitMiB<<20
}
func (s *Server) Maintain(ctx context.Context) {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			s.maintenanceTick(ctx, time.Now().UTC())
		}
	}
}
func (s *Server) maintenanceTick(ctx context.Context, now time.Time) {
	// Expired ephemeral data is safe to discard; changes/mutations retain replay semantics.
	for _, query := range []string{
		"DELETE FROM cache WHERE expires<?", "DELETE FROM snapshots WHERE created<?",
		"DELETE FROM pairing WHERE expires<?", "DELETE FROM invites WHERE expires<?",
	} {
		cutoff := now.Unix()
		if strings.Contains(query, "snapshots") {
			cutoff -= 3600
		}
		if _, err := s.DB.ExecContext(ctx, query, cutoff); err != nil {
			break
		}
	}
	memory := resources.Read()
	var fs syscall.Statfs_t
	syscall.Statfs(filepath.Dir(s.Config.Database), &fs)
	var stats runtime.MemStats
	runtime.ReadMemStats(&stats)
	s.mu.Lock()
	active := 0
	for _, n := range s.active {
		active += n
	}
	s.mu.Unlock()
	log.Printf("event=resources available_bytes=%d total_bytes=%d heap_bytes=%d disk_free_bytes=%d streams=%d max_streams=%d goroutines=%d", memory.Available, memory.Total, stats.HeapAlloc, uint64(fs.Bavail)*uint64(fs.Bsize), active, s.maxStreams, runtime.NumGoroutine())
	v := s.retention()
	if v.HistoryDays > 0 {
		cutoff := now.Add(-time.Duration(v.HistoryDays) * 24 * time.Hour).Unix()
		tx, e := s.DB.BeginTx(ctx, nil)
		if e == nil {
			_, e = tx.Exec(`UPDATE entities SET value='{}',deleted=1,revision=revision+1 WHERE kind IN ('opening','progress') AND deleted=0 AND EXISTS (SELECT 1 FROM changes c WHERE c.user_id=entities.user_id AND c.kind=entities.kind AND c.key=entities.key GROUP BY c.user_id,c.kind,c.key HAVING MAX(c.created)<?)`, cutoff)
			if e == nil {
				_, e = tx.Exec(`INSERT INTO changes(user_id,kind,key,value,revision,deleted,created) SELECT e.user_id,e.kind,e.key,e.value,e.revision,1,? FROM entities e WHERE e.deleted=1 AND e.kind IN ('opening','progress') AND NOT EXISTS(SELECT 1 FROM changes c WHERE c.user_id=e.user_id AND c.kind=e.kind AND c.key=e.key AND c.revision=e.revision)`, now.Unix())
			}
			if e == nil {
				tx.Commit()
			} else {
				tx.Rollback()
			}
		}
	}
	day := now.Format("2006-01-02")
	if !v.BackupEnabled || now.Hour() != v.BackupHourUTC || value(s.DB, "last_backup_day") == day {
		return
	}
	pass, e := s.openSealed(value(s.DB, "backup_passphrase"))
	if e != nil {
		return
	}
	if os.MkdirAll(s.Config.BackupDir, 0700) != nil {
		return
	}
	id := "scheduled-" + now.Format("20060102T150405") + ".mjwb"
	if e = CreateBackup(ctx, s.DB, s.Config, pass, filepath.Join(s.Config.BackupDir, id)); e != nil {
		audit(s.DB, "system", "scheduled_backup_failed")
		return
	}
	s.DB.Exec("INSERT OR REPLACE INTO settings VALUES('last_backup_day',?)", day)
	audit(s.DB, "system", "scheduled_backup_verified")
	entries, _ := os.ReadDir(s.Config.BackupDir)
	names := []string{}
	for _, f := range entries {
		if strings.HasPrefix(f.Name(), "scheduled-") && strings.HasSuffix(f.Name(), ".mjwb") && !f.IsDir() {
			names = append(names, f.Name())
		}
	}
	sort.Sort(sort.Reverse(sort.StringSlice(names)))
	for _, name := range names[min(len(names), v.BackupKeep):] {
		os.Remove(filepath.Join(s.Config.BackupDir, name))
	}
}
