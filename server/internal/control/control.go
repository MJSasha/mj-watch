package control

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strconv"
	"strings"
	"syscall"
	"time"
)

type Field struct {
	Name     string `json:"name"`
	Label    string `json:"label"`
	Min      int64  `json:"min"`
	Max      int64  `json:"max"`
	Boolean  bool   `json:"boolean"`
	Negative bool   `json:"negative"`
	Unit     string `json:"unit"`
}

var Fields = []Field{
	{"TorrentsSavePath", "Каталог дискового кэша", 0, 0, false, false, "path"},
	{"CacheSize", "Размер кэша", 16 << 20, 8 << 30, false, false, "байт"}, {"PreloadCache", "Предзагрузка", 0, 100, false, false, "%"}, {"ReaderReadAHead", "Чтение вперёд", 5, 100, false, false, "%"},
	{"UseDisk", "Дисковый кэш", 0, 0, true, false, ""}, {"RemoveCacheOnDrop", "Удалять кэш при выгрузке", 0, 0, true, false, ""},
	{"DownloadRateLimit", "Лимит скачивания", 0, 1048576, false, false, "KiB/s"}, {"UploadRateLimit", "Лимит отдачи", 0, 1048576, false, false, "KiB/s"},
	{"ConnectionsLimit", "Соединения", 10, 1000, false, false, ""}, {"TorrentDisconnectTimeout", "Таймаут отключения", 0, 86400, false, false, "с"},
	{"ForceEncrypt", "Шифрование пиров", 0, 0, true, false, ""}, {"DisableUpload", "Разрешить отдачу", 0, 0, true, true, ""},
	{"DisableTCP", "TCP", 0, 0, true, true, ""}, {"DisableUTP", "uTP", 0, 0, true, true, ""}, {"DisableDHT", "DHT", 0, 0, true, true, ""}, {"DisablePEX", "PEX", 0, 0, true, true, ""},
	{"EnableIPv6", "IPv6", 0, 0, true, false, ""}, {"DisableUPNP", "UPnP", 0, 0, true, true, ""}, {"PeersListenPort", "Peer-порт", 1024, 65535, false, false, ""}, {"RetrackersMode", "Ретрекеры", 0, 3, false, false, ""}, {"ResponsiveMode", "Режим быстрого чтения", 0, 0, true, false, ""},
}

type TorrControl struct {
	URL, User, Password, StateDir string
	Client                        *http.Client
}

func (c TorrControl) request(path string, input any, out any) error {
	var b io.Reader
	if input != nil {
		raw, _ := json.Marshal(input)
		b = bytes.NewReader(raw)
	}
	method := "GET"
	if b != nil {
		method = "POST"
	}
	req, e := http.NewRequest(method, c.URL+path, b)
	if e != nil {
		return e
	}
	req.Header.Set("Content-Type", "application/json")
	req.SetBasicAuth(c.User, c.Password)
	client := c.Client
	if client == nil {
		client = &http.Client{Timeout: 20 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	}
	resp, e := client.Do(req)
	if e != nil {
		return fmt.Errorf("torrserver_unavailable")
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("torrserver_http_%d", resp.StatusCode)
	}
	if out != nil {
		if str, ok := out.(*string); ok {
			raw, e := io.ReadAll(io.LimitReader(resp.Body, 1024))
			*str = strings.TrimSpace(string(raw))
			return e
		}
		decoder := json.NewDecoder(io.LimitReader(resp.Body, 2<<20))
		decoder.UseNumber()
		return decoder.Decode(out)
	}
	return nil
}
func revision(v map[string]any) string {
	b, _ := json.Marshal(v)
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}
func (c TorrControl) Read() (map[string]any, error) {
	var version string
	if e := c.request("/echo", nil, &version); e != nil {
		return nil, e
	}
	var settings map[string]any
	if e := c.request("/settings", map[string]string{"action": "get"}, &settings); e != nil {
		return nil, e
	}
	for _, field := range Fields {
		if !field.Boolean && field.Unit != "path" {
			if n, ok := settings[field.Name].(json.Number); ok {
				value, err := n.Float64()
				if err != nil {
					return nil, fmt.Errorf("invalid_settings_number")
				}
				settings[field.Name] = value
			}
		}
	}
	var stats []any
	c.request("/torrents", map[string]string{"action": "list"}, &stats)
	active := 0
	for _, v := range stats {
		if item, ok := v.(map[string]any); ok {
			if n, ok := item["active_streams"].(json.Number); ok {
				v, _ := n.Int64()
				active += int(v)
			}
		}
	}
	return map[string]any{"version": version, "editable": version == "MatriX.145.2", "settings": settings, "revision": revision(settings), "fields": Fields, "activeStreams": active}, nil
}

type Apply struct {
	ID        string         `json:"operationId"`
	Revision  string         `json:"revision"`
	Changes   map[string]any `json:"changes"`
	Confirmed bool           `json:"confirmed"`
	Defaults  bool           `json:"defaults"`
}

func (c TorrControl) Apply(v Apply) (map[string]any, error) {
	if len(v.ID) < 16 || len(v.ID) > 128 || strings.ContainsAny(v.ID, "/\\.") {
		return nil, fmt.Errorf("operation_id")
	}
	if e := os.MkdirAll(c.StateDir, 0700); e != nil {
		return nil, e
	}
	lock, e := os.OpenFile(filepath.Join(filepath.Dir(c.StateDir), "lock"), os.O_CREATE|os.O_RDWR, 0600)
	if e != nil {
		return nil, e
	}
	defer lock.Close()
	if e = syscall.Flock(int(lock.Fd()), syscall.LOCK_EX); e != nil {
		return nil, e
	}
	defer syscall.Flock(int(lock.Fd()), syscall.LOCK_UN)
	file := filepath.Join(c.StateDir, v.ID+".json")
	if raw, e := os.ReadFile(file); e == nil {
		var previous map[string]any
		if json.Unmarshal(raw, &previous) == nil {
			return previous, nil
		}
	}
	current, e := c.Read()
	if e != nil {
		return nil, e
	}
	if current["editable"] != true {
		return nil, fmt.Errorf("unsupported_version")
	}
	intentPath := filepath.Join(c.StateDir, v.ID+".pending.json")
	var intent map[string]string
	if raw, err := os.ReadFile(intentPath); err == nil {
		json.Unmarshal(raw, &intent)
		if current["revision"] == intent["targetRevision"] {
			raw, _ := json.Marshal(current)
			if err = os.WriteFile(file, raw, 0600); err != nil {
				return nil, err
			}
			os.Remove(intentPath)
			return current, nil
		}
	}
	if current["revision"] != v.Revision {
		return nil, fmt.Errorf("revision_conflict")
	}
	if !v.Confirmed {
		return nil, fmt.Errorf("stream_confirmation_required")
	}
	settings := current["settings"].(map[string]any)
	before := map[string]any{}
	for k, v := range settings {
		before[k] = v
	}
	if e = Validate(v.Changes); e != nil {
		return nil, e
	}
	useDisk := settings["UseDisk"] == true || v.Changes["UseDisk"] == true
	if useDisk {
		path, _ := settings["TorrentsSavePath"].(string)
		if changed, ok := v.Changes["TorrentsSavePath"].(string); ok {
			path = changed
		}
		if !safeCachePath(path) {
			return nil, fmt.Errorf("unsafe_cache_path")
		}
		// All path traversal and creation runs as the unprivileged cache owner.
		if e = exec.Command("runuser", "-u", "mjwatch-torr", "--", "mkdir", "-p", "--", path).Run(); e != nil {
			return nil, fmt.Errorf("cache_directory_unavailable")
		}
		var fs syscall.Statfs_t
		if e = syscall.Statfs(path, &fs); e != nil {
			return nil, e
		}
		size, _ := settings["CacheSize"].(float64)
		if n, ok := v.Changes["CacheSize"].(float64); ok {
			size = n
		}
		if uint64(fs.Bavail)*uint64(fs.Bsize) < uint64(size)+(2<<30) {
			return nil, fmt.Errorf("insufficient_cache_disk")
		}
	}
	for key, value := range v.Changes {
		settings[key] = value
	}
	settings["EnableDLNA"] = false
	settings["EnableBonjour"] = false
	settings["EnableLPD"] = false
	settings["EnableRutorSearch"] = false
	settings["EnableTorznabSearch"] = false
	settings["TrackTimecode"] = false
	raw, _ := json.Marshal(before)
	if e = os.WriteFile(filepath.Join(c.StateDir, "previous-settings.json"), raw, 0600); e != nil {
		return nil, e
	}
	if v.Defaults {
		if e = c.request("/settings", map[string]string{"action": "def"}, nil); e != nil {
			return nil, e
		}
		read, err := c.Read()
		if err != nil {
			c.request("/settings", map[string]any{"action": "set", "sets": before}, nil)
			return nil, fmt.Errorf("defaults_unconfirmed")
		}
		settings = read["settings"].(map[string]any)
		settings["EnableDLNA"] = false
		settings["EnableBonjour"] = false
		settings["EnableLPD"] = false
		settings["TrackTimecode"] = false
		settings["EnableRutorSearch"] = false
		settings["EnableTorznabSearch"] = false
		settings["TorrentsSavePath"] = "/var/lib/torrserver-mjwatch/cache"
	}
	desired := revision(settings)
	if intent != nil && intent["targetRevision"] != desired {
		return nil, fmt.Errorf("operation_id_reused")
	}
	pending, _ := json.Marshal(map[string]string{"previousRevision": v.Revision, "targetRevision": desired})
	if e = os.WriteFile(intentPath, pending, 0600); e != nil {
		return nil, e
	}
	if e = c.request("/settings", map[string]any{"action": "set", "sets": settings}, nil); e != nil {
		return nil, e
	}
	result, e := c.Read()
	if e != nil {
		return nil, fmt.Errorf("apply_unconfirmed_read_again")
	}
	got := result["settings"].(map[string]any)
	for key, v := range settings {
		if !reflect.DeepEqual(got[key], v) {
			c.request("/settings", map[string]any{"action": "set", "sets": before}, nil)
			restored, err := c.Read()
			if err != nil || restored["revision"] != revision(before) {
				return nil, fmt.Errorf("apply_mismatch_rollback_unconfirmed")
			}
			return nil, fmt.Errorf("apply_mismatch_rollback_confirmed")
		}
	}
	raw, _ = json.Marshal(result)
	if e = os.WriteFile(file, raw, 0600); e != nil {
		return nil, fmt.Errorf("apply_confirmed_receipt_failed")
	}
	os.Remove(intentPath)
	return result, nil
}
func Validate(changes map[string]any) error {
	allowed := map[string]Field{}
	for _, f := range Fields {
		allowed[f.Name] = f
	}
	for key, v := range changes {
		f, ok := allowed[key]
		if !ok {
			return fmt.Errorf("unsupported_field")
		}
		if f.Unit == "path" {
			path, ok := v.(string)
			if !ok || !safeCachePath(path) {
				return fmt.Errorf("unsafe_cache_path")
			}
		} else if f.Boolean {
			if _, ok := v.(bool); !ok {
				return fmt.Errorf("boolean_required")
			}
		} else {
			n, ok := v.(float64)
			if !ok || n != float64(int64(n)) || n < float64(f.Min) || n > float64(f.Max) {
				return fmt.Errorf("field_range_%s", key)
			}
			if key == "CacheSize" {
				raw, e := os.ReadFile("/proc/meminfo")
				if e == nil {
					fields := strings.Fields(strings.Split(string(raw), "\n")[0])
					if len(fields) >= 2 {
						kb, _ := strconv.ParseInt(fields[1], 10, 64)
						if int64(n) > kb*1024/2 {
							return fmt.Errorf("cache_exceeds_half_ram")
						}
					}
				}
			}
		}
	}
	return nil
}

func ReadOperation(dir, id string) (map[string]any, error) {
	if len(id) < 16 || len(id) > 128 || strings.ContainsAny(id, "/\\.") {
		return nil, fmt.Errorf("operation_id")
	}
	if raw, e := os.ReadFile(filepath.Join(dir, id+".json")); e == nil {
		var result map[string]any
		if e = json.Unmarshal(raw, &result); e != nil {
			return nil, e
		}
		return map[string]any{"state": "complete", "result": result}, nil
	}
	if raw, e := os.ReadFile(filepath.Join(dir, id+".error.json")); e == nil {
		var result map[string]any
		json.Unmarshal(raw, &result)
		return result, nil
	}
	return map[string]any{"state": "pending"}, nil
}

func safeCachePath(path string) bool {
	const base = "/var/lib/torrserver-mjwatch/cache"
	return len(path) <= 512 && filepath.Clean(path) == path && (path == base || strings.HasPrefix(path, base+"/")) && !strings.ContainsAny(path, "\x00\n\r")
}
