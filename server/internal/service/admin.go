package service

import (
	"dev.mjwatch/server/internal/resources"
	"net/http"
	"strings"
	"time"
)

func (s *Server) admin(w http.ResponseWriter, r *http.Request, path string, i identity) {
	switch {
	case path == "admin/host/jacred/web":
		s.jacredWebSession(w, r, i)
	case path == "admin/host/jacred/websocket", path == "admin/host/prowlarr/websocket":
		s.searchWebSocket(w, r, i, path == "admin/host/prowlarr/websocket")
	case path == "admin/owner/password" && r.Method == http.MethodPost:
		s.changeOwnerPassword(w, r, i)
	case path == "admin/host/update/available" && r.Method == http.MethodGet:
		s.latestServerSupply(w, r)
	case strings.HasPrefix(path, "admin/host/"):
		s.management(w, r, strings.TrimPrefix(path, "admin/host/"), i)
	case path == "admin/resources" && r.Method == http.MethodGet:
		s.hostResources(w)
	case path == "admin/jacred" && r.Method == http.MethodGet:
		s.jacredDiagnostics(w, r)
	case path == "admin/jacred/stats" && r.Method == http.MethodGet:
		s.jacredStats(w, r)
	case path == "admin/jacred/stats" && r.Method == http.MethodPost:
		s.startJacredStatsRefresh(w)
	case path == "admin/jacred/collect" && r.Method == http.MethodPost:
		s.queueJacredCollection(w)
	case path == "admin/jacred/jobs" && r.Method == http.MethodGet:
		s.jacredJobs(w, r)
	case path == "admin/jacred/jobs" && r.Method == http.MethodPost:
		s.startJacredJob(w, r)
	case path == "admin/playback/settings":
		s.playbackSettings(w, r)
	case path == "admin/playback/streams" || strings.HasPrefix(path, "admin/playback/streams/"):
		s.playbackStreams(w, r, path)
	case path == "admin/probe" && r.Method == "POST":
		key := "probe:" + Token()
		_, e := s.DB.Exec("INSERT INTO settings VALUES(?,?)", key, key)
		if e != nil {
			fail(w, 503, "probe_write_failed")
			return
		}
		var got string
		e = s.DB.QueryRow("SELECT value FROM settings WHERE key=?", key).Scan(&got)
		s.DB.Exec("DELETE FROM settings WHERE key=?", key)
		if e != nil || got != key {
			fail(w, 503, "probe_read_failed")
			return
		}
		respond(w, 200, map[string]bool{"verified": true})

	case strings.HasPrefix(path, "admin/tmdb"):
		s.adminTMDB(w, r, path, i)
	case path == "admin/status" && r.Method == "GET":
		s.mu.Lock()
		active := len(s.streams)
		limits := s.streamSettingsSnapshot()
		maxStreams := s.maxStreams
		s.mu.Unlock()
		var users int
		s.DB.QueryRow("SELECT COUNT(*) FROM users").Scan(&users)
		var ok string
		e := s.DB.QueryRow("PRAGMA quick_check").Scan(&ok)
		req, _ := http.NewRequestWithContext(r.Context(), "GET", s.Config.TorrURL+"/echo", nil)
		if s.Config.TorrUser != "" {
			req.SetBasicAuth(s.Config.TorrUser, s.Config.TorrPassword)
		}
		resp, err := s.Client.Do(req)
		torr := err == nil && resp.StatusCode == 200
		if resp != nil {
			resp.Body.Close()
		}
		respond(w, 200, map[string]any{"version": Version, "apiVersion": 1, "databaseHealthy": e == nil && ok == "ok", "torrserverHealthy": torr, "activeStreams": active, "users": users, "tmdb": s.tmdbPublic(), "maxStreams": maxStreams, "playback": limits, "resources": resources.Read().Budget(maxStreams)})
	case path == "admin/audit" && r.Method == "GET":
		rows, e := s.DB.Query("SELECT actor,action,created FROM audit ORDER BY id DESC LIMIT 200")
		if e != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		defer rows.Close()
		out := []any{}
		for rows.Next() {
			var actor, action string
			var created int64
			rows.Scan(&actor, &action, &created)
			out = append(out, map[string]any{"actor": actor, "action": action, "created": created})
		}
		respond(w, 200, map[string]any{"audit": out})
	case path == "admin/users" && r.Method == "GET":
		rows, e := s.DB.Query("SELECT id,name,role,blocked FROM users ORDER BY name")
		if e != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		defer rows.Close()
		out := []any{}
		for rows.Next() {
			var id, name, role string
			var blocked bool
			rows.Scan(&id, &name, &role, &blocked)
			out = append(out, map[string]any{"id": id, "name": name, "role": role, "blocked": blocked})
		}
		respond(w, 200, map[string]any{"users": out})
	case path == "admin/users" && r.Method == "POST":
		var v struct{ Name string }
		if !decode(w, r, &v) {
			return
		}
		if len(v.Name) < 1 || len(v.Name) > 100 {
			fail(w, 400, "invalid_name")
			return
		}
		uid := Token()
		_, e := s.DB.Exec("INSERT INTO users(id,name,role) VALUES(?,?,'user')", uid, v.Name)
		if e != nil {
			fail(w, 409, "name_unavailable")
			return
		}
		s.invite(w, uid)
	case strings.HasPrefix(path, "admin/users/"):
		parts := strings.Split(path, "/")
		if len(parts) == 5 && parts[3] == "devices" && r.Method == "DELETE" {
			if _, err := s.DB.Exec("DELETE FROM devices WHERE id=? AND user_id=?", parts[4], parts[2]); err != nil {
				fail(w, 503, "database_unavailable")
				return
			}
			respond(w, 200, map[string]bool{"revoked": true})
			return
		}
		if len(parts) < 3 || len(parts) > 4 {
			fail(w, 404, "not_found")
			return
		}
		uid := parts[2]
		if len(parts) == 4 {
			if parts[3] == "invite" && r.Method == "DELETE" {
				if _, err := s.DB.Exec("DELETE FROM invites WHERE user_id=?", uid); err != nil {
					fail(w, 503, "database_unavailable")
					return
				}
				respond(w, 200, map[string]bool{"revoked": true})
			} else if parts[3] == "invite" && r.Method == "POST" {
				s.invite(w, uid)
			} else if parts[3] == "devices" && r.Method == "GET" {
				s.devices(w, uid, true)
			} else if parts[3] == "devices" && r.Method == "DELETE" {
				if _, err := s.DB.Exec("UPDATE devices SET revoked=1 WHERE user_id=?", uid); err != nil {
					fail(w, 503, "database_unavailable")
					return
				}
				respond(w, 200, map[string]bool{"revoked": true})
			} else {
				fail(w, 404, "not_found")
			}
			return
		}
		tx, e := s.DB.Begin()
		if e != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		defer tx.Rollback()
		var role string
		var blocked bool
		if tx.QueryRow("SELECT role,blocked FROM users WHERE id=?", uid).Scan(&role, &blocked) != nil {
			fail(w, 404, "not_found")
			return
		}
		var desired bool
		if r.Method == "PATCH" {
			var v struct{ Blocked bool }
			if !decode(w, r, &v) {
				return
			}
			desired = v.Blocked
		} else if r.Method != "DELETE" {
			fail(w, 405, "method_not_allowed")
			return
		}
		if role == "admin" && !blocked && (r.Method == "DELETE" || desired) {
			var count int
			if tx.QueryRow("SELECT COUNT(*) FROM users WHERE role='admin' AND blocked=0").Scan(&count) != nil {
				fail(w, 503, "database_unavailable")
				return
			}
			if count <= 1 {
				fail(w, 409, "last_owner")
				return
			}
		}
		if r.Method == "DELETE" {
			_, e = tx.Exec("DELETE FROM users WHERE id=?", uid)
		} else {
			_, e = tx.Exec("UPDATE users SET blocked=? WHERE id=?", desired, uid)
			if e == nil && desired {
				_, e = tx.Exec("UPDATE devices SET revoked=1 WHERE user_id=?", uid)
			}
		}
		if e == nil {
			e = tx.Commit()
		}
		if e != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		respond(w, 200, map[string]bool{"saved": true})
	case path == "admin/settings":
		s.retentionAPI(w, r)
	case strings.HasPrefix(path, "admin/backups"):
		s.backups(w, r, path, i)
	default:
		fail(w, 404, "not_found")
	}
}
func (s *Server) invite(w http.ResponseWriter, uid string) {
	var role string
	if s.DB.QueryRow("SELECT role FROM users WHERE id=? AND blocked=0", uid).Scan(&role) != nil {
		fail(w, 404, "not_found")
		return
	}
	if role != "user" {
		fail(w, 403, "ssh_bootstrap_required")
		return
	}
	token := Token()
	_, e := s.DB.Exec("INSERT OR REPLACE INTO invites VALUES(?,?,?)", uid, Hash(token), time.Now().Unix()+86400)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	respond(w, 200, map[string]any{"userId": uid, "invite": token, "expiresIn": 86400})
}
