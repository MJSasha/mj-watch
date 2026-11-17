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
		Type string `json:"type"`
	}
	if !decodeLimit(w, r, &v, 1024) {
		return
	}
	if v.Type != "phone" && v.Type != "tablet" && v.Type != "tv" {
		fail(w, 400, "device_type")
		return
	}
	_, err := s.DB.Exec("INSERT INTO device_profiles(device_id,type) VALUES(?,?) ON CONFLICT(device_id) DO UPDATE SET type=excluded.type", i.Device, v.Type)
	if err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	// Capabilities are established by the receiver handshake, never by discovery alone.
	respond(w, 200, map[string]any{"type": v.Type})
}
