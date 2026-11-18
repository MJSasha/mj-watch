package resources

import "testing"

func TestBudgetIncludesNeighboursAndContainerHeadroom(t *testing.T) {
	m := Parse("MemTotal: 524288 kB\nMemAvailable: 393216 kB\n")
	if m.Streams() != 1 {
		t.Fatal(m)
	}
	b := m.Budget(1)
	if b.Reserve != 224*MiB || b.Maximum != 160*MiB || b.Recommended != 64*MiB {
		t.Fatal(b)
	}
	// A neighbouring service using 128 MiB must reduce the available cache.
	m.Available -= 128 * MiB
	if b = m.Budget(1); b.Maximum != 32*MiB || b.Recommended != 32*MiB {
		t.Fatal(b)
	}
	m.Available = 20 * MiB
	if b = m.Budget(1); b.Maximum != 0 || b.Recommended != 0 {
		t.Fatal(b)
	}
	large := Memory{8 << 30, 6 << 30}
	if large.Streams() != 4 || large.Budget(4).Maximum != 1<<30 {
		t.Fatal(large.Budget(4))
	}
	limited := large.WithLimit("536870912", "402653184")
	if limited.Total != 512*MiB || limited.Available != 128*MiB || limited.Streams() != 1 || limited.Budget(1).Maximum != 0 {
		t.Fatal(limited)
	}
	if limited.WithLimit("max", "0") != limited || limited.WithLimit("oops", "0") != limited {
		t.Fatal("invalid limit")
	}
	if limited.WithLimit("268435456", "300000000").Available != 0 {
		t.Fatal("negative headroom")
	}
	if Parse("").Budget(1).Maximum != 0 || Parse("MemTotal: -1 kB").Total != 0 {
		t.Fatal("unknown RAM must not invent headroom")
	}
}
