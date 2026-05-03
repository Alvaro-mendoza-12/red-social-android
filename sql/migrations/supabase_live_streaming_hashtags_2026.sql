-- Adds optional hashtags to live_streams (for Live setup screen)
begin;

alter table public.live_streams
  add column if not exists hashtags text[] not null default '{}';

commit;

