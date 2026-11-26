package service

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

type TMDBSettings struct {
	Enabled              bool   `json:"enabled"`
	Language             string `json:"language"`
	CacheMiB             int64  `json:"cacheMiB"`
	CredentialConfigured bool   `json:"credentialConfigured"`
}

func (s *Server) tmdbPublic() TMDBSettings {
	v := TMDBSettings{Language: "ru-RU", CacheMiB: 256}
	json.Unmarshal([]byte(value(s.DB, "tmdb")), &v)
	v.CredentialConfigured = value(s.DB, "tmdb_credential") != ""
	return v
}
func (s *Server) cipher() (cipher.AEAD, error) {
	key, e := os.ReadFile(s.Config.SecretKey)
	if e != nil {
		return nil, e
	}
	if len(key) != 32 {
		return nil, fmt.Errorf("invalid_key")
	}
	b, e := aes.NewCipher(key)
	if e != nil {
		return nil, e
	}
	return cipher.NewGCM(b)
}
func (s *Server) credential() (string, error) { return s.openSealed(value(s.DB, "tmdb_credential")) }
func (s *Server) openSealed(encoded string) (string, error) {
	raw, e := base64.RawStdEncoding.DecodeString(encoded)
	if e != nil || len(raw) < 12 {
		return "", fmt.Errorf("credential_missing")
	}
	a, e := s.cipher()
	if e != nil {
		return "", e
	}
	b, e := a.Open(nil, raw[:12], raw[12:], []byte("tmdb"))
	return string(b), e
}
func (s *Server) sealCredential(v string) (string, error) {
	a, e := s.cipher()
	if e != nil {
		return "", e
	}
	iv := make([]byte, a.NonceSize())
	if _, e = rand.Read(iv); e != nil {
		return "", e
	}
	return base64.RawStdEncoding.EncodeToString(a.Seal(iv, iv, []byte(v), []byte("tmdb"))), nil
}
func (s *Server) tmdbRequest(ctx context.Context, path string, q url.Values, credential string) ([]byte, string, int, error) {
	if time.Now().Before(s.tmdbAPIPause) {
		return nil, "", http.StatusTooManyRequests, fmt.Errorf("tmdb_paused")
	}
	u := "https://api.themoviedb.org/3/" + path
	req, _ := http.NewRequestWithContext(ctx, "GET", u, nil)
	if regexp.MustCompile(`^[a-fA-F0-9]{32}$`).MatchString(credential) {
		q.Set("api_key", credential)
	} else {
		req.Header.Set("Authorization", "Bearer "+credential)
	}
	req.URL.RawQuery = q.Encode()
	resp, e := s.Client.Do(req)
	if e != nil {
		return nil, "", 502, e
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusTooManyRequests {
		s.tmdbAPIPause = tmdbRetryDeadline(resp.Header.Get("Retry-After"), time.Now())
	}
	if resp.StatusCode != 200 {
		return nil, "", resp.StatusCode, fmt.Errorf("tmdb_failed")
	}
	b, e := io.ReadAll(io.LimitReader(resp.Body, (4<<20)+1))
	if len(b) > 4<<20 {
		return nil, "", 502, fmt.Errorf("response_limit")
	}
	return b, resp.Header.Get("Content-Type"), 200, e
}

func tmdbRetryDeadline(header string, now time.Time) time.Time {
	if seconds, err := strconv.ParseInt(header, 10, 32); err == nil && seconds >= 0 {
		return now.Add(time.Duration(max(seconds, 1)) * time.Second)
	}
	if deadline, err := http.ParseTime(header); err == nil {
		if deadline.After(now) {
			return deadline
		}
		return now.Add(time.Second)
	}
	return now.Add(30 * time.Second)
}

func tmdbRateLimited(w http.ResponseWriter, until time.Time, code string) {
	seconds := max(1, int64((time.Until(until)+time.Second-1)/time.Second))
	w.Header().Set("Retry-After", strconv.FormatInt(seconds, 10))
	fail(w, http.StatusTooManyRequests, code)
}
func (s *Server) adminTMDB(w http.ResponseWriter, r *http.Request, path string, i identity) {
	s.tmdbMu.Lock()
	defer s.tmdbMu.Unlock()
	v := s.tmdbPublic()
	revision := Hash(value(s.DB, "tmdb") + value(s.DB, "tmdb_credential"))
	switch {
	case path == "admin/tmdb" && r.Method == "GET":
		respond(w, 200, map[string]any{"settings": v, "revision": revision, "lastCheckedAt": value(s.DB, "tmdb_last_check")})
	case (path == "admin/tmdb" && r.Method == "PATCH") || (path == "admin/tmdb/check" && r.Method == "POST"):
		var input struct {
			Enabled    bool   `json:"enabled"`
			Language   string `json:"language"`
			CacheMiB   int64  `json:"cacheMiB"`
			Credential string `json:"credential"`
		}
		if !decode(w, r, &input) {
			return
		}
		if !regexp.MustCompile(`^[a-z]{2}-[A-Z]{2}$`).MatchString(input.Language) || input.CacheMiB < 1 || input.CacheMiB > 1024 || len(input.Credential) > 4096 {
			fail(w, 400, "invalid_tmdb_settings")
			return
		}
		if r.Method == "PATCH" && r.Header.Get("If-Match") != revision {
			fail(w, 409, "revision_conflict")
			return
		}
		credential := input.Credential
		if credential == "" {
			credential, _ = s.credential()
		}
		if input.Credential != "" || strings.HasSuffix(path, "/check") {
			if credential == "" {
				fail(w, 409, "credential_missing")
				return
			}
			_, _, code, e := s.tmdbRequest(r.Context(), "configuration", url.Values{}, credential)
			if e != nil {
				if code == 401 || code == 403 {
					fail(w, 422, "credential_rejected")
				} else if code == 429 {
					tmdbRateLimited(w, s.tmdbAPIPause, "provider_rate_limited")
				} else {
					fail(w, 502, "provider_unavailable")
				}
				return
			}
		}
		if strings.HasSuffix(path, "/check") {
			if input.Credential == "" {
				s.DB.Exec("INSERT OR REPLACE INTO settings VALUES('tmdb_last_check',?)", strconv.FormatInt(time.Now().Unix(), 10))
			}
			respond(w, 200, map[string]any{"checked": true, "checkedAt": time.Now().Unix()})
			return
		}
		if input.Enabled && credential == "" {
			fail(w, 409, "credential_missing")
			return
		}
		settings := TMDBSettings{Enabled: input.Enabled, Language: input.Language, CacheMiB: input.CacheMiB}
		raw, _ := json.Marshal(settings)
		tx, e := s.DB.Begin()
		if e != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		defer tx.Rollback()
		if input.Credential != "" {
			sealed, err := s.sealCredential(credential)
			if err != nil {
				fail(w, 503, "secret_storage_unavailable")
				return
			}
			_, e = tx.Exec("INSERT OR REPLACE INTO settings VALUES('tmdb_credential',?)", sealed)
		}
		if e == nil {
			_, e = tx.Exec("INSERT OR REPLACE INTO settings VALUES('tmdb',?)", string(raw))
		}
		if e == nil && input.Credential != "" {
			_, e = tx.Exec("INSERT OR REPLACE INTO settings VALUES('tmdb_last_check',?)", strconv.FormatInt(time.Now().Unix(), 10))
		}
		if e == nil {
			_, e = tx.Exec("DELETE FROM cache")
		}
		if e == nil {
			e = tx.Commit()
		}
		if e != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		respond(w, 200, map[string]bool{"saved": true})
	case path == "admin/tmdb/credential" && r.Method == "DELETE":
		tx, e := s.DB.Begin()
		if e != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		defer tx.Rollback()
		_, e = tx.Exec("DELETE FROM settings WHERE key IN ('tmdb_credential','tmdb_last_check')")
		v.Enabled = false
		raw, _ := json.Marshal(v)
		if e == nil {
			_, e = tx.Exec("INSERT OR REPLACE INTO settings VALUES('tmdb',?)", string(raw))
		}
		if e == nil {
			_, e = tx.Exec("DELETE FROM cache")
		}
		if e == nil {
			e = tx.Commit()
		}
		if e != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		respond(w, 200, map[string]bool{"deleted": true})
	case path == "admin/tmdb/cache" && r.Method == "DELETE":
		if _, e := s.DB.Exec("DELETE FROM cache"); e != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		respond(w, 200, map[string]bool{"cleared": true})
	default:
		fail(w, 404, "not_found")
	}
}

var imagePath = regexp.MustCompile(`^metadata/images/(w92|w154|w185|w342|w500|w780|w1280|original)/([A-Za-z0-9_-]{1,128}\.(jpg|png|webp))$`)
var detailPath = regexp.MustCompile(`^metadata/(movie|tv)/[1-9][0-9]{0,9}(/seasons/[0-9]{1,3}(/episodes/[0-9]{1,4})?)?$`)
var creditsPath = regexp.MustCompile(`^metadata/((movie|tv)/[1-9][0-9]{0,9}/credits|person/[1-9][0-9]{0,9}/combined_credits)$`)

func (s *Server) metadata(w http.ResponseWriter, r *http.Request, path string, i identity) {
	v := s.tmdbPublic()
	if !v.Enabled {
		fail(w, 409, "provider_disabled")
		return
	}
	image := imagePath.FindStringSubmatch(path)
	api := ""
	withLogos := false
	q := url.Values{"language": {v.Language}}
	if image == nil {
		switch {
		case path == "metadata/search":
			api = "search/multi"
			query := r.URL.Query().Get("query")
			if len(query) < 2 || len(query) > 256 {
				fail(w, 400, "invalid_query")
				return
			}
			q.Set("query", query)
			q.Set("include_adult", "false")
		case path == "metadata/home":
			api = "trending/all/week"
			category := r.URL.Query().Get("category")
			switch category {
			case "", "all":
			case "movies", "series", "anime", "cartoons":
				api = "catalog/" + category
			default:
				fail(w, 400, "invalid_category")
				return
			}
			q.Set("category", category)
		case creditsPath.MatchString(path):
			api = strings.TrimPrefix(path, "metadata/")
		case detailPath.MatchString(path):
			api = strings.ReplaceAll(strings.ReplaceAll(strings.TrimPrefix(path, "metadata/"), "/seasons/", "/season/"), "/episodes/", "/episode/")
			withLogos = strings.Count(api, "/") == 1 && r.URL.Query().Get("artwork") == "logos"
			if withLogos {
				q.Set("append_to_response", "images")
				q.Set("include_image_language", "ru,en,null")
			}
			if strings.HasPrefix(api, "tv/") && strings.Count(api, "/") == 1 && r.URL.Query().Get("external") == "1" {
				appendToResponse := "external_ids"
				if withLogos {
					appendToResponse = "images," + appendToResponse
				}
				q.Set("append_to_response", appendToResponse)
			}
		default:
			fail(w, 404, "not_found")
			return
		}
	}
	key := Hash(path + q.Encode())
	s.tmdbMu.Lock()
	defer s.tmdbMu.Unlock() // coalesces identical cache misses and caps upstream concurrency
	var cached []byte
	var mime string
	var expires int64
	cachedOK := s.DB.QueryRow("SELECT value,mime,expires FROM cache WHERE key=?", key).Scan(&cached, &mime, &expires) == nil
	writeCached := func() {
		w.Header().Set("Content-Type", mime)
		if image != nil && expires > time.Now().Unix() {
			w.Header().Set("Cache-Control", "private, max-age=3600")
		}
		w.Write(cached)
	}
	now := time.Now()
	if cachedOK && expires > now.Unix() {
		writeCached()
		return
	}
	pause, bucket, perUser, global := &s.tmdbAPIPause, "tmdb", 60, 240
	if image != nil {
		pause, bucket, perUser, global = &s.tmdbImagePause, "tmdb-image", 180, 480
	}
	if now.Before(*pause) {
		if cachedOK {
			writeCached()
		} else {
			tmdbRateLimited(w, *pause, "provider_rate_limited")
		}
		return
	}
	if !s.limit(bucket+":"+i.User, perUser, time.Minute) || !s.limit(bucket+":global", global, time.Minute) {
		if cachedOK {
			writeCached()
		} else {
			tmdbRateLimited(w, now.Add(time.Minute), "rate_limited")
		}
		return
	}
	var data []byte
	var e error
	var fetchedMIME string
	status := 200
	if image != nil {
		req, _ := http.NewRequestWithContext(r.Context(), "GET", "https://image.tmdb.org/t/p/"+image[1]+"/"+image[2], nil)
		resp, err := s.Client.Do(req)
		if err != nil {
			fail(w, 502, "provider_unavailable")
			return
		}
		defer resp.Body.Close()
		status = resp.StatusCode
		fetchedMIME = resp.Header.Get("Content-Type")
		if status == http.StatusTooManyRequests {
			*pause = tmdbRetryDeadline(resp.Header.Get("Retry-After"), time.Now())
		}
		if status == 200 && strings.HasPrefix(fetchedMIME, "image/") {
			data, e = io.ReadAll(io.LimitReader(resp.Body, (8<<20)+1))
			if len(data) > 8<<20 {
				e = fmt.Errorf("image_limit")
			}
		} else {
			e = fmt.Errorf("image_failed")
		}
	} else {
		credential, err := s.credential()
		if err != nil {
			fail(w, 409, "credential_missing")
			return
		}
		if strings.HasPrefix(api, "catalog/") {
			data, fetchedMIME, status, e = s.catalogRequest(r.Context(), strings.TrimPrefix(api, "catalog/"), q.Get("language"), credential)
		} else {
			q.Del("category")
			data, fetchedMIME, status, e = s.tmdbRequest(r.Context(), api, q, credential)
		}
	}
	if e != nil {
		if status == 429 {
			if cachedOK {
				writeCached()
			} else {
				tmdbRateLimited(w, *pause, "provider_rate_limited")
			}
		} else if status == 401 || status == 403 {
			fail(w, 502, "provider_credential_rejected")
		} else {
			fail(w, 502, "provider_unavailable")
		}
		return
	}
	if image == nil {
		var obj any
		if json.Unmarshal(data, &obj) != nil {
			fail(w, 502, "provider_invalid_response")
			return
		}
		rewriteImages(obj)
		if withLogos {
			rewriteTitleLogos(obj)
		}
		data, _ = json.Marshal(obj)
		fetchedMIME = "application/json"
	}
	if int64(len(data)) <= v.CacheMiB<<20 {
		var size int64
		s.DB.QueryRow("SELECT COALESCE(SUM(length(value)),0) FROM cache WHERE key<>?", key).Scan(&size)
		if excess := size + int64(len(data)) - (v.CacheMiB << 20); excess > 0 {
			s.DB.Exec(`DELETE FROM cache WHERE key IN (
				SELECT key FROM (
					SELECT key, SUM(length(value)) OVER (
						ORDER BY expires, key ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
					) AS preceding_bytes FROM cache WHERE key<>?
				) WHERE COALESCE(preceding_bytes,0) < ?
			)`, key, excess)
		}
		s.DB.Exec("INSERT OR REPLACE INTO cache VALUES(?,?,?,?)", key, data, fetchedMIME, time.Now().Unix()+86400)
	}
	if image != nil {
		w.Header().Set("Cache-Control", "private, max-age=3600")
	}
	w.Header().Set("Content-Type", fetchedMIME)
	w.Write(data)
}

func rewriteTitleLogos(v any) {
	title, _ := v.(map[string]any)
	images, _ := title["images"].(map[string]any)
	logos, _ := images["logos"].([]any)
	if logos == nil {
		return
	}
	for _, item := range logos {
		logo, ok := item.(map[string]any)
		if !ok {
			continue
		}
		file, _ := logo["file_path"].(string)
		if strings.HasSuffix(file, ".svg") {
			file = strings.TrimSuffix(file, ".svg") + ".png"
		}
		path := "metadata/images/w500/" + strings.TrimPrefix(file, "/")
		if imagePath.MatchString(path) {
			logo["file_path"] = "/api/v1/" + path
		} else {
			logo["file_path"] = nil
		}
	}
	// Title details already include the primary backdrop and poster.
	title["images"] = map[string]any{"logos": logos}
}

func rewriteImages(v any) {
	switch x := v.(type) {
	case map[string]any:
		for k, val := range x {
			if k == "poster_path" || k == "backdrop_path" || k == "still_path" || k == "profile_path" {
				if str, ok := val.(string); ok {
					path := "metadata/images/w500/" + strings.TrimPrefix(str, "/")
					if k == "backdrop_path" {
						path = "metadata/images/w1280/" + strings.TrimPrefix(str, "/")
					}
					if imagePath.MatchString(path) {
						x[k] = "/api/v1/" + path
					} else {
						x[k] = nil
					}
				}
			} else {
				rewriteImages(val)
			}
		}
	case []any:
		for _, item := range x {
			rewriteImages(item)
		}
	}
}

var _ = filepath.Separator
var _ = strconv.Itoa

func (s *Server) publicTMDB() map[string]any {
	v := s.tmdbPublic()
	return map[string]any{"enabled": v.Enabled, "available": v.Enabled && v.CredentialConfigured, "language": v.Language, "operations": []string{"search", "details", "seasons", "episodes", "images", "credits", "filmography"}}
}
