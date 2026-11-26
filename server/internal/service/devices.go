package service

import (
	"net/http"
	"strings"
	"unicode"
	"unicode/utf8"
)

// Aliases belong to the MJWatch account; they never rename the OS/Cast route.
func (s *Server) renameDevice(w http.ResponseWriter, r *http.Request, id string, i identity) {
	var v struct {
		Name string `json:"name"`
	}
	if !decodeLimit(w, r, &v, 1024) {
		return
	}
	name := strings.TrimSpace(v.Name)
	if !utf8.ValidString(name) || utf8.RuneCountInString(name) < 1 || utf8.RuneCountInString(name) > 32 || strings.ContainsFunc(name, unicode.IsControl) {
		fail(w, 400, "device_name")
		return
	}
	result, err := s.DB.Exec("UPDATE devices SET name=? WHERE id=? AND user_id=? AND revoked=0", name, id, i.User)
	if err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	n, _ := result.RowsAffected()
	if n != 1 {
		fail(w, 404, "device_not_found")
		return
	}
	respond(w, 200, map[string]any{"id": id, "name": name})
}
func (s *Server) deviceProfile(w http.ResponseWriter, r *http.Request, i identity) {
	var v struct {
		Type         string `json:"type"`
		Installation string `json:"installationId"`
		VersionName  string `json:"appVersionName"`
		VersionCode  int64  `json:"appVersionCode"`
	}
	if !decodeLimit(w, r, &v, 1024) {
		return
	}
	if v.Type != "phone" && v.Type != "tablet" && v.Type != "tv" {
		fail(w, 400, "device_type")
		return
	}
	if v.Installation != "" && (len(v.Installation) < 16 || len(v.Installation) > 128 || strings.ContainsFunc(v.Installation, func(r rune) bool { return !unicode.IsLetter(r) && !unicode.IsDigit(r) && r != '-' })) {
		fail(w, 400, "installation_id")
		return
	}
	reportedVersion := v.VersionName != "" || v.VersionCode != 0
	if reportedVersion && (v.VersionCode < 1 || strings.TrimSpace(v.VersionName) == "" || len(v.VersionName) > 256 || strings.ContainsFunc(v.VersionName, unicode.IsControl)) {
		fail(w, 400, "device_app_version")
		return
	}
	tx, err := s.DB.Begin()
	if err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	if v.Installation != "" {
		// Identify an installation, never merge distinct devices sharing a model or alias.
		_, err = tx.Exec("DELETE FROM devices WHERE user_id=? AND id<>? AND id IN (SELECT device_id FROM device_installations WHERE user_id=? AND installation=?)", i.User, i.Device, i.User, v.Installation)
		if err == nil {
			_, err = tx.Exec("INSERT INTO device_installations VALUES(?,?,?) ON CONFLICT(user_id,installation) DO UPDATE SET device_id=excluded.device_id", i.User, v.Installation, i.Device)
		}
		if err != nil {
			fail(w, 503, "database_unavailable")
			return
		}
	}
	_, err = tx.Exec("INSERT INTO device_profiles(device_id,type) VALUES(?,?) ON CONFLICT(device_id) DO UPDATE SET type=excluded.type", i.Device, v.Type)
	if err == nil && reportedVersion {
		_, err = tx.Exec(`INSERT INTO device_app_versions(device_id,name,code) VALUES(?,?,?)
			ON CONFLICT(device_id) DO UPDATE SET name=excluded.name,code=excluded.code
			WHERE name<>excluded.name OR code<>excluded.code`, i.Device, v.VersionName, v.VersionCode)
	}
	if err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	if err = tx.Commit(); err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	// Capabilities are established by the receiver handshake, never by discovery alone.
	respond(w, 200, map[string]any{"type": v.Type})
}
