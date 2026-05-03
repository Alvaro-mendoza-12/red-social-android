-- Live Streaming (independent from Voice Rooms)
-- Creates:
-- - public.live_streams
-- - public.live_comments
-- Adds RLS policies and Realtime publication entries (supabase_realtime)

begin;

create table if not exists public.live_streams (
  id uuid primary key default gen_random_uuid(),
  created_at timestamptz not null default now(),
  is_active boolean not null default false,
  title text not null default '',
  source text not null default 'camera' check (source in ('camera', 'screen')),
  host_user_id uuid not null references public.profiles(id) on delete cascade,
  host_name text not null default '',
  host_avatar_url text,
  viewer_count integer not null default 0
);

create index if not exists live_streams_active_created_at_idx
  on public.live_streams (is_active, created_at desc);

create table if not exists public.live_comments (
  id uuid primary key default gen_random_uuid(),
  created_at timestamptz not null default now(),
  live_id uuid not null references public.live_streams(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  user_name text not null default '',
  avatar_url text,
  content text not null
);

create index if not exists live_comments_live_created_at_idx
  on public.live_comments (live_id, created_at);

alter table public.live_streams enable row level security;
alter table public.live_comments enable row level security;

-- live_streams policies
drop policy if exists "live_streams_select_all" on public.live_streams;
create policy "live_streams_select_all"
  on public.live_streams
  for select
  using (true);

drop policy if exists "live_streams_insert_own" on public.live_streams;
create policy "live_streams_insert_own"
  on public.live_streams
  for insert
  to authenticated
  with check (auth.uid() = host_user_id);

drop policy if exists "live_streams_update_own" on public.live_streams;
create policy "live_streams_update_own"
  on public.live_streams
  for update
  to authenticated
  using (auth.uid() = host_user_id)
  with check (auth.uid() = host_user_id);

drop policy if exists "live_streams_delete_own" on public.live_streams;
create policy "live_streams_delete_own"
  on public.live_streams
  for delete
  to authenticated
  using (auth.uid() = host_user_id);

-- live_comments policies
drop policy if exists "live_comments_select_all" on public.live_comments;
create policy "live_comments_select_all"
  on public.live_comments
  for select
  using (true);

drop policy if exists "live_comments_insert_own" on public.live_comments;
create policy "live_comments_insert_own"
  on public.live_comments
  for insert
  to authenticated
  with check (auth.uid() = user_id);

drop policy if exists "live_comments_delete_own" on public.live_comments;
create policy "live_comments_delete_own"
  on public.live_comments
  for delete
  to authenticated
  using (auth.uid() = user_id);

-- Realtime publication (safe / idempotent)
do $$
begin
  if exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    if not exists (
      select 1 from pg_publication_tables
      where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'live_streams'
    ) then
      alter publication supabase_realtime add table public.live_streams;
    end if;

    if not exists (
      select 1 from pg_publication_tables
      where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'live_comments'
    ) then
      alter publication supabase_realtime add table public.live_comments;
    end if;
  end if;
end $$;

commit;
