package service

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"regexp"
	"time"
)

const defaultManagementSocket = "/run/mjwatch-management.sock"

var serverSupplyAsset = regexp.MustCompile(`^mjwatch-([0-9]+\.[0-9]+\.[0-9]+)-supply\.zip$`)

var managementRoutes = map[string]string{
	"status":             http.MethodGet,
	"torr/read":          http.MethodGet,
	"torr/start":         http.MethodPost,
	"torr/status":        http.MethodGet,
	"maintenance/start":  http.MethodPost,
	"maintenance/status": http.MethodGet,
	"prowlarr/status":    http.MethodGet,
	"prowlarr/install":   http.MethodPost,
	"prowlarr/prowlarr":  http.MethodPost,
	"prowlarr/jacred":    http.MethodPost,
	"jacred/status":      http.MethodGet,
	"jacred/start":       http.MethodPost,
}

func (s *Server) management(w http.ResponseWriter, r *http.Request, route string, i identity) {
	method, known := managementRoutes[route]
	if !known || method != r.Method {
		fail(w, http.StatusNotFound, "not_found")
		return
	}
	var payload map[string]any
	if r.Method == http.MethodPost {
		if !decodeLimit(w, r, &payload, 16<<10) {
			return
		}
		if payload == nil {
			fail(w, http.StatusBadRequest, "invalid_request")
			return
		}
	} else if route == "maintenance/status" || route == "torr/status" {
		payload = map[string]any{"operationId": r.URL.Query().Get("operationId")}
	} else {
		payload = map[string]any{}
	}
	if route == "maintenance/start" {
		password, _ := payload["adminPassword"].(string)
		delete(payload, "adminPassword")
		if _, ok := s.checkOwnerPassword(w, i, password, false); !ok {
			return
		}
	}
	result, err := s.managementCall(r.Context(), route, payload)
	if err != nil {
		fail(w, http.StatusServiceUnavailable, "management_unavailable")
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	if code, _ := result["error"].(string); code != "" {
		switch code {
		case "invalid_request":
			fail(w, http.StatusBadRequest, code)
		case "management_operation_failed":
			fail(w, http.StatusBadGateway, code)
		default:
			fail(w, http.StatusConflict, code)
		}
		return
	}
	respond(w, http.StatusOK, result)
}

func (s *Server) checkOwnerPassword(w http.ResponseWriter, i identity, password string, allowInitial bool) (string, bool) {
	if len(password) > 1024 {
		fail(w, 400, "invalid_request")
		return "", false
	}
	if !s.limit("owner-reauth:"+i.User, 10, time.Minute) {
		w.Header().Set("Retry-After", "60")
		fail(w, 429, "rate_limited")
		return "", false
	}
	var encoded string
	if err := s.DB.QueryRow("SELECT password FROM users WHERE id=? AND role='admin' AND blocked=0", i.User).Scan(&encoded); err != nil {
		fail(w, 403, "admin_required")
		return "", false
	}
	if encoded == "" {
		if allowInitial && password == "" {
			return "", true
		}
		fail(w, 409, "owner_password_required")
		return "", false
	}
	select {
	case s.passwordSlots <- struct{}{}:
		defer func() { <-s.passwordSlots }()
	default:
		fail(w, 429, "authentication_busy")
		return "", false
	}
	if !CheckPassword(encoded, password) {
		fail(w, 403, "reauthentication_required")
		return "", false
	}
	return encoded, true
}

func (s *Server) changeOwnerPassword(w http.ResponseWriter, r *http.Request, i identity) {
	var input struct{ Current, Password string }
	if !decodeLimit(w, r, &input, 8192) {
		return
	}
	if len(input.Password) < 10 || len(input.Password) > 1024 {
		fail(w, 400, "password_length")
		return
	}
	previous, ok := s.checkOwnerPassword(w, i, input.Current, true)
	if !ok {
		return
	}
	select {
	case s.passwordSlots <- struct{}{}:
		defer func() { <-s.passwordSlots }()
	default:
		fail(w, 429, "authentication_busy")
		return
	}
	hash, err := Password(input.Password)
	if err != nil {
		fail(w, 500, "password_update_failed")
		return
	}
	result, err := s.DB.Exec("UPDATE users SET password=? WHERE id=? AND password=? AND role='admin' AND blocked=0", hash, i.User, previous)
	if err != nil {
		fail(w, 500, "password_update_failed")
		return
	}
	if changed, _ := result.RowsAffected(); changed != 1 {
		fail(w, 409, "revision_conflict")
		return
	}
	respond(w, 200, map[string]bool{"updated": true})
}

func (s *Server) latestServerSupply(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, "https://api.github.com/repos/MJSasha/mj-watch/releases/latest", nil)
	if err != nil {
		fail(w, 502, "supply_unavailable")
		return
	}
	var release struct {
		Assets []struct{ Name string }
	}
	resp, err := s.Client.Do(req)
	if readJSONResponse(resp, err, &release) == nil {
		for _, asset := range release.Assets {
			if match := serverSupplyAsset.FindStringSubmatch(asset.Name); len(match) == 2 {
				respond(w, 200, map[string]string{"version": match[1]})
				return
			}
		}
	}
	fail(w, 502, "supply_unavailable")
}

func (s *Server) managementCall(ctx context.Context, route string, payload map[string]any) (map[string]any, error) {
	conn, err := s.openManagement(ctx, route, payload)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	var result map[string]any
	err = json.NewDecoder(io.LimitReader(conn, 4<<20)).Decode(&result)
	return result, err
}

func (s *Server) openManagement(ctx context.Context, route string, payload map[string]any) (net.Conn, error) {
	socket := s.managementSocket
	if socket == "" {
		socket = defaultManagementSocket
	}
	dial := net.Dialer{Timeout: 2 * time.Second}
	conn, err := dial.DialContext(ctx, "unix", socket)
	if err != nil {
		return nil, err
	}
	if err = conn.SetDeadline(time.Now().Add(24 * time.Second)); err != nil {
		conn.Close()
		return nil, err
	}
	request := map[string]any{"route": route, "payload": payload}
	if err = json.NewEncoder(conn).Encode(request); err != nil {
		conn.Close()
		return nil, err
	}
	return conn, nil
}
