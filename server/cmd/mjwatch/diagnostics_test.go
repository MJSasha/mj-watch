package main

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func TestDiagnosticsMigrationRetriesAndDoesNotRestartTwice(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "system")
	fail := true
	calls := 0
	run := func(args ...string) error {
		calls++
		if args[0] == "try-restart" && args[1] == "mjwatch-torrserver.service" && fail {
			return errors.New("failure")
		}
		return nil
	}
	if configureDiagnostics(dir, run) == nil {
		t.Fatal("lost restart failure")
	}
	if _,err:=os.Stat(filepath.Join(dir,"mjwatch.service.d","20-mjwatch-diagnostics.conf"));!os.IsNotExist(err){t.Fatal("failed migration kept new unit",err)}
 fail = false
	if err := configureDiagnostics(dir, run); err != nil {
		t.Fatal(err)
	}
	if calls < 6 {
		t.Fatal("incomplete migration not retried", calls)
	}
	completedCalls:=calls
 if err := configureDiagnostics(dir, run); err != nil || calls != completedCalls {
		t.Fatal("repeated restart", err, calls)
	}
	raw, err := os.ReadFile(filepath.Join(filepath.Dir(dir), "journald@mjwatch.conf.d", "20-mjwatch-retention.conf"))
	if err != nil || string(raw) != diagnosticJournalConfig {
		t.Fatal("missing bounded journal", err)
	}
	for _, unit := range []string{"mjwatch.service", "mjwatch-torrserver.service"} {
		raw, err := os.ReadFile(filepath.Join(dir, unit+".d", "20-mjwatch-diagnostics.conf"))
		if err != nil || string(raw) != diagnosticUnitConfig {
			t.Fatal(unit, err)
		}
	}
}
