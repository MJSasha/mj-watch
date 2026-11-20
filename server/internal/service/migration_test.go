package service

import (
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

func TestMigrationPreservesDeviceAndRefreshAcrossColdCopy(t *testing.T) {
	root := t.TempDir()
	dbpath := filepath.Join(root, "source.db")
	db, err := Open(dbpath)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	account, err := Bootstrap(db, "owner", "phone")
	if err != nil {
		t.Fatal(err)
	}
	key := filepath.Join(root, "secret.key")
	if err := os.WriteFile(key, []byte("01234567890123456789012345678901"), 0600); err != nil {
		t.Fatal(err)
	}
	source := New(db, Config{Database: dbpath, SecretKey: key})
	token := account["accessToken"].(string)
	receipt := Mutation{MutationID: Token(), Kind: "progress", Key: "episode", Operation: "put", Value: json.RawMessage(`{"positionMs":4000}`), ServerEpoch: value(db, "server_epoch"), HistoryEpoch: 1}
	status, original := call(t, source, token, "POST", "mutations", receipt)
	if status != 200 {
		t.Fatal(original)
	}
	sealed, err := source.sealCredential("existing-TMDB-secret")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = db.Exec("INSERT INTO settings VALUES('tmdb_credential',?)", sealed); err != nil {
		t.Fatal(err)
	}
	move := Relocation{Code: "migration_in_progress", ServerID: value(db, "server_id"), ServerEpoch: receipt.ServerEpoch, MigrationID: Token(), BaseURL: "https://new.example:8443", CA: "new private CA"}
	source.Config.Relocation = &move
	if status, _ := call(t, source, "", "POST", "auth/refresh", map[string]any{"refreshToken": account["refreshToken"]}); status != 503 {
		t.Fatal("refresh escaped fence", status)
	}

	// Exercise the same SQLite backup, SSH tar encoding and restricted staging decoder
	// as the workstation tool. This includes committed entries still in the source WAL.
	raw, _ := json.Marshal(move)
	copyScript := `
import io, json, pathlib, sys
sys.path.insert(0, '../../packaging')
import migration_host as m
root = pathlib.Path(sys.argv[1])
m.ROOT = root
m.TORR = root/'torr'
m.TORR.mkdir()
(m.TORR/'accs.db').write_text('local-only')
(m.TORR/'settings.db').write_text('torrent-state')
(m.TORR/'cache').mkdir()
(m.TORR/'cache'/'piece').touch()
backups = root/'backups'
backups.mkdir()
(backups/'history.mjwb').write_text('encrypted-archive')
archive = io.BytesIO()
move = json.loads(sys.argv[2])
m.export(dict(database=str(root/'source.db'), secretKey=str(root/'secret.key'), backupDir=str(backups)), move, archive)
archive.seek(0)
target = root/'target'
m.receive(target, move, archive)
assert (target/'torr/settings.db').read_text() == 'torrent-state'
assert not (target/'torr/accs.db').exists() and not (target/'torr/cache').exists()
assert (target/'backups/history.mjwb').read_text() == 'encrypted-archive'
`
	cmd := exec.Command("python3", "-c", copyScript, root, string(raw))
	cmd.Env = append(os.Environ(), "PYTHONDONTWRITEBYTECODE=1")
	if output, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("copy: %v\n%s", err, output)
	}
	destDB, err := Open(filepath.Join(root, "target", "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer destDB.Close()
	dest := New(destDB, Config{SecretKey: filepath.Join(root, "target", "secret.key")})
	if status, proof := call(t, dest, "", "GET", "server/identity", nil); status != 200 || proof["serverId"] != move.ServerID || proof["serverEpoch"] != move.ServerEpoch || proof["migrationId"] != move.MigrationID {
		t.Fatal(status, proof)
	}
	if status, me := call(t, dest, token, "GET", "me", nil); status != 200 || me["deviceId"] != account["deviceId"] || me["userId"] != account["userId"] {
		t.Fatal(status, me)
	}
	if recovered, err := dest.openSealed(value(destDB, "tmdb_credential")); err != nil || recovered != "existing-TMDB-secret" {
		t.Fatal("credential recovery", err)
	}
	if status, result := call(t, dest, token, "POST", "mutations", receipt); status != 200 || result["entity"].(map[string]any)["revision"] != original["entity"].(map[string]any)["revision"] {
		t.Fatal(status, result)
	}
	if status, session := call(t, dest, "", "POST", "auth/refresh", map[string]any{"refreshToken": account["refreshToken"]}); status != 200 || session["refreshToken"] == account["refreshToken"] {
		t.Fatal(status, session)
	}
	move.Code = "server_moved"
	if status, moved := call(t, source, "", "POST", "auth/refresh", nil); status != 409 || moved["ca"] != move.CA || moved["migrationId"] != move.MigrationID {
		t.Fatal(status, moved)
	}
	source.Config.PriorMigrationID = "previous-transfer"
	if status, proof := call(t, source, "", "GET", "server/identity", nil); status != 200 || proof["migrationId"] != "previous-transfer" {
		t.Fatal(status, proof)
	}
}
