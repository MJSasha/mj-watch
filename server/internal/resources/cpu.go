package resources

import (
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

type CPU struct {
	Cores   int      `json:"cores"`
	Percent *float64 `json:"percent,omitempty"`
}

type cpuTicks struct {
	total uint64
	idle  uint64
	cores int
}

// CPUSampler measures Linux host CPU time between reads. Fast concurrent
// refreshes reuse a sample instead of shortening another client's interval.
type CPUSampler struct {
	mu       sync.Mutex
	at       time.Time
	previous cpuTicks
	reading  CPU
}

func (s *CPUSampler) Read() CPU {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now()
	if !s.at.IsZero() && now.Sub(s.at) < time.Second {
		return s.reading
	}
	raw, err := os.ReadFile("/proc/stat")
	if err != nil {
		return CPU{}
	}
	current := parseCPUTicks(string(raw))
	reading := CPU{Cores: current.cores}
	if now.Sub(s.at) <= 15*time.Second {
		reading.Percent = cpuPercent(s.previous, current)
	}
	s.at, s.previous, s.reading = now, current, reading
	return reading
}

func parseCPUTicks(raw string) cpuTicks {
	var ticks cpuTicks
	for _, line := range strings.Split(raw, "\n") {
		fields := strings.Fields(line)
		if len(fields) < 5 {
			continue
		}
		if fields[0] != "cpu" {
			if strings.HasPrefix(fields[0], "cpu") {
				if _, err := strconv.ParseUint(fields[0][3:], 10, 32); err == nil {
					ticks.cores++
				}
			}
			continue
		}
		var total, idle uint64
		// guest and guest_nice are already included in user and nice.
		for index, field := range fields[1:min(len(fields), 9)] {
			value, err := strconv.ParseUint(field, 10, 64)
			if err != nil {
				return cpuTicks{}
			}
			total += value
			if index == 3 || index == 4 {
				idle += value
			}
		}
		ticks.total, ticks.idle = total, idle
	}
	return ticks
}

func cpuPercent(previous, current cpuTicks) *float64 {
	if previous.total == 0 || previous.cores != current.cores || current.total <= previous.total || current.idle < previous.idle {
		return nil
	}
	total := current.total - previous.total
	idle := current.idle - previous.idle
	if idle > total {
		return nil
	}
	percent := float64(total-idle) * 100 / float64(total)
	return &percent
}
