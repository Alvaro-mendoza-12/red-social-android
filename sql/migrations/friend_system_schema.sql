-- Create friends table
create table public.friends (
  id uuid default uuid_generate_v4() primary key,
  sender_id uuid references public.profiles(id) not null,
  receiver_id uuid references public.profiles(id) not null,
  status text check (status in ('pending', 'accepted', 'rejected')) default 'pending',
  created_at timestamp with time zone default timezone('utc'::text, now()) not null,
  updated_at timestamp with time zone default timezone('utc'::text, now()) not null,
  unique(sender_id, receiver_id)
);

-- RLS Policies
alter table public.friends enable row level security;

create policy "Users can see their own friend requests"
  on public.friends for select
  using (auth.uid() = sender_id or auth.uid() = receiver_id);

create policy "Users can send friend requests"
  on public.friends for insert
  with check (auth.uid() = sender_id);

create policy "Users can update their own received requests (accept/reject)"
  on public.friends for update
  using (auth.uid() = receiver_id);

create policy "Users can delete their own requests or friendships"
  on public.friends for delete
  using (auth.uid() = sender_id or auth.uid() = receiver_id);

-- Notifications Trigger (Corrected to match Notification table schema)
create or replace function public.handle_new_friend_request()
returns trigger as $$
begin
  insert into public.notifications (recipient_id, actor_id, type, target_id, target_type, content)
  values (new.receiver_id, new.sender_id, 'friend_request', new.id::text, 'friend_request', 'te ha enviado una solicitud de amistad');
  return new;
end;
$$ language plpgsql security definer;

create trigger on_new_friend_request
  after insert on public.friends
  for each row execute procedure public.handle_new_friend_request();

create or replace function public.handle_friend_request_accepted()
returns trigger as $$
begin
  if new.status = 'accepted' and old.status = 'pending' then
    insert into public.notifications (recipient_id, actor_id, type, target_id, target_type, content)
    values (new.sender_id, new.receiver_id, 'friend_request_accepted', new.id::text, 'friend_request', 'ha aceptado tu solicitud de amistad');
  end if;
  return new;
end;
$$ language plpgsql security definer;

create trigger on_friend_request_accepted
  after update on public.friends
  for each row execute procedure public.handle_friend_request_accepted();
