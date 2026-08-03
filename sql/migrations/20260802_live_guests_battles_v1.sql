-- Live guests and battles (feature/live-gifts)
--
-- This migration creates the server-authoritative collaboration ledger. It is
-- intentionally not enabled by this file: the API runtime flag and an SFU
-- token provider must be configured before clients can join a guest room.

begin;

create table if not exists public.live_guest_invitations (
  id uuid primary key default gen_random_uuid(),
  live_id uuid not null references public.live_streams(id) on delete cascade,
  host_user_id uuid not null references public.profiles(id) on delete cascade,
  invitee_user_id uuid not null references public.profiles(id) on delete cascade,
  status text not null default 'pending'
    check (status in ('pending', 'accepted', 'rejected', 'canceled', 'expired')),
  request_nonce text not null,
  expires_at timestamptz not null,
  responded_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (live_id, request_nonce),
  check (host_user_id <> invitee_user_id)
);

create unique index if not exists live_guest_invitation_pending_uidx
  on public.live_guest_invitations (live_id, invitee_user_id)
  where status = 'pending';

create index if not exists live_guest_invitations_invitee_idx
  on public.live_guest_invitations (invitee_user_id, status, created_at desc);

create table if not exists public.live_participants (
  id uuid primary key default gen_random_uuid(),
  live_id uuid not null references public.live_streams(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  role text not null check (role in ('host', 'guest', 'viewer')),
  participant_identity text not null,
  state text not null default 'invited'
    check (state in ('invited', 'connected', 'disconnected', 'removed')),
  joined_at timestamptz,
  left_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (live_id, user_id),
  unique (live_id, participant_identity)
);

create index if not exists live_participants_live_state_idx
  on public.live_participants (live_id, state, role);

create table if not exists public.live_battles (
  id uuid primary key default gen_random_uuid(),
  live_id uuid not null references public.live_streams(id) on delete cascade,
  left_user_id uuid not null references public.profiles(id) on delete cascade,
  right_user_id uuid not null references public.profiles(id) on delete cascade,
  status text not null default 'active'
    check (status in ('active', 'ended', 'canceled')),
  score_version bigint not null default 0 check (score_version >= 0),
  left_score bigint not null default 0 check (left_score >= 0),
  right_score bigint not null default 0 check (right_score >= 0),
  winner_user_id uuid references public.profiles(id) on delete set null,
  started_at timestamptz not null default now(),
  ended_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (left_user_id <> right_user_id),
  check (winner_user_id is null or winner_user_id in (left_user_id, right_user_id))
);

create unique index if not exists live_battles_active_uidx
  on public.live_battles (live_id)
  where status = 'active';

create table if not exists public.live_battle_events (
  id uuid primary key default gen_random_uuid(),
  battle_id uuid not null references public.live_battles(id) on delete cascade,
  event_id text not null,
  source text not null check (source in ('gift_ledger', 'channel_points', 'system')),
  event_type text not null,
  actor_user_id uuid references public.profiles(id) on delete set null,
  points bigint not null check (points > 0),
  occurred_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  unique (battle_id, event_id)
);

create index if not exists live_battle_events_timeline_idx
  on public.live_battle_events (battle_id, occurred_at asc);

alter table public.live_guest_invitations enable row level security;
alter table public.live_participants enable row level security;
alter table public.live_battles enable row level security;
alter table public.live_battle_events enable row level security;

revoke all on table public.live_guest_invitations from public, anon, authenticated;
revoke all on table public.live_participants from public, anon, authenticated;
revoke all on table public.live_battles from public, anon, authenticated;
revoke all on table public.live_battle_events from public, anon, authenticated;
grant all on table public.live_guest_invitations to service_role;
grant all on table public.live_participants to service_role;
grant all on table public.live_battles to service_role;
grant all on table public.live_battle_events to service_role;

drop policy if exists live_guest_invitations_select_participant on public.live_guest_invitations;
create policy live_guest_invitations_select_participant
  on public.live_guest_invitations
  for select to authenticated
  using (auth.uid() = host_user_id or auth.uid() = invitee_user_id);

drop policy if exists live_participants_select_self_or_host on public.live_participants;
create policy live_participants_select_self_or_host
  on public.live_participants
  for select to authenticated
  using (
    auth.uid() = user_id
    or exists (
      select 1 from public.live_streams ls
      where ls.id = live_participants.live_id
        and ls.host_user_id = auth.uid()
    )
  );

drop policy if exists live_battles_select_participant_or_active on public.live_battles;
create policy live_battles_select_participant_or_active
  on public.live_battles
  for select to authenticated
  using (
    auth.uid() in (left_user_id, right_user_id)
    or exists (
      select 1 from public.live_streams ls
      where ls.id = live_battles.live_id
        and ls.is_active = true
    )
  );

drop policy if exists live_battle_events_select_participant on public.live_battle_events;
create policy live_battle_events_select_participant
  on public.live_battle_events
  for select to authenticated
  using (
    exists (
      select 1
      from public.live_battles lb
      where lb.id = live_battle_events.battle_id
        and auth.uid() in (lb.left_user_id, lb.right_user_id)
    )
  );

do $$
begin
  if exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    if not exists (
      select 1 from pg_publication_tables
      where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'live_guest_invitations'
    ) then alter publication supabase_realtime add table public.live_guest_invitations; end if;
    if not exists (
      select 1 from pg_publication_tables
      where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'live_participants'
    ) then alter publication supabase_realtime add table public.live_participants; end if;
    if not exists (
      select 1 from pg_publication_tables
      where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'live_battles'
    ) then alter publication supabase_realtime add table public.live_battles; end if;
    if not exists (
      select 1 from pg_publication_tables
      where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'live_battle_events'
    ) then alter publication supabase_realtime add table public.live_battle_events; end if;
  end if;
end $$;

commit;
