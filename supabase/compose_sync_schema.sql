-- WireKey cloud sync — Supabase schema, RLS policies and realtime publication.
--
-- Run once, in the Supabase dashboard: SQL Editor → New query → paste → Run.
-- Safe to run again; every statement is idempotent.
--
-- Ownership is auth.users.id (a uuid), never the email address: emails can be changed,
-- and auth.uid() is what the policies below can actually verify.

-- ── Table ────────────────────────────────────────────────────────────────────
-- One row per user. user_id is the primary key, which is what lets the app treat
-- "first ever sync" and "replace what is there" as the same single upsert.
create table if not exists public.compose_documents (
    user_id    uuid        primary key references auth.users (id) on delete cascade,
    content    text        not null default '',
    updated_at timestamptz not null default now()
);

-- ── updated_at ───────────────────────────────────────────────────────────────
-- Maintained by the database rather than the client, so a phone with a wrong clock
-- cannot write a misleading timestamp.
create or replace function public.set_compose_documents_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

drop trigger if exists compose_documents_set_updated_at on public.compose_documents;
create trigger compose_documents_set_updated_at
    before update on public.compose_documents
    for each row execute function public.set_compose_documents_updated_at();

-- ── Row Level Security ───────────────────────────────────────────────────────
-- Without these, the anon key shipped in the app could read every user's document.
-- With them, the key can only ever reach the row belonging to the signed-in session.
alter table public.compose_documents enable row level security;

drop policy if exists "Users read their own compose document"   on public.compose_documents;
drop policy if exists "Users insert their own compose document" on public.compose_documents;
drop policy if exists "Users update their own compose document" on public.compose_documents;
drop policy if exists "Users delete their own compose document" on public.compose_documents;

create policy "Users read their own compose document"
    on public.compose_documents for select
    to authenticated
    using ((select auth.uid()) = user_id);

create policy "Users insert their own compose document"
    on public.compose_documents for insert
    to authenticated
    with check ((select auth.uid()) = user_id);

-- USING decides which row may be updated; WITH CHECK stops the row being handed to
-- somebody else on the way out.
create policy "Users update their own compose document"
    on public.compose_documents for update
    to authenticated
    using ((select auth.uid()) = user_id)
    with check ((select auth.uid()) = user_id);

create policy "Users delete their own compose document"
    on public.compose_documents for delete
    to authenticated
    using ((select auth.uid()) = user_id);

-- Supabase grants these by default on new public tables; stated explicitly so the
-- schema still works on a project whose default privileges have been tightened.
grant select, insert, update, delete on public.compose_documents to authenticated;

-- ── Realtime ─────────────────────────────────────────────────────────────────
-- Puts the table on the realtime publication, which is what pushes one phone's Sync
-- out to the others. RLS is applied to these messages too: a phone is only sent
-- changes to rows its own session is allowed to read.
do $$
begin
    if not exists (
        select 1
        from pg_publication_tables
        where pubname    = 'supabase_realtime'
          and schemaname = 'public'
          and tablename  = 'compose_documents'
    ) then
        alter publication supabase_realtime add table public.compose_documents;
    end if;
end
$$;

-- Not required by WireKey: the app filters on user_id, which is the primary key and so
-- is already present in the default replica identity. Uncomment only if you later need
-- the previous row contents (old_record) in realtime payloads.
-- alter table public.compose_documents replica identity full;
