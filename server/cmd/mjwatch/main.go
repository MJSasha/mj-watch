package main

import (
	"context"
	"crypto/rand"
	"crypto/tls"
	"dev.mjwatch/server/internal/control"
	"dev.mjwatch/server/internal/service"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

func main() {
	if e := run(); e != nil {
		fmt.Fprintln(os.Stderr, "MJWatch operation failed:", e)
		os.Exit(1)
	}
}
func run() error {
	args := os.Args[1:]
	if len(args) == 1 && args[0] == "version" {
		return json.NewEncoder(os.Stdout).Encode(map[string]any{"version": service.Version, "apiVersion": 1, "schemaVersion": 1})
	}
	if len(args) < 2 {
		return fmt.Errorf("usage: mjwatch <serve|bootstrap|torrserver|restore|password|init-key> config.json")
	}
	command, path := args[0], args[1]
	if command == "init-key" {
		if os.Geteuid() != 0 {
			return fmt.Errorf("root_required")
		}
		if _, e := os.Stat(path); e == nil {
			return nil
		}
		key := make([]byte, 32)
		rand.Read(key)
		return os.WriteFile(path, key, 0600)
	}
	raw, e := os.ReadFile(path)
	if e != nil {
		return e
	}
	var c service.Config
	if e = json.Unmarshal(raw, &c); e != nil {
		return e
	}
	if command != "serve" && os.Geteuid() != 0 {
		return fmt.Errorf("root_required")
	}
	if command == "torr-operation" {
		var input struct{ OperationID string }
		if e = json.NewDecoder(io.LimitReader(os.Stdin, 4096)).Decode(&input); e != nil {
			return e
		}
		out, e := control.ReadOperation("/var/lib/mjwatch-install/torrserver", input.OperationID)
		if e != nil {
			return e
		}
		return json.NewEncoder(os.Stdout).Encode(out)
	}
	if command == "torr-apply-file" {
		if len(args) != 3 {
			return fmt.Errorf("request_path")
		}
		path := args[2]
		if filepath.Dir(path) != "/var/lib/mjwatch-install/requests" || strings.Contains(filepath.Base(path), "..") {
			return fmt.Errorf("request_path")
		}
		info, err := os.Lstat(path)
		if err != nil {
			return err
		}
		if !info.Mode().IsRegular() || info.Mode().Perm()&0077 != 0 {
			return fmt.Errorf("unsafe_request_file")
		}
		raw, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		os.Remove(path)
		var input control.Apply
		if e = json.Unmarshal(raw, &input); e != nil {
			return e
		}
		ctl := control.TorrControl{URL: c.TorrURL, User: c.TorrUser, Password: c.TorrPassword, StateDir: "/var/lib/mjwatch-install/torrserver"}
		_, e = ctl.Apply(input)
		if e != nil {
			if len(input.ID) >= 16 && !strings.ContainsAny(input.ID, "/\\.") {
				out, _ := json.Marshal(map[string]string{"state": "failed", "code": e.Error()})
				os.MkdirAll(ctl.StateDir, 0700)
				os.WriteFile(filepath.Join(ctl.StateDir, input.ID+".error.json"), out, 0600)
			}
			return e
		}
		return nil
	}
	if command == "torrserver" {
		if len(args) < 3 {
			return fmt.Errorf("settings_read_or_apply_required")
		}
		ctl := control.TorrControl{URL: c.TorrURL, User: c.TorrUser, Password: c.TorrPassword, StateDir: "/var/lib/mjwatch-install/torrserver"}
		var out map[string]any
		switch args[2] {
		case "read":
			out, e = ctl.Read()
		case "apply":
			var input control.Apply
			e = json.NewDecoder(io.LimitReader(os.Stdin, 65536)).Decode(&input)
			if e == nil {
				out, e = ctl.Apply(input)
			}
		default:
			return fmt.Errorf("operation_not_allowed")
		}
		if e != nil {
			return e
		}
		return json.NewEncoder(os.Stdout).Encode(out)
	}
	if command == "restore" {
		var v struct{ Archive, Passphrase, Destination string }
		if e = json.NewDecoder(io.LimitReader(os.Stdin, 8192)).Decode(&v); e != nil {
			return e
		}
		if filepath.Dir(v.Archive) != c.BackupDir || v.Destination != "/var/lib/mjwatch-restore" {
			return fmt.Errorf("invalid_restore_path")
		}
		return service.ExtractBackup(v.Archive, v.Passphrase, v.Destination)
	}
	db, e := service.Open(c.Database)
	if e != nil {
		return e
	}
	defer db.Close()
	if command == "identity" {
		var id string
		if e = db.QueryRow("SELECT value FROM settings WHERE key='server_id'").Scan(&id); e != nil {
			return e
		}
		return json.NewEncoder(os.Stdout).Encode(map[string]string{"serverId": id})
	}
	if command == "bootstrap" {
		var v struct{ Device, OperationID string }
		if e = json.NewDecoder(io.LimitReader(os.Stdin, 4096)).Decode(&v); e != nil {
			return e
		}
		if len(v.OperationID) < 16 || strings.ContainsAny(v.OperationID, "/\\.") {
			return fmt.Errorf("operation_id")
		}
		receipt := filepath.Join("/var/lib/mjwatch-install", v.OperationID+".bootstrap")
		if b, e := os.ReadFile(receipt); e == nil {
			var receipt map[string]any
			json.Unmarshal(b, &receipt)
			if service.ReceiptValid(db, receipt) {
				_, e = os.Stdout.Write(b)
				return e
			}
		}
		out, e := service.Bootstrap(db, "Владелец", v.Device)
		if e != nil {
			return e
		}
		ca, e := os.ReadFile(filepath.Join(filepath.Dir(c.Certificate), "ca.crt"))
		if e != nil {
			return e
		}
		out["ca"] = string(ca)
		out["listen"] = c.Listen
		out["version"] = service.Version
		b, _ := json.Marshal(out)
		if e = os.WriteFile(receipt, b, 0600); e != nil {
			return e
		}
		_, e = os.Stdout.Write(b)
		return e
	}
	if command == "backup" {
		var v struct{ Passphrase, Destination string }
		if e = json.NewDecoder(io.LimitReader(os.Stdin, 8192)).Decode(&v); e != nil {
			return e
		}
		if filepath.Dir(v.Destination) != c.BackupDir {
			return fmt.Errorf("backup_path")
		}
		return service.CreateBackup(context.Background(), db, c, v.Passphrase, v.Destination)
	}
	if command == "password" {
		var v struct{ User, Password string }
		if e = json.NewDecoder(io.LimitReader(os.Stdin, 8192)).Decode(&v); e != nil {
			return e
		}
		hash, e := service.Password(v.Password)
		if e != nil {
			return e
		}
		_, e = db.Exec("UPDATE users SET password=? WHERE id=? AND role='admin'", hash, v.User)
		return e
	}
	if command != "serve" {
		return fmt.Errorf("unknown_command")
	}
	if !strings.HasPrefix(c.TorrURL, "http://127.0.0.1:") {
		return fmt.Errorf("torrserver_must_be_loopback")
	}
	s := service.New(db, c)
	go s.Maintain(context.Background())
	server := http.Server{Addr: c.Listen, Handler: s, ReadHeaderTimeout: 10 * time.Second, ReadTimeout: 30 * time.Second, IdleTimeout: 90 * time.Second, MaxHeaderBytes: 32768, ErrorLog: log.New(io.Discard, "", 0)}
	server.TLSConfig = &tls.Config{MinVersion: tls.VersionTLS12, GetCertificate: func(*tls.ClientHelloInfo) (*tls.Certificate, error) {
		cert, e := tls.LoadX509KeyPair(c.Certificate, c.PrivateKey)
		for attempt := 0; e != nil && attempt < 3; attempt++ {
			time.Sleep(10 * time.Millisecond)
			cert, e = tls.LoadX509KeyPair(c.Certificate, c.PrivateKey)
		}
		return &cert, e
	}}
	return server.ListenAndServeTLS("", "")
}
