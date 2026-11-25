package service

import (
	"net/http"
	"os"
	"path/filepath"
	"syscall"
	"time"

	"dev.mjwatch/server/internal/resources"
)

type resourceDisk struct {
	ID        string `json:"id"`
	Total     uint64 `json:"totalBytes"`
	Used      uint64 `json:"usedBytes"`
	Available uint64 `json:"availableBytes"`
}

func resourceDisks(database string) []resourceDisk {
	disks := make([]resourceDisk, 0, 2)
	volumes := map[uint64]bool{}
	paths := []struct{ id, location string }{{"system", "/"}}
	if database != "" {
		paths = append(paths, struct{ id, location string }{"data", filepath.Dir(database)})
	}
	for _, path := range paths {
		info, err := os.Stat(path.location)
		if err != nil {
			continue
		}
		stat, ok := info.Sys().(*syscall.Stat_t)
		if !ok || volumes[uint64(stat.Dev)] {
			continue
		}
		var fs syscall.Statfs_t
		if syscall.Statfs(path.location, &fs) != nil || fs.Blocks == 0 || fs.Bsize <= 0 ||
			fs.Bfree > fs.Blocks || fs.Bavail > fs.Bfree {
			continue
		}
		volumes[uint64(stat.Dev)] = true
		blockSize := uint64(fs.Bsize)
		disks = append(disks, resourceDisk{
			ID: path.id, Total: fs.Blocks * blockSize,
			Used: (fs.Blocks - fs.Bfree) * blockSize, Available: fs.Bavail * blockSize,
		})
	}
	return disks
}

func (s *Server) hostResources(w http.ResponseWriter) {
	respond(w, 200, map[string]any{
		"hostResources": true,
		"checkedAt":     time.Now().Unix(),
		"cpu":           s.cpu.Read(),
		"memory":        resources.Read(),
		"disks":         resourceDisks(s.Config.Database),
	})
}
