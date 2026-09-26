-- Metadata and access controls required by the redesigned Novvin LIVE flow.
-- Safe to apply after supabase_live_streaming_schema_2026.sql.

begin;

alter table public.live_streams
  add column if not exists cover_url text,
  add column if not exists game_name text,
  add column if not exists category text,
  add column if not exists visibility text not null default 'public',
  add column if not exists gifts_enabled boolean not null default true,
  add column if not exists battles_enabled boolean not null default true;

do $$
begin
  if not exists (
    select 1
    from pg_constraint
    where conrelid = 'public.live_streams'::regclass
      and conname = 'live_streams_visibility_check'
  ) then
    alter table public.live_streams
      add constraint live_streams_visibility_check
      check (visibility in ('public', 'followers', 'private'));
  end if;
end
$$;

create index if not exists live_streams_active_visibility_created_idx
  on public.live_streams (is_active, visibility, created_at desc);

drop policy if exists "live_streams_select_all" on public.live_streams;
drop policy if exists "Live streams are viewable by visibility" on public.live_streams;
drop policy if exists "live_streams_select_by_visibility" on public.live_streams;

create policy "live_streams_select_by_visibility"
  on public.live_streams
  for select
  using (
    visibility = 'public'
    or auth.uid() = host_user_id
    or (
      visibility = 'followers'
      and auth.uid() is not null
      and exists (
        select 1
        from public.follows
        where follows.follower_id = auth.uid()
          and follows.following_id = live_streams.host_user_id
      )
    )
  );

drop policy if exists "live_comments_select_all" on public.live_comments;
drop policy if exists "live_comments_select_visible_live" on public.live_comments;
create policy "live_comments_select_visible_live"
  on public.live_comments
  for select
  using (
    exists (
      select 1
      from public.live_streams
      where live_streams.id = live_comments.live_id
    )
  );

drop policy if exists "live_comments_insert_own" on public.live_comments;
drop policy if exists "live_comments_insert_visible_live" on public.live_comments;
create policy "live_comments_insert_visible_live"
  on public.live_comments
  for insert
  to authenticated
  with check (
    auth.uid() = user_id
    and exists (
      select 1
      from public.live_streams
      where live_streams.id = live_comments.live_id
        and live_streams.is_active = true
    )
  );

commit;
