package service

import (
	"context"
	"io"
	"net/http"
	"net/url"
	"slices"
	"strings"
	"time"
)

const jacredUpdateTasks = "UpdateTasksParse"

// These controllers expose the bounded queue refresh in the packaged JacRed release.
var jacredUpdateTrackers = []string{
	"anibelka", "kinozal", "korsars", "megapeer", "nnmclub", "rutor", "rutracker", "toloka", "torrentby", "ultradox",
}

type jacredJobTarget struct {
	Tracker string `json:"tracker"`
	Job     string `json:"job"`
}

type jacredActiveJob struct {
	jacredJobTarget
	ID           string  `json:"id"`
	StartedAtUTC string  `json:"startedAtUtc"`
	Elapsed      int64   `json:"elapsedSeconds"`
	PagesDone    int64   `json:"pagesCompleted"`
	PagesTotal   int64   `json:"pagesTotal"`
	Percent      *int    `json:"percent"`
	Category     *string `json:"currentCategory"`
	Page         *int    `json:"currentPage"`
	Summary      string  `json:"summary"`
}

func (s *Server) jacredJobs(w http.ResponseWriter, r *http.Request) {
	access := s.readJacredAccess()
	if access.APIKey == "" {
		fail(w, http.StatusServiceUnavailable, "jacred_not_installed")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	var report struct {
		Jobs []jacredActiveJob `json:"jobs"`
	}
	if s.jacredGet(ctx, "/health/background-jobs", access.APIKey, url.Values{}, &report) != nil || report.Jobs == nil {
		fail(w, http.StatusServiceUnavailable, "jacred_jobs_unavailable")
		return
	}
	starts := []jacredJobTarget{}
	if access.Collect != nil && *access.Collect {
		for _, tracker := range jacredUpdateTrackers {
			if slices.Contains(access.Trackers, tracker) {
				starts = append(starts, jacredJobTarget{Tracker: tracker, Job: jacredUpdateTasks})
			}
		}
	}
	w.Header().Set("Cache-Control", "no-store")
	respond(w, http.StatusOK, map[string]any{"jobs": report.Jobs, "availableStarts": starts})
}

func (s *Server) startJacredJob(w http.ResponseWriter, r *http.Request) {
	var target jacredJobTarget
	if !decodeLimit(w, r, &target, 1024) {
		return
	}
	if target.Job != jacredUpdateTasks || !slices.Contains(jacredUpdateTrackers, target.Tracker) {
		fail(w, http.StatusBadRequest, "jacred_job_unsupported")
		return
	}
	access := s.readJacredAccess()
	if access.APIKey == "" {
		fail(w, http.StatusServiceUnavailable, "jacred_not_installed")
		return
	}
	if access.Collect == nil || !*access.Collect || !slices.Contains(access.Trackers, target.Tracker) {
		fail(w, http.StatusConflict, "jacred_job_disabled")
		return
	}
	// Kinozal and Toloka can sign in before enqueueing the job.
	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
	defer cancel()
	// JacRed owns the per-tracker job lock.
	req, err := http.NewRequestWithContext(ctx, http.MethodGet,
		"http://127.0.0.1:19117/cron/"+target.Tracker+"/"+jacredUpdateTasks, nil)
	if err != nil {
		fail(w, http.StatusServiceUnavailable, "jacred_job_start_failed")
		return
	}
	resp, err := s.Client.Do(req)
	if err != nil {
		fail(w, http.StatusServiceUnavailable, "jacred_job_start_failed")
		return
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 256))
	if err != nil || resp.StatusCode != http.StatusOK {
		fail(w, http.StatusServiceUnavailable, "jacred_job_start_failed")
		return
	}
	result := strings.TrimSpace(string(body))
	switch {
	case result == "ok":
		respond(w, http.StatusAccepted, map[string]string{"state": "started"})
	case result == "work" || result == "running":
		fail(w, http.StatusConflict, "jacred_job_busy")
	case result == "disabled":
		fail(w, http.StatusConflict, "jacred_job_disabled")
	case strings.HasPrefix(result, "login failed") || result == "TakeLogin == null":
		fail(w, http.StatusConflict, "jacred_job_login_required")
	default:
		fail(w, http.StatusServiceUnavailable, "jacred_job_start_failed")
	}
}
