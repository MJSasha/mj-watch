package resources

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

const MiB int64 = 1 << 20

type Memory struct {
	Total     int64 `json:"totalBytes"`
	Available int64 `json:"availableBytes"`
}

// Read includes other processes through MemAvailable and inherited cgroup v2 limits.
func Read() Memory {
	raw, _ := os.ReadFile("/proc/meminfo")
	m := Parse(string(raw))
	cgroup, _ := os.ReadFile("/proc/self/cgroup")
	for _, line := range strings.Split(string(cgroup), "\n") {
		if !strings.HasPrefix(line, "0::/") {
			continue
		}
		base := "/sys/fs/cgroup"
		dir := filepath.Join(base, strings.TrimPrefix(line, "0::"))
		for dir == base || strings.HasPrefix(dir, base+"/") {
			limit, _ := os.ReadFile(filepath.Join(dir, "memory.max"))
			used, _ := os.ReadFile(filepath.Join(dir, "memory.current"))
			m = m.WithLimit(strings.TrimSpace(string(limit)), strings.TrimSpace(string(used)))
			if dir == base {
				break
			}
			dir = filepath.Dir(dir)
		}
	}
	return m
}

func Parse(raw string) Memory {
	var m Memory
	for _, line := range strings.Split(raw, "\n") {
		f := strings.Fields(line)
		if len(f) != 3 || f[2] != "kB" {
			continue
		}
		n, err := strconv.ParseInt(f[1], 10, 64)
		if err != nil || n < 0 || n > (1<<50) {
			continue
		}
		switch f[0] {
		case "MemTotal:":
			m.Total = n * 1024
		case "MemAvailable:":
			m.Available = n * 1024
		}
	}
	m.Available = min(m.Available, m.Total)
	return m
}

func (m Memory) WithLimit(limit, used string) Memory {
	l, e := strconv.ParseInt(limit, 10, 64)
	u, ue := strconv.ParseInt(used, 10, 64)
	if e != nil || ue != nil || l <= 0 || u < 0 {
		return m
	}
	if m.Total > 0 {
		m.Total = min(m.Total, l)
		m.Available = min(m.Available, max(0, l-u))
	}
	return m
}

func (m Memory) Streams() int {
	if m.Total <= 1<<30 {
		return 1
	}
	if m.Total <= 2<<30 {
		return 2
	}
	return 4
}

type CacheBudget struct {
	Memory
	Streams     int   `json:"plannedStreams"`
	Torrents    int   `json:"plannedTorrents"`
	Reserve     int64 `json:"reserveBytes"`
	Maximum     int64 `json:"maximumBytes"`
	Recommended int64 `json:"recommendedBytes"`
}

func (m Memory) Budget(streams int) CacheBudget { return m.BudgetFor(streams, streams) }

func (m Memory) BudgetFor(streams, torrents int) CacheBudget {
	streams = max(1, streams)
	torrents = max(streams, torrents)
	// MemAvailable already reflects neighbours' current use. Reserve their growth,
	// OS headroom and another 96 MiB for MJWatch/password verification peaks.
	reserve := max(128*MiB, m.Total/10) + 96*MiB
	maximum := min(8<<30, min(m.Total/2, max(0, m.Available-reserve))/int64(torrents)) / MiB * MiB
	recommended := min(maximum, int64(128)*MiB)
	if m.Streams() == 1 {
		recommended = min(recommended, 64*MiB)
	}
	return CacheBudget{m, streams, torrents, reserve, maximum, recommended}
}
