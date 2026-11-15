package service

import (
	"archive/tar"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"database/sql"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"golang.org/x/crypto/scrypt"
	"io"
	"modernc.org/sqlite"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

func BackupDB(ctx context.Context, db *sql.DB, destination string) error {
	conn, e := db.Conn(ctx)
	if e != nil {
		return e
	}
	defer conn.Close()
	return conn.Raw(func(driver any) error {
		b, e := driver.(interface {
			NewBackup(string) (*sqlite.Backup, error)
		}).NewBackup(destination)
		if e != nil {
			return e
		}
		defer b.Finish()
		for {
			select {
			case <-ctx.Done():
				return ctx.Err()
			default:
			}
			more, e := b.Step(128)
			if e != nil {
				return e
			}
			if !more {
				return nil
			}
		}
	})
}

// Each authenticated record contains its ordinal in AAD; a final empty record detects truncation.
func cryptArchive(in io.Reader, out io.Writer, pass string, encrypt bool) error {
	if len(pass) < 16 || len(pass) > 1024 {
		return errors.New("passphrase_length")
	}
	salt := make([]byte, 32)
	if encrypt {
		rand.Read(salt)
		if _, e := out.Write(append([]byte("MJWB1"), salt...)); e != nil {
			return e
		}
	} else {
		magic := make([]byte, 5)
		if _, e := io.ReadFull(in, magic); e != nil || string(magic) != "MJWB1" {
			return errors.New("archive_format")
		}
		if _, e := io.ReadFull(in, salt); e != nil {
			return e
		}
	}
	key, e := scrypt.Key([]byte(pass), salt, 32768, 8, 1, 32)
	if e != nil {
		return e
	}
	block, e := aes.NewCipher(key)
	if e != nil {
		return e
	}
	gcm, e := cipher.NewGCM(block)
	if e != nil {
		return e
	}
	buf := make([]byte, 64*1024)
	for seq := uint64(0); ; seq++ {
		aad := make([]byte, 8)
		binary.BigEndian.PutUint64(aad, seq)
		if encrypt {
			n, e := io.ReadFull(in, buf)
			if e != nil && e != io.EOF && e != io.ErrUnexpectedEOF {
				return e
			}
			nonce := make([]byte, gcm.NonceSize())
			rand.Read(nonce)
			sealed := gcm.Seal(nonce, nonce, buf[:n], aad)
			if e := binary.Write(out, binary.BigEndian, uint32(len(sealed))); e != nil {
				return e
			}
			if _, e := out.Write(sealed); e != nil {
				return e
			}
			if n == 0 {
				return nil
			}
		} else {
			var n uint32
			if e := binary.Read(in, binary.BigEndian, &n); e != nil {
				return e
			}
			if n < 28 || n > 64*1024+28 {
				return errors.New("archive_record")
			}
			raw := make([]byte, n)
			if _, e := io.ReadFull(in, raw); e != nil {
				return e
			}
			plain, e := gcm.Open(nil, raw[:12], raw[12:], aad)
			if e != nil {
				return errors.New("archive_authentication")
			}
			if len(plain) == 0 {
				tail := make([]byte, 1)
				n, e := in.Read(tail)
				if n != 0 || e != io.EOF {
					return errors.New("archive_trailing_data")
				}
				return nil
			}
			if _, e = out.Write(plain); e != nil {
				return e
			}
		}
	}
}
func CreateBackup(ctx context.Context, db *sql.DB, c Config, pass, dest string) error {
	tmp, e := os.MkdirTemp(filepath.Dir(dest), ".backup-")
	if e != nil {
		return e
	}
	defer os.RemoveAll(tmp)
	snapshot := filepath.Join(tmp, "state.db")
	if e = BackupDB(ctx, db, snapshot); e != nil {
		return e
	}
	copyDB, err := sql.Open("sqlite", snapshot)
	if err != nil {
		return err
	}
	_, err = copyDB.Exec("PRAGMA secure_delete=ON; DELETE FROM cache; DELETE FROM snapshots; DELETE FROM snapshot_entities; DELETE FROM settings WHERE key='backup_passphrase'; VACUUM;")
	copyDB.Close()
	if err != nil {
		return err
	}
	archive, e := os.OpenFile(filepath.Join(tmp, "archive.tar"), os.O_CREATE|os.O_EXCL|os.O_RDWR, 0600)
	if e != nil {
		return e
	}
	defer archive.Close()
	tw := tar.NewWriter(archive)
	files := map[string]string{"state.db": snapshot, "secret.key": c.SecretKey, "tls.crt": c.Certificate, "tls.key": c.PrivateKey, "ca.crt": filepath.Join(filepath.Dir(c.Certificate), "ca.crt"), "ca.key": filepath.Join(filepath.Dir(c.Certificate), "ca.key")}
	for name, path := range files {
		f, e := os.Open(path)
		if e != nil {
			return e
		}
		info, e := f.Stat()
		if e != nil {
			f.Close()
			return e
		}
		if e = tw.WriteHeader(&tar.Header{Name: name, Mode: 0600, Size: info.Size()}); e != nil {
			f.Close()
			return e
		}
		_, e = io.CopyBuffer(tw, f, make([]byte, 32768))
		f.Close()
		if e != nil {
			return e
		}
	}
	versions, _ := json.Marshal(map[string]any{"serverVersion": Version, "apiVersion": 1, "schemaVersion": 1})
	tw.WriteHeader(&tar.Header{Name: "versions.json", Mode: 0600, Size: int64(len(versions))})
	tw.Write(versions)
	raw, _ := json.Marshal(c)
	tw.WriteHeader(&tar.Header{Name: "config.json", Mode: 0600, Size: int64(len(raw))})
	tw.Write(raw)
	tw.Close()
	archive.Seek(0, 0)
	f, e := os.OpenFile(dest+".part", os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if e != nil {
		return e
	}
	defer os.Remove(dest + ".part")
	e = cryptArchive(archive, f, pass, true)
	if e == nil {
		e = f.Sync()
	}
	f.Close()
	if e != nil {
		return e
	}
	return os.Rename(dest+".part", dest)
}
func ExtractBackup(path, pass, destination string) error {
	f, e := os.Open(path)
	if e != nil {
		return e
	}
	defer f.Close()
	tmp, e := os.CreateTemp(filepath.Dir(destination), ".restore-")
	if e != nil {
		return e
	}
	defer os.Remove(tmp.Name())
	defer tmp.Close()
	if e = cryptArchive(f, tmp, pass, false); e != nil {
		return e
	}
	tmp.Seek(0, 0)
	tr := tar.NewReader(tmp)
	if e = os.Mkdir(destination, 0700); e != nil {
		return e
	}
	success := false
	defer func() {
		if !success {
			os.RemoveAll(destination)
		}
	}()
	allowed := map[string]bool{"state.db": true, "secret.key": true, "tls.crt": true, "tls.key": true, "ca.crt": true, "ca.key": true, "config.json": true, "versions.json": true}
	for {
		h, e := tr.Next()
		if e == io.EOF {
			break
		}
		if e != nil {
			return e
		}
		if !allowed[h.Name] || h.Typeflag != tar.TypeReg || h.Size > 2<<30 {
			return errors.New("archive_entry")
		}
		delete(allowed, h.Name)
		out, e := os.OpenFile(filepath.Join(destination, h.Name), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
		if e != nil {
			return e
		}
		_, e = io.CopyN(out, tr, h.Size)
		out.Close()
		if e != nil {
			return e
		}
	}
	delete(allowed, "versions.json")
	if b, e := os.ReadFile(filepath.Join(destination, "versions.json")); e == nil {
		var v struct{ SchemaVersion int }
		if json.Unmarshal(b, &v) != nil || v.SchemaVersion != 1 {
			return errors.New("incompatible_schema")
		}
	}
	if len(allowed) != 0 {
		return errors.New("archive_incomplete")
	}
	db, e := Open(filepath.Join(destination, "state.db"))
	if e != nil {
		return e
	}
	defer db.Close()
	var integrity string
	if e = db.QueryRow("PRAGMA integrity_check").Scan(&integrity); e != nil || integrity != "ok" {
		return errors.New("database_corrupt")
	}
	tx, e := db.Begin()
	if e != nil {
		return e
	}
	defer tx.Rollback()
	for _, table := range []string{"sessions", "devices", "invites", "pairing", "mutations", "cache", "snapshots"} {
		if _, e = tx.Exec("DELETE FROM " + table); e != nil {
			return e
		}
	}
	if _, e = tx.Exec("UPDATE settings SET value=? WHERE key='server_epoch'", Token()); e != nil {
		return e
	}
	if e = tx.Commit(); e != nil {
		return e
	}
	success = true
	return nil
}
func (s *Server) backups(w http.ResponseWriter, r *http.Request, path string, i identity) {
	dir := s.Config.BackupDir
	if dir == "" {
		fail(w, 503, "backup_unconfigured")
		return
	}
	if e := os.MkdirAll(dir, 0700); e != nil {
		fail(w, 503, "backup_storage")
		return
	}
	if path == "admin/backups" && r.Method == "GET" {
		entries, e := os.ReadDir(dir)
		if e != nil {
			fail(w, 503, "backup_storage")
			return
		}
		out := []any{}
		for _, f := range entries {
			if strings.HasSuffix(f.Name(), ".mjwb") && !f.IsDir() {
				info, e := f.Info()
				if e == nil {
					out = append(out, map[string]any{"id": f.Name(), "size": info.Size(), "created": info.ModTime().Unix()})
				}
			}
		}
		respond(w, 200, map[string]any{"backups": out})
		return
	}
	if path == "admin/backups" && r.Method == "POST" {
		var v struct{ Passphrase string }
		if !decode(w, r, &v) {
			return
		}
		if len(v.Passphrase) < 16 {
			fail(w, 400, "passphrase_length")
			return
		}
		id := fmt.Sprintf("%d-%s.mjwb", time.Now().Unix(), Token()[:12])
		if e := CreateBackup(r.Context(), s.DB, s.Config, v.Passphrase, filepath.Join(dir, id)); e != nil {
			fail(w, 503, "backup_failed")
			return
		}
		respond(w, 200, map[string]string{"id": id})
		return
	}
	if strings.HasSuffix(path, "/download") && r.Method == "POST" {
		parts := strings.Split(path, "/")
		if len(parts) != 4 || filepath.Base(parts[2]) != parts[2] || !strings.HasSuffix(parts[2], ".mjwb") {
			fail(w, 404, "not_found")
			return
		}
		var v struct{ Password string }
		if !decode(w, r, &v) {
			return
		}
		var encoded string
		s.DB.QueryRow("SELECT password FROM users WHERE id=?", i.User).Scan(&encoded)
		select {
		case s.passwordSlots <- struct{}{}:
			defer func() { <-s.passwordSlots }()
		default:
			fail(w, 429, "authentication_busy")
			return
		}
		if !CheckPassword(encoded, v.Password) {
			fail(w, 401, "reauthentication_required")
			return
		}
		f, e := os.Open(filepath.Join(dir, parts[2]))
		if e != nil {
			fail(w, 404, "not_found")
			return
		}
		defer f.Close()
		w.Header().Set("Content-Type", "application/octet-stream")
		io.CopyBuffer(w, f, make([]byte, 32768))
		return
	}
	fail(w, 404, "not_found")
}
