package service

import (
	"context"
	"io"
	"log"
	"net/http"
	"sort"
	"strings"
	"sync/atomic"
	"time"
)

const streamIdleTimeout = 90 * time.Second
const stoppedStreamTTL = 5 * time.Minute

type streamKey struct {
	session, hash, view string
	index               int
	download            bool
}

type streamInfo struct {
	ID       string    `json:"id"`
	Account  string    `json:"account"`
	Device   string    `json:"device"`
	File     string    `json:"file"`
	Started  time.Time `json:"startedAt"`
	Requests int       `json:"requests"`
	Download bool      `json:"download,omitempty"`
}

type activeStream struct {
	streamInfo
	user, device string
	stopped      chan struct{}
}

func (s *Server) acquireStream(i identity, key streamKey, probe bool) (*activeStream, string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.expireStoppedStreams(time.Now())
	if _, stopped := s.stoppedStreams[key]; stopped {
		return nil, "stopped"
	}
	if probe {
		return nil, ""
	}
	if view := s.streams[key]; view != nil {
		// A seek can briefly overlap two Range requests on the same view.
		if view.Requests >= 2 {
			return nil, "overlap"
		}
		view.Requests++
		return view, ""
	}
	perUser := 0
	for _, view := range s.streams {
		if view.user == i.User {
			perUser++
		}
	}
	if perUser >= s.maxStreamsPerUser {
		return nil, "account"
	}
	if len(s.streams) >= s.maxStreams {
		return nil, "server"
	}
	view := &activeStream{
		streamInfo: streamInfo{ID: Token(), Started: time.Now().UTC(), Requests: 1, Download: key.download},
		user:       i.User,
		device:     i.Device,
		stopped:    make(chan struct{}),
	}
	s.streams[key] = view
	return view, ""
}

func (s *Server) releaseStream(key streamKey, view *activeStream) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.streams[key] != view {
		return
	}
	view.Requests--
	if view.Requests == 0 {
		delete(s.streams, key)
	}
}

func (s *Server) expireStoppedStreams(now time.Time) {
	for key, expiry := range s.stoppedStreams {
		if !now.Before(expiry) {
			delete(s.stoppedStreams, key)
		}
	}
}

type streamProgress struct {
	io.Writer
	started time.Time
	wrote   atomic.Int64
}

func (p *streamProgress) Write(b []byte) (int, error) {
	n, err := p.Writer.Write(b)
	if n > 0 {
		p.wrote.Store(int64(time.Since(p.started)))
	}
	return n, err
}

func (s *Server) watchStream(parent context.Context, w http.ResponseWriter, session string, stopped <-chan struct{}, progress *streamProgress, idleTimeout time.Duration) (context.Context, func()) {
	ctx, cancel := context.WithCancel(parent)
	done := make(chan struct{})
	go func() {
		defer close(done)
		tick := time.NewTicker(min(5*time.Second, idleTimeout))
		defer tick.Stop()
		for {
			reason := "idle_timeout"
			select {
			case <-ctx.Done():
				return
			case <-stopped:
				reason = "admin_stop"
			case <-tick.C:
				if time.Since(progress.started)-time.Duration(progress.wrote.Load()) < idleTimeout {
					var alive int
					err := s.DB.QueryRowContext(ctx, "SELECT COUNT(*) FROM sessions s JOIN devices d ON d.id=s.device_id JOIN users u ON u.id=d.user_id WHERE s.id=? AND s.refresh_exp>? AND d.revoked=0 AND u.blocked=0", session, time.Now().Unix()).Scan(&alive)
					if err == nil && alive == 1 {
						continue
					}
					if ctx.Err() != nil {
						return
					}
					reason = "session_invalid"
				}
			}
			cancel()
			http.NewResponseController(w).SetWriteDeadline(time.Now())
			log.Printf("event=stream_cancel reason=%s", reason)
			return
		}
	}()
	return ctx, func() {
		cancel()
		<-done
	}
}

func (s *Server) playbackStreams(w http.ResponseWriter, r *http.Request, path string) {
	const listPath = "admin/playback/streams"
	if path == listPath && r.Method == http.MethodGet {
		s.mu.Lock()
		views := make([]activeStream, 0, len(s.streams))
		for _, view := range s.streams {
			views = append(views, *view)
		}
		s.mu.Unlock()
		sort.Slice(views, func(a, b int) bool {
			return views[a].Started.Before(views[b].Started)
		})
		items := make([]streamInfo, 0, len(views))
		for _, view := range views {
			item := view.streamInfo
			err := s.DB.QueryRowContext(r.Context(),
				"SELECT u.name, COALESCE(d.name, '') FROM users u LEFT JOIN devices d ON d.id=? AND d.user_id=u.id WHERE u.id=?",
				view.device, view.user).Scan(&item.Account, &item.Device)
			if err != nil {
				item.Account = view.user
				item.Device = view.device
			}
			items = append(items, item)
		}
		respond(w, 200, map[string]any{"streams": items})
		return
	}
	if path != listPath && r.Method == http.MethodDelete {
		id := strings.TrimPrefix(path, listPath+"/")
		s.mu.Lock()
		for key, view := range s.streams {
			if view.ID == id {
				s.stoppedStreams[key] = time.Now().Add(stoppedStreamTTL)
				delete(s.streams, key)
				close(view.stopped)
				break
			}
		}
		s.mu.Unlock()
		respond(w, 200, map[string]bool{"stopped": true})
		return
	}
	fail(w, 405, "method_not_allowed")
}
