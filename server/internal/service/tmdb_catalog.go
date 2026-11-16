package service

import (
	"context"
	"encoding/json"
	"net/url"
)

// Fixed discovery routes keep managed TMDB credentials on the server.
func (s *Server) catalogRequest(ctx context.Context, category, language, credential string) ([]byte, string, int, error) {
	kinds := []string{"movie", "tv"}
	q := url.Values{"language": {language}, "include_adult": {"false"}, "sort_by": {"popularity.desc"}}
	switch category {
	case "movies":
		kinds = []string{"movie"}
		q.Set("without_genres", "16")
	case "series":
		kinds = []string{"tv"}
		q.Set("without_genres", "16")
	default:
		q.Set("with_genres", "16")
		if category == "anime" {
			q.Set("with_original_language", "ja")
		}
	}
	results := []map[string]any{}
	for _, kind := range kinds {
		data, mime, status, err := s.tmdbRequest(ctx, "discover/"+kind, q, credential)
		if err != nil {
			return nil, mime, status, err
		}
		var page struct {
			Results []map[string]any `json:"results"`
		}
		if err = json.Unmarshal(data, &page); err != nil {
			return nil, mime, 502, err
		}
		for _, item := range page.Results {
			item["media_type"] = kind
			results = append(results, item)
		}
	}
	data, err := json.Marshal(map[string]any{"results": results, "category": category})
	return data, "application/json", 200, err
}
