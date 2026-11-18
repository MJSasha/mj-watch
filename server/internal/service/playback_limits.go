package service

import (
	"database/sql"
	"dev.mjwatch/server/internal/resources"
	"encoding/json"
	"net/http"
)

const MaximumStreams = 8

type StreamSettings struct {
	Total   int `json:"maxStreams"`
	PerUser int `json:"maxStreamsPerUser"`
}

func ReadStreamSettings(db *sql.DB) StreamSettings {
	var settings StreamSettings
	json.Unmarshal([]byte(value(db, "playback_limits")), &settings)
	if settings.Total < 0 || settings.Total > MaximumStreams {
		settings.Total = 0
	}
	if settings.PerUser < 0 || settings.PerUser > MaximumStreams {
		settings.PerUser = 0
	}
	return settings
}

func (v StreamSettings) Effective(recommended int) (int, int) {
	total := v.Total
	if total == 0 {
		total = recommended
	}
	total = max(1, min(MaximumStreams, total))
	perUser := v.PerUser
	if perUser == 0 {
		perUser = 2
	}
	return total, min(total, max(1, perUser))
}

func (s *Server) streamSettingsSnapshot() map[string]any {
	v := ReadStreamSettings(s.DB)
	raw, _ := json.Marshal(v)
	return map[string]any{"settings": v, "revision": Hash(string(raw)), "effectiveMaxStreams": s.maxStreams, "effectiveMaxStreamsPerUser": s.maxStreamsPerUser, "recommendedMaxStreams": s.recommendedStreams, "maximumStreams": MaximumStreams}
}

func (s *Server) playbackSettings(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if r.Method == "GET" {
		respond(w, 200, s.streamSettingsSnapshot())
		return
	}
	if r.Method != "PATCH" {
		fail(w, 405, "method_not_allowed")
		return
	}
	var v StreamSettings
	if !decode(w, r, &v) {
		return
	}
	if v.Total < 0 || v.Total > MaximumStreams || v.PerUser < 0 || v.PerUser > MaximumStreams {
		fail(w, 400, "invalid_stream_limits")
		return
	}
	effectiveTotal, _ := v.Effective(s.recommendedStreams)
	if v.PerUser > effectiveTotal {
		fail(w, 400, "invalid_stream_limits")
		return
	}
	if r.Header.Get("If-Match") != s.streamSettingsSnapshot()["revision"] {
		fail(w, 409, "revision_conflict")
		return
	}
	raw, _ := json.Marshal(v)
	if _, err := s.DB.Exec("INSERT OR REPLACE INTO settings VALUES('playback_limits',?)", string(raw)); err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	s.maxStreams, s.maxStreamsPerUser = v.Effective(s.recommendedStreams)
	respond(w, 200, s.streamSettingsSnapshot())
}

func ConfiguredStreams(db *sql.DB) int {
	total, _ := ReadStreamSettings(db).Effective(resources.Read().Streams())
	return total
}
