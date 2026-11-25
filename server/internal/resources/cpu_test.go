package resources

import "testing"

func TestCPUInterval(t *testing.T) {
	previous := parseCPUTicks("cpu 100 0 20 400 20 0 0 0 30 0\ncpu0 50 0 10 200 10\ncpu1 50 0 10 200 10")
	current := parseCPUTicks("cpu 150 0 30 430 30 0 0 0 70 0\ncpu0 75 0 15 215 15\ncpu1 75 0 15 215 15")
	if current.cores != 2 {
		t.Fatal("logical processor count", current.cores)
	}
	if percent := cpuPercent(previous, current); percent == nil || *percent != 60 {
		t.Fatal("expected 60 percent busy time", percent)
	}
	if cpuPercent(cpuTicks{}, current) != nil || cpuPercent(current, previous) != nil {
		t.Fatal("CPU samples require an increasing counter and a baseline")
	}
}
