// Fixture-only executable. Never included in the server supply or APK.
package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"dev.mjwatch/server/internal/service"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"log"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"time"
)

func main() {
	if len(os.Args) != 3 {
		log.Fatal("fixture requires directory and demo.mp4")
	}
	port := os.Getenv("MJWATCH_FIXTURE_PORT")
	if port == "" {
		port = "18443"
	}
	controlPort := os.Getenv("MJWATCH_FIXTURE_CONTROL_PORT")
	if controlPort == "" {
		controlPort = "18444"
	}
	listen := "127.0.0.1:" + port
	controlListen := "127.0.0.1:" + controlPort
	dir, video := os.Args[1], os.Args[2]
	os.MkdirAll(dir, 0700)
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	template := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "MJWatch isolated fixture"}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(48 * time.Hour), IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}, IPAddresses: []net.IP{net.ParseIP("127.0.0.1")}, DNSNames: []string{"localhost"}}
	der, _ := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	ca := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	private, _ := x509.MarshalECPrivateKey(key)
	os.WriteFile(filepath.Join(dir, "tls.crt"), ca, 0600)
	os.WriteFile(filepath.Join(dir, "ca.crt"), ca, 0600)
	os.WriteFile(filepath.Join(dir, "ca.key"), pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: private}), 0600)
	os.WriteFile(filepath.Join(dir, "tls.key"), pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: private}), 0600)
	secret := make([]byte, 32)
	rand.Read(secret)
	os.WriteFile(filepath.Join(dir, "secret.key"), secret, 0600)
	torr := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/echo":
			io.WriteString(w, "MatriX.145.2")
		case "/torrents", "/torrent/upload":
			w.Header().Set("Content-Type", "application/json")
			files := []any{map[string]any{"id": 17, "path": "fixture/movie.mp4", "length": 2400000}}
			if os.Getenv("MJWATCH_FIXTURE_REMOTE") == "1" {
				files = append(files, map[string]any{"id": 18, "path": "fixture/second.mp4", "length": 2400000})
			}
			json.NewEncoder(w).Encode(map[string]any{"hash": "0123456789012345678901234567890123456789", "file_stats": files})
		case "/stream":
			http.ServeFile(w, r, video)
		default:
			http.NotFound(w, r)
		}
	}))
	defer torr.Close()
	db, e := service.Open(filepath.Join(dir, "state.db"))
	if e != nil {
		log.Fatal(e)
	}
	defer db.Close()
	config := service.Config{Database: filepath.Join(dir, "state.db"), TorrURL: torr.URL, Certificate: filepath.Join(dir, "tls.crt"), PrivateKey: filepath.Join(dir, "tls.key"), SecretKey: filepath.Join(dir, "secret.key"), BackupDir: filepath.Join(dir, "backups")}
	server := service.New(db, config)
	mux := http.NewServeMux()
	mux.Handle("/api/v1/", server)
	mux.HandleFunc("/fixture-source/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if !strings.Contains(r.URL.Path, "results") {
			http.NotFound(w, r)
			return
		}
		io.WriteString(w, `{"Results":[{"Title":"Managed fixture 720p","Tracker":"fixture","MagnetUri":"magnet:?xt=urn:btih:0123456789012345678901234567890123456789","Seeders":1}]}`)
	})
	go func() {
		control := http.NewServeMux()
		control.HandleFunc("/torrent", func(w http.ResponseWriter, r *http.Request) {
			http.ServeFile(w, r, "/private/tmp/mjwatch-seed.torrent")
		})
		control.HandleFunc("/ssh-target", func(w http.ResponseWriter, r *http.Request) {
			b, e := os.ReadFile("/private/tmp/mjwatch-ssh-fixture.json")
			if e != nil {
				http.Error(w, "fixture_unavailable", 503)
				return
			}
			w.Header().Set("Content-Type", "application/json")
			w.Write(b)
		})
		control.HandleFunc("/bootstrap", func(w http.ResponseWriter, r *http.Request) {
			out, e := service.Bootstrap(db, "owner", "fixture-device")
			if e != nil {
				http.Error(w, "fixture_failed", 500)
				return
			}
			out["ca"] = string(ca)
			out["url"] = "https://" + listen
			w.Header().Set("Content-Type", "application/json")
			json.NewEncoder(w).Encode(out)
		})
		if e := http.ListenAndServe(controlListen, control); e != nil {
			log.Fatal(e)
		}
	}()
	fmt.Printf("Isolated fixture listening on %s (TLS) and %s (test bootstrap)\n", listen, controlListen)
	log.Fatal(http.ListenAndServeTLS(listen, config.Certificate, config.PrivateKey, mux))
}
