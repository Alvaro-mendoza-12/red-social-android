-- Historial de visitas de perfil con consentimiento mutuo.
-- Desactivado por defecto: una visita solo se registra cuando ambos perfiles
-- tienen habilitada la función.

alter table public.profiles
    add column if not exists profile_view_history_enabled boolean not null default false;

create table if not exists public.profile_views (
    profile_id uuid not null references public.profiles(id) on delete cascade,
    viewer_id uuid not null references public.profiles(id) on delete cascade,
    viewed_at timestamptz not null default now(),
    primary key (profile_id, viewer_id),
    constraint profile_views_not_self check (profile_id <> viewer_id)
);

create index if not exists profile_views_owner_recent_idx
    on public.profile_views(profile_id, viewed_at desc);

alter table public.profile_views enable row level security;

drop policy if exists profile_views_owner_read on public.profile_views;
create policy profile_views_owner_read
    on public.profile_views for select
    using (
        profile_id = auth.uid()
        and exists (
            select 1 from public.profiles owner
            where owner.id = auth.uid()
              and owner.profile_view_history_enabled = true
        )
    );

drop policy if exists profile_views_mutual_insert on public.profile_views;
create policy profile_views_mutual_insert
    on public.profile_views for insert
    with check (
        viewer_id = auth.uid()
        and exists (
            select 1 from public.profiles viewer
            where viewer.id = auth.uid()
              and viewer.profile_view_history_enabled = true
        )
        and exists (
            select 1 from public.profiles owner
            where owner.id = profile_id
              and owner.profile_view_history_enabled = true
        )
    );

drop policy if exists profile_views_mutual_update on public.profile_views;
create policy profile_views_mutual_update
    on public.profile_views for update
    using (viewer_id = auth.uid())
    with check (viewer_id = auth.uid());

drop policy if exists profile_views_participant_delete on public.profile_views;
create policy profile_views_participant_delete
    on public.profile_views for delete
    using (viewer_id = auth.uid() or profile_id = auth.uid());

