-- First-party channel points for live viewers.
-- Points are creator-bound, non-transferable, non-purchasable and non-withdrawable.
-- All earning/redemption mutations are server-only; the mobile client receives
-- signed session heartbeats and read-only projections.

begin;

alter table if exists public.monetization_runtime_flags
  add column if not exists channel_points_enabled boolean not null default false;

create table if not exists public.live_point_settings (
  live_id uuid primary key references public.live_streams(id) on delete cascade,
  creator_id uuid not null references public.profiles(id) on delete cascade,
  enabled boolean not null default true,
  interval_seconds integer not null default 300 check (interval_seconds between 60 and 3600),
  points_per_interval integer not null default 10 check (points_per_interval between 1 and 1000),
  daily_cap integer not null default 2000 check (daily_cap between 1 and 100000),
  updated_at timestamptz not null default now()
);

create table if not exists public.channel_points_balances (
  live_id uuid not null references public.live_streams(id) on delete cascade,
  creator_id uuid not null references public.profiles(id) on delete cascade,
  viewer_id uuid not null references public.profiles(id) on delete cascade,
  balance integer not null default 0 check (balance >= 0),
  earned_total bigint not null default 0 check (earned_total >= 0),
  redeemed_total bigint not null default 0 check (redeemed_total >= 0),
  updated_at timestamptz not null default now(),
  primary key (live_id, viewer_id)
);

create table if not exists public.channel_points_earnings (
  id uuid primary key default gen_random_uuid(),
  live_id uuid not null references public.live_streams(id) on delete cascade,
  viewer_id uuid not null references public.profiles(id) on delete cascade,
  event_type text not null default 'watch_interval',
  interval_id text not null,
  points integer not null check (points > 0),
  idempotency_key text not null unique,
  occurred_at timestamptz not null default now(),
  unique (live_id, viewer_id, event_type, interval_id)
);

create table if not exists public.channel_point_rewards (
  id uuid primary key default gen_random_uuid(),
  live_id uuid not null references public.live_streams(id) on delete cascade,
  creator_id uuid not null references public.profiles(id) on delete cascade,
  title text not null,
  description text not null default '',
  cost integer not null check (cost > 0),
  active boolean not null default true,
  stock integer check (stock is null or stock >= 0),
  cooldown_seconds integer not null default 0 check (cooldown_seconds between 0 and 86400),
  version bigint not null default 1 check (version > 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists channel_point_rewards_live_idx
  on public.channel_point_rewards (live_id, active, cost, created_at);

create table if not exists public.channel_point_sessions (
  id uuid primary key default gen_random_uuid(),
  live_id uuid not null references public.live_streams(id) on delete cascade,
  viewer_id uuid not null references public.profiles(id) on delete cascade,
  session_nonce text not null unique,
  token_hash text not null unique,
  device_hash text,
  joined_at timestamptz not null default now(),
  last_heartbeat_at timestamptz not null default now(),
  last_rewarded_interval integer not null default 0 check (last_rewarded_interval >= 0),
  state text not null default 'active' check (state in ('active', 'ended', 'expired')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create unique index if not exists channel_point_sessions_active_uidx
  on public.channel_point_sessions (live_id, viewer_id)
  where state = 'active';

create table if not exists public.channel_point_redemptions (
  id uuid primary key default gen_random_uuid(),
  live_id uuid not null references public.live_streams(id) on delete cascade,
  reward_id uuid not null references public.channel_point_rewards(id) on delete cascade,
  viewer_id uuid not null references public.profiles(id) on delete cascade,
  cost integer not null check (cost > 0),
  status text not null default 'requested'
    check (status in ('requested', 'approved', 'rejected', 'completed', 'canceled')),
  request_nonce text not null,
  refunded_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (live_id, request_nonce)
);

create index if not exists channel_point_redemptions_live_idx
  on public.channel_point_redemptions (live_id, status, created_at desc);

alter table public.live_point_settings enable row level security;
alter table public.channel_points_balances enable row level security;
alter table public.channel_points_earnings enable row level security;
alter table public.channel_point_rewards enable row level security;
alter table public.channel_point_sessions enable row level security;
alter table public.channel_point_redemptions enable row level security;

revoke all on table public.live_point_settings from public, anon, authenticated;
revoke all on table public.channel_points_balances from public, anon, authenticated;
revoke all on table public.channel_points_earnings from public, anon, authenticated;
revoke all on table public.channel_point_rewards from public, anon, authenticated;
revoke all on table public.channel_point_sessions from public, anon, authenticated;
revoke all on table public.channel_point_redemptions from public, anon, authenticated;
grant all on table public.live_point_settings to service_role;
grant all on table public.channel_points_balances to service_role;
grant all on table public.channel_points_earnings to service_role;
grant all on table public.channel_point_rewards to service_role;
grant all on table public.channel_point_sessions to service_role;
grant all on table public.channel_point_redemptions to service_role;

drop policy if exists live_point_settings_select_active on public.live_point_settings;
create policy live_point_settings_select_active
  on public.live_point_settings
  for select to authenticated
  using (
    enabled = true and exists (
      select 1 from public.live_streams ls
      where ls.id = live_point_settings.live_id and ls.is_active = true
    )
  );

drop policy if exists channel_points_balances_select_own on public.channel_points_balances;
create policy channel_points_balances_select_own
  on public.channel_points_balances
  for select to authenticated
  using (viewer_id = auth.uid());

drop policy if exists channel_points_earnings_select_own on public.channel_points_earnings;
create policy channel_points_earnings_select_own
  on public.channel_points_earnings
  for select to authenticated
  using (viewer_id = auth.uid());

drop policy if exists channel_point_rewards_select_active on public.channel_point_rewards;
create policy channel_point_rewards_select_active
  on public.channel_point_rewards
  for select to authenticated
  using (
    active = true or creator_id = auth.uid()
  );

drop policy if exists channel_point_redemptions_select_participant on public.channel_point_redemptions;
create policy channel_point_redemptions_select_participant
  on public.channel_point_redemptions
  for select to authenticated
  using (
    viewer_id = auth.uid() or exists (
      select 1 from public.channel_point_rewards cpr
      where cpr.id = channel_point_redemptions.reward_id and cpr.creator_id = auth.uid()
    )
  );

commit;
