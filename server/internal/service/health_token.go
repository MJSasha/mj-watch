package service

import (
	"crypto/subtle"
	"database/sql"
	"encoding/json"
	"time"
)

type healthCredential struct {
	Hash    string `json:"hash"`
	Expires int64  `json:"expires"`
}

// Issued only by the root CLI, without creating an account, device or refresh session.
func MaintenanceToken(db *sql.DB) (map[string]any, error) {
	token := Token()
	raw, _ := json.Marshal(healthCredential{Hash(token), time.Now().Add(90 * time.Second).Unix()})
	_, err := db.Exec("INSERT OR REPLACE INTO settings VALUES('maintenance_access',?)", string(raw))
	return map[string]any{"accessToken": token, "expiresIn": 90}, err
}

func (s *Server) validMaintenanceToken(token string) bool {
	var credential healthCredential
	if len(token) != 43 || json.Unmarshal([]byte(value(s.DB, "maintenance_access")), &credential) != nil || credential.Expires <= time.Now().Unix() {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(credential.Hash), []byte(Hash(token))) == 1
}
