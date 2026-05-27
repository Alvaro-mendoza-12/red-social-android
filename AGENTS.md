# Conectate Agent Guide

This repo is a mixed Android, web, SQL and signaling-server workspace. Keep changes small, scoped and tied to the active product tree.

## Active Product Roots

- `app/`: Android app, Jetpack Compose, Hilt, Room, Supabase and Android tests.
- `Conectate-web/`: Vite/React web client.
- `signaling-server/`: Node Socket.io/WebRTC signaling service.
- `supabase/`: Supabase edge functions and related runtime code.
- `sql/`: deployable migrations, hotfixes, diagnostics, security scripts and archive.
- `tools/`: quality gates, audits and local automation.
- `docs/`: live documentation plus `docs/archive/` for historical material.

## Repo Hygiene

- Keep the repo root limited to global config, wrappers and the main README.
- Do not add logs, screenshots, APKs, SQL snippets, one-off repair scripts or local backups to the root.
- Put temporary/manual output under `tmp/` or `build/`; both are ignored.
- Put historical snapshots under `docs/archive/` only when they must be versioned.
- Treat `docs/archive/`, `tools/legacy/`, `backup/`, `tmp/` and `app_backup_before_restore/` as non-active unless the task is explicitly about recovery.

## Android Boundaries

- `presentation/`: Compose screens, components, navigation and ViewModels.
- `data/`: repositories, Room, Supabase, services and remote/local data access.
- `core/`: shared policies, security, observability, concurrency and result handling.
- `util/`: narrow helpers that are not yet worth a dedicated package.

Large repositories should be split by responsibility: `Store`, `Remote`, `Sync`, `Media`, policy/helper classes, then a small orchestrating repository.

## SQL Rules

- Automated deploys must use `sql/release-manifest.txt`.
- New schema changes go in `sql/migrations/`.
- Production/staging fixes go in `sql/hotfixes/`.
- Verification-only SQL goes in `sql/diagnostics/`.
- RLS/security hardening goes in `sql/security/`.
- Superseded or manual SQL goes in `sql/archive/`.

## Verification

- Repo hygiene: `npm run repo:hygiene`
- Android debug gate: `npm run quality:android`
- Full Android gate: `npm run quality:full`
- Web lint/build: `npm run lint:web` and `npm run build:web`
