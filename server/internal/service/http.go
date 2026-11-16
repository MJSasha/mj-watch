package service

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

type Config struct {
	Database     string `json:"database"`
	Listen       string `json:"listen"`
	Certificate  string `json:"certificate"`
	PrivateKey   string `json:"privateKey"`
	TorrURL      string `json:"torrUrl"`
	TorrUser     string `json:"torrUser"`
	TorrPassword string `json:"torrPassword"`
	SecretKey    string `json:"secretKey"`
	BackupDir    string `json:"backupDir"`
}
type identity struct {
	User, Device, Session, Role string
	Epoch                       int64
}

var Version = "0.1.1"

type Server struct {
	passwordSlots chan struct{}
	playbackSlots chan struct{}
	uploadSlots   chan struct{}
	dummyPassword string

	DB     *sql.DB
	Config Config
	Client *http.Client
	mu     sync.Mutex
	rates  map[string][]time.Time
	active map[string]int
	tmdbMu sync.Mutex
}

func New(db *sql.DB, c Config) *Server {
	dummy, _ := Password("not-a-valid-user-password")
	return &Server{passwordSlots: make(chan struct{}, 2), playbackSlots: make(chan struct{}, 4), uploadSlots: make(chan struct{}, 2), dummyPassword: dummy, DB: db, Config: c, Client: &http.Client{Timeout: 25 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}, rates: map[string][]time.Time{}, active: map[string]int{}}
}
func (s *Server) limit(key string, n int, period time.Duration) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now()
	q := s.rates[key]
	i := 0
	for i < len(q) && now.Sub(q[i]) > period {
		i++
	}
	q = q[i:]
	if len(q) >= n {
		return false
	}
	if len(s.rates) > 10000 {
		for k, v := range s.rates {
			if len(v) == 0 || now.Sub(v[len(v)-1]) > time.Hour {
				delete(s.rates, k)
			}
		}
	}
	s.rates[key] = append(q, now)
	return true
}
func respond(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}
func fail(w http.ResponseWriter, status int, code string) {
	respond(w, status, map[string]any{"code": code})
}
func decode(w http.ResponseWriter, r *http.Request, v any) bool { return decodeLimit(w, r, v, 2<<20) }
func decodeLimit(w http.ResponseWriter, r *http.Request, v any, limit int64) bool {
	r.Body = http.MaxBytesReader(w, r.Body, limit)
	d := json.NewDecoder(r.Body)
	d.DisallowUnknownFields()
	if e := d.Decode(v); e != nil {
		fail(w, 400, "invalid_request")
		return false
	}
	if d.Decode(new(any)) != io.EOF {
		fail(w, 400, "invalid_request")
		return false
	}
	return true
}
func (s *Server) identity(token string) (identity, error) {
	var i identity
	e := s.DB.QueryRow(`SELECT u.id,d.id,s.id,u.role,u.epoch FROM sessions s JOIN devices d ON d.id=s.device_id JOIN users u ON u.id=d.user_id WHERE s.access=? AND s.access_exp>? AND d.revoked=0 AND u.blocked=0`, Hash(token), time.Now().Unix()).Scan(&i.User, &i.Device, &i.Session, &i.Role, &i.Epoch)
	return i, e
}
func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	if !strings.HasPrefix(r.URL.Path, "/api/v1/") {
		fail(w, 404, "not_found")
		return
	}
	path := strings.TrimPrefix(r.URL.Path, "/api/v1/")
	if strings.HasPrefix(path, "playback/") && strings.HasSuffix(path, "/stream") {
		http.NewResponseController(w).SetWriteDeadline(time.Time{})
	} else {
		http.NewResponseController(w).SetWriteDeadline(time.Now().Add(30 * time.Second))
	}
	ip, _, _ := net.SplitHostPort(r.RemoteAddr)
	if strings.HasPrefix(path, "auth/") || strings.HasPrefix(path, "pairing") {
		if !s.limit("auth:"+ip, 60, time.Minute) {
			w.Header().Set("Retry-After", "60")
			fail(w, 429, "rate_limited")
			return
		}
		if s.auth(w, r, path) {
			return
		}
	}
	token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	i, e := s.identity(token)
	if e != nil {
		fail(w, 401, "login_required")
		return
	}
	s.DB.Exec("UPDATE devices SET last_seen=? WHERE id=?", time.Now().Unix(), i.Device)
	if !s.limit("api:"+i.User, 300, time.Minute) {
		fail(w, 429, "rate_limited")
		return
	}
	if strings.HasPrefix(path, "admin/") {
		if i.Role != "admin" {
			fail(w, 403, "admin_required")
			return
		}
		if r.Method != "GET" {
			audit(s.DB, i.User, strings.Split(path, "/")[1]+":"+r.Method)
		}
		s.admin(w, r, path, i)
		return
	}
	switch {
	case path == "me" && r.Method == "GET":
		var name string
		s.DB.QueryRow("SELECT name FROM users WHERE id=?", i.User).Scan(&name)
		respond(w, 200, map[string]any{"userId": i.User, "name": name, "role": i.Role, "deviceId": i.Device, "serverId": value(s.DB, "server_id"), "serverEpoch": value(s.DB, "server_epoch"), "historyEpoch": i.Epoch, "apiVersion": 1, "serverVersion": Version, "schemaVersion": 1, "minClientApiVersion": 1})
	case (path == "capabilities" || path == "config") && r.Method == "GET":
		respond(w, 200, map[string]any{"apiVersion": 1, "serverVersion": Version, "schemaVersion": 1, "minClientApiVersion": 1, "tmdb": s.publicTMDB(), "sync": true})
	case path == "auth/logout" && r.Method == "POST":
		if _, err := s.DB.Exec("UPDATE devices SET revoked=1 WHERE id=?", i.Device); err != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		respond(w, 200, map[string]bool{"revoked": true})
	case path == "devices" && r.Method == "GET":
		s.devices(w, i.User)
	case strings.HasPrefix(path, "devices/") && r.Method == "DELETE":
		if _, err := s.DB.Exec("UPDATE devices SET revoked=1 WHERE id=? AND user_id=?", strings.TrimPrefix(path, "devices/"), i.User); err != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		respond(w, 200, map[string]bool{"revoked": true})
	case path == "pairing/lookup" && r.Method == "POST":
		var v struct{ Code string }
		if !decode(w, r, &v) {
			return
		}
		if !s.limit("pair-lookup:"+i.User, 10, time.Minute) {
			fail(w, 429, "rate_limited")
			return
		}
		var name string
		var expires int64
		if s.DB.QueryRow("SELECT name,expires FROM pairing WHERE code=? AND user_id='' AND expires>?", v.Code, time.Now().Unix()).Scan(&name, &expires) != nil {
			fail(w, 404, "pairing_expired")
			return
		}
		respond(w, 200, map[string]any{"pairing": map[string]any{"code": v.Code, "device": name, "expires": expires}})
	case strings.HasPrefix(path, "pairing/") && strings.HasSuffix(path, "/approve") && r.Method == "POST":
		s.approve(w, r, i)
	case path == "mutations" && r.Method == "POST":
		s.mutate(w, r, i)
	case (path == "sync" || path == "snapshot") && r.Method == "GET":
		s.pull(w, r, i, path == "snapshot")
	case path == "history" && r.Method == "DELETE":
		s.clearHistory(w, r, i)
	case strings.HasPrefix(path, "playback"):
		s.playback(w, r, path, i, token)
	case strings.HasPrefix(path, "metadata/") && r.Method == "GET":
		s.metadata(w, r, path, i)
	default:
		fail(w, 404, "not_found")
	}
}
func (s *Server) auth(w http.ResponseWriter, r *http.Request, path string) bool {
	if (path == "auth/login" || path == "auth/accept-invite") && r.Method == "POST" {
		select {
		case s.passwordSlots <- struct{}{}:
			defer func() { <-s.passwordSlots }()
		default:
			fail(w, 429, "authentication_busy")
			return true
		}
	}
	if path == "auth/login" && r.Method == "POST" {
		var v struct{ Name, Password, Device string }
		if !decode(w, r, &v) {
			return true
		}
		var uid, hash string
		e := s.DB.QueryRow("SELECT id,password FROM users WHERE name=? AND blocked=0", v.Name).Scan(&uid, &hash)
		if e != nil {
			hash = s.dummyPassword
		}
		if !CheckPassword(hash, v.Password) || e != nil {
			fail(w, 401, "invalid_credentials")
			return true
		}
		tx, e := s.DB.Begin()
		if e != nil {
			fail(w, 503, "database_unavailable")
			return true
		}
		defer tx.Rollback()
		out, e := issue(tx, uid, v.Device)
		if e == nil {
			e = tx.Commit()
		}
		if e != nil {
			fail(w, 400, "session_failed")
		} else {
			respond(w, 200, out)
		}
		return true
	}
	if path == "auth/refresh" && r.Method == "POST" {
		var v struct{ RefreshToken string }
		if !decode(w, r, &v) {
			return true
		}
		tx, e := s.DB.Begin()
		if e != nil {
			fail(w, 503, "database_unavailable")
			return true
		}
		defer tx.Rollback()
		var sid string
		e = tx.QueryRow("SELECT s.id FROM sessions s JOIN devices d ON d.id=s.device_id JOIN users u ON u.id=d.user_id WHERE s.refresh=? AND s.refresh_exp>? AND d.revoked=0 AND u.blocked=0", Hash(v.RefreshToken), time.Now().Unix()).Scan(&sid)
		if e != nil {
			fail(w, 401, "login_required")
			return true
		}
		a, b := Token(), Token()
		_, e = tx.Exec("UPDATE sessions SET access=?,refresh=?,access_exp=?,refresh_exp=? WHERE id=?", Hash(a), Hash(b), time.Now().Unix()+900, time.Now().Unix()+30*86400, sid)
		if e == nil {
			e = tx.Commit()
		}
		if e != nil {
			fail(w, 503, "database_unavailable")
		} else {
			respond(w, 200, map[string]any{"accessToken": a, "refreshToken": b, "expiresIn": 900})
		}
		return true
	}
	if path == "auth/accept-invite" && r.Method == "POST" {
		var v struct{ Invite, Password, Device string }
		if !decode(w, r, &v) {
			return true
		}
		hash, e := Password(v.Password)
		if e != nil {
			fail(w, 400, "password_length")
			return true
		}
		tx, e := s.DB.Begin()
		if e != nil {
			fail(w, 503, "database_unavailable")
			return true
		}
		defer tx.Rollback()
		var uid string
		e = tx.QueryRow("SELECT i.user_id FROM invites i JOIN users u ON u.id=i.user_id WHERE i.hash=? AND i.expires>? AND u.blocked=0", Hash(v.Invite), time.Now().Unix()).Scan(&uid)
		if e != nil {
			fail(w, 401, "invite_invalid")
			return true
		}
		tx.Exec("DELETE FROM invites WHERE user_id=?", uid)
		tx.Exec("UPDATE users SET password=? WHERE id=?", hash, uid)
		out, e := issue(tx, uid, v.Device)
		if e == nil {
			e = tx.Commit()
		}
		if e != nil {
			fail(w, 400, "session_failed")
		} else {
			respond(w, 200, out)
		}
		return true
	}
	if path == "pairing" && r.Method == "POST" {
		var v struct{ Device string }
		if !decode(w, r, &v) {
			return true
		}
		if len(v.Device) < 1 || len(v.Device) > 120 {
			fail(w, 400, "device_name")
			return true
		}
		id, secret := Token(), Token()
		n, _ := strconv.ParseUint(Hash(id)[:8], 16, 32)
		code := strconv.FormatUint(100000+n%900000, 10)
		s.DB.Exec("DELETE FROM pairing WHERE expires<?", time.Now().Unix())
		_, e := s.DB.Exec("INSERT INTO pairing VALUES(?,?,?,?,?,'')", id, Hash(secret), code, v.Device, time.Now().Unix()+300)
		if e != nil {
			fail(w, 503, "pairing_busy")
		} else {
			respond(w, 200, map[string]any{"id": id, "pollSecret": secret, "code": code, "expiresIn": 300})
		}
		return true
	}
	if strings.HasPrefix(path, "pairing/") && !strings.HasSuffix(path, "/approve") && r.Method == "GET" {
		id := strings.TrimPrefix(path, "pairing/")
		tx, e := s.DB.Begin()
		if e != nil {
			fail(w, 503, "database_unavailable")
			return true
		}
		defer tx.Rollback()
		var uid, name string
		e = tx.QueryRow("SELECT user_id,name FROM pairing WHERE id=? AND secret=? AND expires>?", id, Hash(r.Header.Get("X-Pairing-Secret")), time.Now().Unix()).Scan(&uid, &name)
		if e != nil {
			fail(w, 404, "pairing_expired")
			return true
		}
		if uid == "" {
			respond(w, 202, map[string]string{"status": "pending"})
			return true
		}
		out, e := issue(tx, uid, name)
		if e == nil {
			_, e = tx.Exec("DELETE FROM pairing WHERE id=?", id)
		}
		if e == nil {
			e = tx.Commit()
		}
		if e != nil {
			fail(w, 503, "database_unavailable")
		} else {
			respond(w, 200, out)
		}
		return true
	}
	return false
}
func (s *Server) devices(w http.ResponseWriter, uid string) {
	rows, e := s.DB.Query("SELECT id,name,last_seen,revoked FROM devices WHERE user_id=? ORDER BY last_seen DESC", uid)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer rows.Close()
	out := []any{}
	for rows.Next() {
		var id, name string
		var seen, revoked int64
		rows.Scan(&id, &name, &seen, &revoked)
		out = append(out, map[string]any{"id": id, "name": name, "lastSeen": seen, "revoked": revoked != 0})
	}
	respond(w, 200, map[string]any{"devices": out})
}
func (s *Server) approve(w http.ResponseWriter, r *http.Request, i identity) {
	var v struct{ Code string }
	if !decode(w, r, &v) {
		return
	}
	if !s.limit("pair-approve:"+i.User, 10, time.Minute) {
		fail(w, 429, "rate_limited")
		return
	}
	code := strings.TrimSuffix(strings.TrimPrefix(r.URL.Path, "/api/v1/pairing/"), "/approve")
	if code != v.Code {
		fail(w, 400, "code_mismatch")
		return
	}
	res, e := s.DB.Exec("UPDATE pairing SET user_id=? WHERE code=? AND expires>? AND user_id=''", i.User, v.Code, time.Now().Unix())
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	n, _ := res.RowsAffected()
	if n != 1 {
		fail(w, 404, "pairing_expired")
		return
	}
	respond(w, 200, map[string]string{"status": "approved"})
}

var _ = errors.Is
var _ = context.Canceled
