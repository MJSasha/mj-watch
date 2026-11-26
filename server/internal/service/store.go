package service

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"golang.org/x/crypto/argon2"
	_ "modernc.org/sqlite"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const Schema = `
PRAGMA journal_mode=WAL;
PRAGMA foreign_keys=ON;
PRAGMA busy_timeout=5000;
CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY,value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY,name TEXT UNIQUE NOT NULL,password TEXT NOT NULL DEFAULT '',role TEXT NOT NULL CHECK(role IN ('admin','user')),blocked INTEGER NOT NULL DEFAULT 0,epoch INTEGER NOT NULL DEFAULT 1);
CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,name TEXT NOT NULL,revoked INTEGER NOT NULL DEFAULT 0,last_seen INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS device_installations(user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,installation TEXT NOT NULL,device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,PRIMARY KEY(user_id,installation));
CREATE TABLE IF NOT EXISTS device_profiles(device_id TEXT PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,type TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS device_app_versions(device_id TEXT PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,name TEXT NOT NULL,code INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS remote_receivers(device_id TEXT PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,instance TEXT NOT NULL,heartbeat INTEGER NOT NULL,state TEXT NOT NULL,command TEXT NOT NULL DEFAULT '{}');
CREATE TABLE IF NOT EXISTS remote_receipts(device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,request_id TEXT NOT NULL,created INTEGER NOT NULL,PRIMARY KEY(device_id,request_id));
CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY,device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,access TEXT UNIQUE NOT NULL,refresh TEXT UNIQUE NOT NULL,access_exp INTEGER NOT NULL,refresh_exp INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS invites(user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,hash TEXT UNIQUE NOT NULL,expires INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS entities(user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,kind TEXT NOT NULL,key TEXT NOT NULL,value TEXT NOT NULL,revision INTEGER NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,play_session TEXT NOT NULL DEFAULT '',sequence INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(user_id,kind,key));
CREATE TABLE IF NOT EXISTS changes(cursor INTEGER PRIMARY KEY AUTOINCREMENT,user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,kind TEXT NOT NULL,key TEXT NOT NULL,value TEXT NOT NULL,revision INTEGER NOT NULL,deleted INTEGER NOT NULL,created INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS mutations(user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,id TEXT NOT NULL,result TEXT NOT NULL,PRIMARY KEY(user_id,id));
CREATE TABLE IF NOT EXISTS playback(id TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,hash TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS playback_requests(user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,id TEXT NOT NULL,handle TEXT NOT NULL DEFAULT '',PRIMARY KEY(user_id,id));
CREATE TABLE IF NOT EXISTS playback_jobs(handle TEXT PRIMARY KEY REFERENCES playback(id) ON DELETE CASCADE,state TEXT NOT NULL,created INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS pairing(id TEXT PRIMARY KEY,secret TEXT NOT NULL,code TEXT UNIQUE NOT NULL,name TEXT NOT NULL,expires INTEGER NOT NULL,user_id TEXT NOT NULL DEFAULT '');
CREATE TABLE IF NOT EXISTS snapshots(id TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,watermark INTEGER NOT NULL,history_epoch INTEGER NOT NULL,server_epoch TEXT NOT NULL,created INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS snapshot_entities(snapshot_id TEXT NOT NULL REFERENCES snapshots(id) ON DELETE CASCADE,ordinal INTEGER NOT NULL,kind TEXT NOT NULL,key TEXT NOT NULL,value TEXT NOT NULL,revision INTEGER NOT NULL,deleted INTEGER NOT NULL,PRIMARY KEY(snapshot_id,ordinal));
CREATE TABLE IF NOT EXISTS cache(key TEXT PRIMARY KEY,value BLOB NOT NULL,mime TEXT NOT NULL,expires INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY,actor TEXT NOT NULL,action TEXT NOT NULL,created INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS changes_user_cursor ON changes(user_id,cursor);
CREATE INDEX IF NOT EXISTS changes_entity_created ON changes(user_id,kind,key,created);
CREATE INDEX IF NOT EXISTS devices_user ON devices(user_id);
CREATE INDEX IF NOT EXISTS sessions_device ON sessions(device_id);
CREATE INDEX IF NOT EXISTS playback_user ON playback(user_id);
PRAGMA user_version=1;
`

func Open(path string) (*sql.DB, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return nil, err
	}
	absolute, err := filepath.Abs(path)
	if err != nil {
		return nil, err
	}
	uri := url.URL{Scheme: "file", Path: absolute, RawQuery: url.Values{"_pragma": []string{"foreign_keys(1)", "busy_timeout(5000)"}}.Encode()}
	db, err := sql.Open("sqlite", uri.String())
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(4)
	var version int
	if err = db.QueryRow("PRAGMA user_version").Scan(&version); err != nil {
		db.Close()
		return nil, err
	}
	if version > 1 {
		db.Close()
		return nil, errors.New("incompatible_schema")
	}
	if _, err = db.Exec(Schema); err != nil {
		db.Close()
		return nil, err
	}
	for _, key := range []string{"server_id", "server_epoch"} {
		if _, err = db.Exec("INSERT OR IGNORE INTO settings VALUES(?,?)", key, Token()); err != nil {
			db.Close()
			return nil, err
		}
	}
	os.Chmod(path, 0600)
	return db, nil
}
func Token() string {
	b := make([]byte, 32)
	if _, e := rand.Read(b); e != nil {
		panic(e)
	}
	return base64.RawURLEncoding.EncodeToString(b)
}
func Hash(s string) string { b := sha256.Sum256([]byte(s)); return hex.EncodeToString(b[:]) }
func Password(s string) (string, error) {
	if len(s) < 10 || len(s) > 1024 {
		return "", errors.New("password_length")
	}
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	key := argon2.IDKey([]byte(s), salt, 3, 64*1024, 2, 32)
	return base64.RawStdEncoding.EncodeToString(salt) + ":" + base64.RawStdEncoding.EncodeToString(key), nil
}
func CheckPassword(encoded, s string) bool {
	parts := strings.Split(encoded, ":")
	if len(parts) != 2 || len(s) > 1024 {
		return false
	}
	salt, e := base64.RawStdEncoding.DecodeString(parts[0])
	if e != nil || len(salt) != 16 {
		return false
	}
	want, e := base64.RawStdEncoding.DecodeString(parts[1])
	if e != nil || len(want) != 32 {
		return false
	}
	return subtle.ConstantTimeCompare(want, argon2.IDKey([]byte(s), salt, 3, 64*1024, 2, 32)) == 1
}
func value(db *sql.DB, key string) string {
	var v string
	db.QueryRow("SELECT value FROM settings WHERE key=?", key).Scan(&v)
	return v
}
func audit(db *sql.DB, actor, action string) {
	db.Exec("INSERT INTO audit(actor,action,created) VALUES(?,?,?)", actor, action, time.Now().Unix())
}
func Bootstrap(db *sql.DB, name, device string) (map[string]any, error) {
	tx, e := db.Begin()
	if e != nil {
		return nil, e
	}
	defer tx.Rollback()
	var uid string
	e = tx.QueryRow("SELECT id FROM users WHERE role='admin' LIMIT 1").Scan(&uid)
	if e == sql.ErrNoRows {
		uid = Token()
		_, e = tx.Exec("INSERT INTO users(id,name,role) VALUES(?,?,'admin')", uid, name)
	}
	if e != nil {
		return nil, e
	}
	if _, e = tx.Exec("UPDATE users SET blocked=0 WHERE id=?", uid); e != nil {
		return nil, e
	}
	out, e := issue(tx, uid, device)
	if e != nil {
		return nil, e
	}
	if e = tx.Commit(); e != nil {
		return nil, e
	}
	out["serverId"] = value(db, "server_id")
	out["serverEpoch"] = value(db, "server_epoch")
	return out, nil
}
func issue(tx *sql.Tx, uid, name string) (map[string]any, error) {
	if len(name) < 1 || len(name) > 120 {
		return nil, fmt.Errorf("device_name")
	}
	did, access, refresh := Token(), Token(), Token()
	now := time.Now().Unix()
	if _, e := tx.Exec("INSERT INTO devices VALUES(?,?,?,0,?)", did, uid, name, now); e != nil {
		return nil, e
	}
	_, e := tx.Exec("INSERT INTO sessions VALUES(?,?,?,?,?,?)", Token(), did, Hash(access), Hash(refresh), now+900, now+30*86400)
	return map[string]any{"accessToken": access, "refreshToken": refresh, "expiresIn": 900, "deviceId": did, "userId": uid}, e
}

func ReceiptValid(db *sql.DB, out map[string]any) bool {
	token, ok := out["refreshToken"].(string)
	if !ok {
		return false
	}
	var count int
	e := db.QueryRow("SELECT COUNT(*) FROM sessions s JOIN devices d ON d.id=s.device_id JOIN users u ON u.id=d.user_id WHERE s.refresh=? AND s.refresh_exp>? AND u.role='admin' AND u.blocked=0 AND d.revoked=0", Hash(token), time.Now().Unix()).Scan(&count)
	return e == nil && count == 1
}
