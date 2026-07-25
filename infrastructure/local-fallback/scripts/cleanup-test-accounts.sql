BEGIN;

CREATE TEMP TABLE cleanup_test_users AS
SELECT id, lower(id::text) AS local_id, lower(COALESCE(supabase_user_id::text, '')) AS remote_id
FROM fallback_users
WHERE lower(email) LIKE 'module-%'
   OR lower(email) LIKE 'module1.%'
   OR lower(email) LIKE 'codex.%'
   OR lower(email) LIKE 'codex-%'
   OR lower(email) LIKE 'video.%'
   OR lower(email) LIKE 'imported.comment.report.%';

CREATE TEMP TABLE cleanup_ids AS
SELECT local_id AS id FROM cleanup_test_users
UNION
SELECT remote_id FROM cleanup_test_users WHERE remote_id <> '';

CREATE TEMP TABLE cleanup_posts AS
SELECT lower(id::text) AS id FROM fallback_posts WHERE lower(user_id) IN (SELECT id FROM cleanup_ids);
CREATE TEMP TABLE cleanup_post_comments AS
SELECT lower(id::text) AS id FROM fallback_post_comments
WHERE lower(user_id) IN (SELECT id FROM cleanup_ids) OR lower(post_id) IN (SELECT id FROM cleanup_posts);
DELETE FROM fallback_post_comment_reactions WHERE lower(comment_id) IN (SELECT id FROM cleanup_post_comments) OR lower(user_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_post_comments WHERE lower(id::text) IN (SELECT id FROM cleanup_post_comments);
DELETE FROM fallback_post_reactions WHERE lower(post_id) IN (SELECT id FROM cleanup_posts) OR lower(user_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_shares WHERE lower(post_id) IN (SELECT id FROM cleanup_posts) OR lower(user_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_posts WHERE lower(id::text) IN (SELECT id FROM cleanup_posts);

CREATE TEMP TABLE cleanup_videos AS
SELECT lower(id::text) AS id FROM fallback_videos WHERE lower(user_id) IN (SELECT id FROM cleanup_ids);
CREATE TEMP TABLE cleanup_video_comments AS
SELECT lower(id::text) AS id FROM fallback_video_comments
WHERE lower(user_id) IN (SELECT id FROM cleanup_ids) OR lower(video_id) IN (SELECT id FROM cleanup_videos);
DELETE FROM fallback_video_comment_reactions WHERE lower(comment_id) IN (SELECT id FROM cleanup_video_comments) OR lower(user_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_video_comment_reports WHERE lower(comment_id) IN (SELECT id FROM cleanup_video_comments) OR lower(video_id) IN (SELECT id FROM cleanup_videos) OR lower(reporter_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_video_comments WHERE lower(id::text) IN (SELECT id FROM cleanup_video_comments);
DELETE FROM fallback_video_favorites WHERE lower(video_id) IN (SELECT id FROM cleanup_videos) OR lower(user_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_video_likes WHERE lower(video_id) IN (SELECT id FROM cleanup_videos) OR lower(user_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_video_views WHERE lower(video_id) IN (SELECT id FROM cleanup_videos) OR lower(user_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_video_metrics WHERE lower(video_id) IN (SELECT id FROM cleanup_videos);
DELETE FROM fallback_video_deletions WHERE lower(video_id) IN (SELECT id FROM cleanup_videos) OR lower(user_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_video_reports WHERE lower(video_id) IN (SELECT id FROM cleanup_videos) OR lower(reporter_id) IN (SELECT id FROM cleanup_ids) OR lower(video_owner_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_videos WHERE lower(id::text) IN (SELECT id FROM cleanup_videos);

CREATE TEMP TABLE cleanup_moments AS
SELECT lower(id::text) AS id FROM fallback_moments WHERE lower(user_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_moment_views WHERE lower(moment_id::text) IN (SELECT id FROM cleanup_moments) OR lower(user_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_moment_deletions WHERE lower(moment_id) IN (SELECT id FROM cleanup_moments) OR lower(user_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_moments WHERE lower(id::text) IN (SELECT id FROM cleanup_moments);

CREATE TEMP TABLE cleanup_rooms AS
SELECT room_kind, lower(id::text) AS id FROM fallback_collection_rooms WHERE lower(creator_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_room_messages m USING cleanup_rooms r WHERE m.room_kind = r.room_kind AND lower(m.room_id) = r.id;
DELETE FROM fallback_collection_members m USING cleanup_rooms r WHERE m.room_kind = r.room_kind AND lower(m.room_id) = r.id;
DELETE FROM fallback_collection_read_states s USING cleanup_rooms r WHERE s.room_kind = r.room_kind AND lower(s.room_id) = r.id;
DELETE FROM fallback_collection_invites i USING cleanup_rooms r WHERE i.room_kind = r.room_kind AND lower(i.room_id) = r.id;
DELETE FROM fallback_collection_rooms r USING cleanup_rooms c WHERE r.room_kind = c.room_kind AND lower(r.id::text) = c.id;
DELETE FROM fallback_room_messages WHERE lower(sender_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_collection_members WHERE lower(user_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_collection_read_states WHERE lower(user_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_collection_invites WHERE lower(created_by) IN (SELECT id FROM cleanup_ids);

CREATE TEMP TABLE cleanup_voice_rooms AS
SELECT lower(id::text) AS id FROM fallback_voice_rooms WHERE lower(creator_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_voice_room_participants WHERE lower(room_id) IN (SELECT id FROM cleanup_voice_rooms) OR lower(user_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_voice_rooms WHERE lower(id::text) IN (SELECT id FROM cleanup_voice_rooms);

CREATE TEMP TABLE cleanup_live_streams AS
SELECT lower(id::text) AS id FROM fallback_live_streams WHERE lower(host_user_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_live_comments
WHERE lower(live_id::text) IN (SELECT id FROM cleanup_live_streams)
   OR lower(user_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_live_streams WHERE lower(id::text) IN (SELECT id FROM cleanup_live_streams);

CREATE TEMP TABLE cleanup_chats AS
SELECT DISTINCT lower(chat_id::text) AS id FROM fallback_chat_participants WHERE lower(user_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_messages WHERE lower(chat_id::text) IN (SELECT id FROM cleanup_chats) OR lower(sender_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_chat_hidden WHERE lower(chat_id::text) IN (SELECT id FROM cleanup_chats) OR lower(user_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_chat_participants WHERE lower(chat_id::text) IN (SELECT id FROM cleanup_chats) OR lower(user_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_chats WHERE lower(id::text) IN (SELECT id FROM cleanup_chats);

CREATE TEMP TABLE cleanup_submissions AS
SELECT lower(id::text) AS id FROM fallback_challenge_submissions WHERE lower(user_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_challenge_votes WHERE lower(submission_id::text) IN (SELECT id FROM cleanup_submissions) OR lower(voter_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_challenge_submissions WHERE lower(id::text) IN (SELECT id FROM cleanup_submissions);
DELETE FROM fallback_challenges_master WHERE lower(creator_id) IN (SELECT id FROM cleanup_ids);

DELETE FROM fallback_fcm_tokens WHERE lower(user_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_profile_moderation WHERE lower(user_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_random_chat_messages
WHERE session_id IN (
  SELECT id FROM fallback_random_chat_sessions
  WHERE lower(user_a) IN (SELECT id FROM cleanup_ids)
     OR lower(user_b) IN (SELECT id FROM cleanup_ids)
);
DELETE FROM fallback_random_chat_sessions
WHERE lower(user_a) IN (SELECT id FROM cleanup_ids)
   OR lower(user_b) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_random_chat_queue WHERE lower(user_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_proximity_presence WHERE lower(user_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_notifications WHERE lower(recipient_id) IN (SELECT id FROM cleanup_ids) OR lower(actor_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_follows WHERE lower(follower_id) IN (SELECT id FROM cleanup_ids) OR lower(following_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_friends WHERE lower(sender_id) IN (SELECT id FROM cleanup_ids) OR lower(receiver_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_user_blocks WHERE lower(blocker_id) IN (SELECT id FROM cleanup_ids) OR lower(blocked_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_nicknames WHERE lower(user_id) IN (SELECT id FROM cleanup_ids) OR lower(target_user_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_reports WHERE lower(reporter_id) IN (SELECT id FROM cleanup_ids) OR lower(reported_user_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_moderation_events WHERE lower(actor_id) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_audit_events WHERE lower(user_id::text) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_storage_objects WHERE lower(owner_id::text) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_web_sessions WHERE lower(user_id::text) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_web_login_pairings WHERE lower(COALESCE(approved_by_user_id::text, '')) IN (SELECT id FROM cleanup_ids);
DELETE FROM fallback_users WHERE id IN (SELECT id FROM cleanup_test_users);

SELECT COUNT(*) AS deleted_test_accounts FROM cleanup_test_users;
COMMIT;
