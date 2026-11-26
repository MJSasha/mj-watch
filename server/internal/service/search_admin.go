package service

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/coder/websocket"
)

type searchWebSession struct {
	owner string
	port  int
	lease net.Conn
}

func (s *Server) jacredWebSession(w http.ResponseWriter, r *http.Request, i identity) {
	if r.Method == http.MethodDelete {
		s.closeSearchWeb(r.URL.Query().Get("id"), i.Session)
		respond(w, 200, map[string]bool{"closed": true})
		return
	}
	if r.Method != http.MethodPost {
		fail(w, 404, "not_found")
		return
	}
	id := Token()
	s.mu.Lock()
	if len(s.searchWebSessions) >= 3 {
		s.mu.Unlock()
		fail(w, 429, "search_admin_busy")
		return
	}
	if s.searchWebSessions == nil {
		s.searchWebSessions = make(map[string]searchWebSession)
	}
	s.searchWebSessions[id] = searchWebSession{owner: i.Session}
	s.mu.Unlock()

	var address struct {
		Port          int
		Token, Cookie string
	}
	conn, err := s.openManagement(r.Context(), "jacred/web", map[string]any{})
	if err == nil {
		err = json.NewDecoder(io.LimitReader(conn, 4096)).Decode(&address)
	}
	if err != nil || address.Port <= 1024 || address.Port == 19696 || address.Port > 65535 ||
		len(address.Token) != 43 || address.Cookie != "mjwatch_jacred_"+strconv.Itoa(address.Port) {
		if conn != nil {
			conn.Close()
		}
		s.closeSearchWeb(id, i.Session)
		fail(w, 503, "search_admin_unavailable")
		return
	}
	s.mu.Lock()
	s.searchWebSessions[id] = searchWebSession{i.Session, address.Port, conn}
	s.mu.Unlock()
	time.AfterFunc(29*time.Minute, func() { s.closeSearchWeb(id, i.Session) })
	w.Header().Set("Cache-Control", "no-store")
	respond(w, 200, map[string]string{"id": id, "token": address.Token, "cookie": address.Cookie})
}

func (s *Server) closeSearchWeb(id, owner string) {
	s.mu.Lock()
	session := s.searchWebSessions[id]
	if session.owner == owner {
		delete(s.searchWebSessions, id)
	}
	s.mu.Unlock()
	if session.owner == owner && session.lease != nil {
		session.lease.Close()
	}
}

func (s *Server) searchWebSocket(w http.ResponseWriter, r *http.Request, i identity, prowlarr bool) {
	if r.Method != http.MethodGet || r.Header.Get("Origin") != "" ||
		!strings.EqualFold(r.Header.Get("Upgrade"), "websocket") {
		fail(w, 404, "not_found")
		return
	}
	s.mu.Lock()
	if s.searchWebClients == nil {
		s.searchWebClients = make(map[string]int)
	}
	total := 0
	for _, count := range s.searchWebClients {
		total += count
	}
	if s.searchWebClients[i.Session] >= 8 || total >= 24 {
		s.mu.Unlock()
		fail(w, 429, "search_admin_busy")
		return
	}
	port := 19696
	if !prowlarr {
		session := s.searchWebSessions[r.URL.Query().Get("id")]
		if session.owner != i.Session || session.port == 0 {
			s.mu.Unlock()
			fail(w, 404, "search_admin_unavailable")
			return
		}
		port = session.port
	}
	s.searchWebClients[i.Session]++
	s.mu.Unlock()
	defer func() {
		s.mu.Lock()
		if s.searchWebClients[i.Session]--; s.searchWebClients[i.Session] == 0 {
			delete(s.searchWebClients, i.Session)
		}
		s.mu.Unlock()
	}()

	target, err := net.DialTimeout("tcp4", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)), 3*time.Second)
	if err != nil {
		fail(w, 503, "search_admin_unavailable")
		return
	}
	defer target.Close()
	ws, err := websocket.Accept(w, r, nil)
	if err != nil {
		return
	}
	defer ws.CloseNow()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	go func() {
		ticker := time.NewTicker(10 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				identity, err := s.identity(token)
				if err != nil || identity.Role != "admin" {
					cancel()
					target.Close()
					return
				}
			}
		}
	}()
	stream := websocket.NetConn(ctx, ws, websocket.MessageBinary)
	ws.SetReadLimit(64 << 10)
	target.SetDeadline(time.Now().Add(2 * time.Minute))
	finished := make(chan struct{})
	go func() {
		io.Copy(target, stream)
		target.Close()
		close(finished)
	}()
	io.Copy(stream, target)
	ws.Close(websocket.StatusNormalClosure, "")
	cancel()
	<-finished
}
