# DietLog Supabase backend

DietLog keeps SQLite and local preferences as the source of truth. Supabase is an optional second
cloud backup/sync provider alongside Google Drive; it stores only versioned DietLog snapshots for an
authenticated user.

## Security model

- The Android app uses a Supabase **publishable** key, never a secret/service-role key.
- Users authenticate with Supabase Auth email/password. Passwords are sent only to the Auth API and
  are never stored by DietLog.
- The refresh token is encrypted with an Android Keystore AES-GCM key before local persistence.
  Access tokens are kept only in memory.
- `public.dietlog_snapshots` has Row Level Security enabled and every policy binds `user_id` to
  `auth.uid()`.
- `anon` receives no table privileges. `authenticated` receives only the CRUD privileges needed by
  the app, still constrained by RLS.
- Snapshot payloads are the same gzip-compressed logical backups used by the Drive provider. The
  table caps their Base64 representation to 16 MiB; the Android client caps compressed snapshots to
  12 MiB.

## Schema

Apply this schema to the dedicated DietLog Supabase project:

```sql
create table if not exists public.dietlog_snapshots (
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  snapshot_key text not null,
  device_id text not null,
  kind text not null,
  revision bigint not null default 0,
  snapshot_created_at bigint not null,
  schema_version integer not null,
  payload_base64 text not null,
  primary key (user_id, snapshot_key),
  constraint dietlog_snapshot_key_length check (char_length(snapshot_key) between 1 and 200),
  constraint dietlog_device_id_length check (char_length(device_id) between 1 and 100),
  constraint dietlog_snapshot_kind check (kind in ('device_snapshot', 'history_snapshot')),
  constraint dietlog_snapshot_revision check (revision >= 0),
  constraint dietlog_snapshot_created_at check (snapshot_created_at >= 0),
  constraint dietlog_snapshot_schema check (schema_version between 1 and 100),
  constraint dietlog_snapshot_payload_size check (octet_length(payload_base64) <= 16777216)
);

create index if not exists dietlog_snapshots_device_history_idx
  on public.dietlog_snapshots (user_id, device_id, kind, snapshot_created_at desc);

alter table public.dietlog_snapshots enable row level security;

revoke all on table public.dietlog_snapshots from anon;
grant select, insert, update, delete on table public.dietlog_snapshots to authenticated;

create policy "dietlog snapshots select own"
  on public.dietlog_snapshots
  for select
  to authenticated
  using ((select auth.uid()) = user_id);

create policy "dietlog snapshots insert own"
  on public.dietlog_snapshots
  for insert
  to authenticated
  with check ((select auth.uid()) = user_id);

create policy "dietlog snapshots update own"
  on public.dietlog_snapshots
  for update
  to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

create policy "dietlog snapshots delete own"
  on public.dietlog_snapshots
  for delete
  to authenticated
  using ((select auth.uid()) = user_id);
```

The final project URL and publishable key are supplied at build time through
`DIETLOG_SUPABASE_URL` and `DIETLOG_SUPABASE_PUBLISHABLE_KEY`. No Supabase secret key belongs in the
repository, APK, diagnostics, or GitHub Actions logs.
