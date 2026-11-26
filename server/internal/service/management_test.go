package service

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
)

func TestHTTPSManagementRequiresAdminAndReauthenticatesMaintenance(t *testing.T) {
	s, admin := fixture(t)
	_, invite := call(t, s, admin, "POST", "admin/users", map[string]string{"name": "viewer"})
	_, login := call(t, s, "", "POST", "auth/accept-invite", map[string]string{
		"invite": invite["invite"].(string), "password": "viewer-password", "device": "test",
	})
	viewer := login["accessToken"].(string)
	for route, method := range map[string]string{
		"admin/host/torr/read": "GET", "admin/host/maintenance/start": "POST",
		"admin/host/prowlarr/install": "POST", "admin/host/jacred/web": "POST",
		"admin/host/prowlarr/websocket": "GET", "admin/owner/password": "POST",
	} {
		if code, _ := call(t, s, viewer, method, route, nil); code != 403 {
			t.Fatalf("viewer accessed %s: %d", route, code)
		}
	}
	if code, _ := call(t, s, "", "GET", "admin/host/torr/read", nil); code != 401 {
		t.Fatal(code)
	}
	const password = "owner-fixture-password"
	if code, body := call(t, s, admin, "POST", "admin/owner/password", map[string]string{"password": password}); code != 200 {
		t.Fatal(code, body)
	}
	if code, _ := call(t, s, admin, "POST", "admin/host/maintenance/start", map[string]string{"action": "restart"}); code != 403 {
		t.Fatal(code)
	}
	listener := managementListener(t, s)
	got := make(chan map[string]any, 1)
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		var request map[string]any
		json.NewDecoder(conn).Decode(&request)
		got <- request
		io.WriteString(conn, `{"state":"running"}`)
	}()
	if code, _ := call(t, s, admin, "POST", "admin/host/execute", map[string]string{"command": "restart"}); code != 404 {
		t.Fatal(code)
	}
	if code, body := call(t, s, admin, "POST", "admin/host/maintenance/start", map[string]any{
		"operationId": "synthetic-restart-operation", "confirmed": true, "action": "restart", "adminPassword": password,
	}); code != 200 || body["state"] != "running" {
		t.Fatal(code, body)
	}
	request := <-got
	payload := request["payload"].(map[string]any)
	if request["route"] != "maintenance/start" || payload["action"] != "restart" || payload["adminPassword"] != nil {
		t.Fatal("invalid local dispatch or password forwarded")
	}
}

func TestJacredAdminWebSocketUsesHTTPSAndBindsSession(t *testing.T) {
	s, admin := fixture(t)
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/settings" {
			http.NotFound(w, r)
			return
		}
		io.WriteString(w, "synthetic admin console")
	}))
	defer provider.Close()
	_, port, err := net.SplitHostPort(provider.Listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	portNumber, err := strconv.Atoi(port)
	if err != nil {
		t.Fatal(err)
	}
	listener := managementListener(t, s)
	closed := make(chan struct{})
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		var request map[string]any
		if json.NewDecoder(conn).Decode(&request) != nil || request["route"] != "jacred/web" {
			return
		}
		json.NewEncoder(conn).Encode(map[string]any{
			"port": portNumber, "token": strings.Repeat("x", 43), "cookie": "mjwatch_jacred_" + port,
		})
		io.Copy(io.Discard, conn)
		close(closed)
	}()
	const create = "admin/host/jacred/web"
	code, session := call(t, s, admin, "POST", create, nil)
	if code != 200 {
		t.Fatal(code, session)
	}
	id := session["id"].(string)
	other, err := Bootstrap(s.DB, "owner", "second-phone")
	if err != nil {
		t.Fatal(err)
	}
	upstream := httptest.NewTLSServer(s)
	defer upstream.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	dial := func(token string) (*websocket.Conn, *http.Response, error) {
		endpoint := upstream.URL + "/api/v1/admin/host/jacred/websocket?id=" + id
		return websocket.Dial(ctx, endpoint, &websocket.DialOptions{
			HTTPClient: upstream.Client(), HTTPHeader: http.Header{"Authorization": {"Bearer " + token}},
		})
	}
	if _, response, err := dial(other["accessToken"].(string)); err == nil || response == nil || response.StatusCode != 404 {
		t.Fatal("another device accessed the lease")
	}
	ws, _, err := dial(admin)
	if err != nil {
		t.Fatal(err)
	}
	defer ws.CloseNow()
	conn := websocket.NetConn(ctx, ws, websocket.MessageBinary)
	if _, err := io.WriteString(conn, "GET /settings HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"); err != nil {
		t.Fatal(err)
	}
	body, err := io.ReadAll(conn)
	if err != nil || !strings.Contains(string(body), "synthetic admin console") {
		t.Fatalf("relay: %q, %v", body, err)
	}
	if code, _ := call(t, s, admin, "DELETE", create+"?id="+id, nil); code != 200 {
		t.Fatal(code)
	}
	select {
	case <-closed:
	case <-ctx.Done():
		t.Fatal("admin session connection leaked")
	}
}

func managementListener(t *testing.T, s *Server) net.Listener {
	t.Helper()
	dir, err := os.MkdirTemp("/tmp", "mjw-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(dir) })
	s.managementSocket = filepath.Join(dir, "test.sock")
	listener, err := net.Listen("unix", s.managementSocket)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { listener.Close() })
	return listener
}
