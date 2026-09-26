-- Cross-LIVE host battles for feature/live-gifts.
-- Two independent active LIVE streams join a server-issued shared LiveKit room
-- only after the invited creator explicitly accepts.

begin;

alter table if exists public.live_battles
  add column if not exists opponent_live_id uuid references public.live_streams(id) on delete cascade;
alter table if exists public.live_battles
  add column if not exists room_name text;
alter table if exists public.live_battles
  add column if not exists requested_by_user_id uuid references public.profiles(id) on delete set null;
alter table if exists public.live_battles
  add column if not exists invitation_expires_at timestamptz;
alter table if exists public.live_battles
  add column if not exists responded_at timestamptz;

alter table if exists public.live_battles
  drop constraint if exists live_battles_status_check;
alter table if exists public.live_battles
  drop constraint if exists fallback_live_battles_status_check;
alter table if exists public.live_battles
  add constraint live_battles_status_check
  check (status in ('invited', 'active', 'rejected', 'ended', 'canceled', 'expired'));

update public.live_battles
   set room_name = coalesce(nullif(room_name, ''), 'novvin-battle-' || id::text)
 where room_name is null or room_name = '';

drop index if exists public.live_battles_active_uidx;
create unique index if not exists live_battles_active_uidx
  on public.live_battles(live_id)
  where status in ('invited', 'active');

create unique index if not exists live_battles_opponent_active_uidx
  on public.live_battles(opponent_live_id)
  where opponent_live_id is not null and status in ('invited', 'active');

create index if not exists live_battles_invitation_expiry_idx
  on public.live_battles(invitation_expires_at)
  where status = 'invited';

commit;
