-- Adds basic live statistics fields to live_streams (for end-of-live persistence)
begin;

alter table public.live_streams
  add column if not exists ended_at timestamptz,
  add column if not exists peak_viewer_count integer not null default 0,
  add column if not exists total_comments integer not null default 0,
  add column if not exists total_reactions integer not null default 0;

commit;

