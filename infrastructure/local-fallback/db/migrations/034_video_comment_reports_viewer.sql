CREATE INDEX IF NOT EXISTS idx_fallback_video_comment_reports_viewer
ON fallback_video_comment_reports(lower(video_id), lower(reporter_id));
