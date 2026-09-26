'use strict'

// Emit a fixture-only PostgreSQL regression from the actual candidate query.
// Run the output ONLY in an isolated disposable PostgreSQL instance. No real
// accounts, credentials or network calls are used to generate this script.
const assert = require('node:assert/strict')
const { candidateBundles } = require('../src/playmatch-routes')

const viewer = '11111111-1111-4111-8111-111111111111'
const split = '22222222-2222-4222-8222-222222222222'
const compatible = '33333333-3333-4333-8333-333333333333'
const noGames = '44444444-4444-4444-8444-444444444444'

function literal(value) {
  if (Array.isArray(value)) return `ARRAY[${value.map(literal).join(',')}]::text[]`
  if (typeof value === 'boolean') return value ? 'TRUE' : 'FALSE'
  assert.equal(typeof value, 'string')
  return `'${value.replace(/'/g, "''")}'`
}

async function assertion(filters, expected, name) {
  let statement
  await candidateBundles({ query: async (sql, params) => {
    statement = sql.replace(/\$(\d+)\b/g, (_, index) => `(${literal(params[Number(index) - 1])})`)
    return { rows: [] }
  } }, { id: viewer }, filters)
  const wanted = [...expected].sort()
  return `DO $regression$ DECLARE actual text[]; BEGIN
    SELECT COALESCE(array_agg(user_id::text ORDER BY user_id::text), ARRAY[]::text[]) INTO actual
      FROM (${statement}) candidates;
    IF actual IS DISTINCT FROM ${literal(wanted)} THEN
      RAISE EXCEPTION 'PlayMatch regression failed: %', ${literal(name)};
    END IF;
    RAISE NOTICE 'PASS %', ${literal(name)};
  END $regression$;`
}

async function buildRegressionSql() {
  const setup = `BEGIN;
    SET LOCAL statement_timeout = '5s';
    CREATE TEMP TABLE fallback_users (id uuid, supabase_user_id text, display_name text, avatar_url text, avatar_media_type text);
    CREATE TEMP TABLE fallback_gaming_profiles (user_id uuid, discoverable boolean DEFAULT TRUE, adult_confirmed boolean DEFAULT TRUE,
      gamer_display_name text, gamer_bio text DEFAULT '', region_code text DEFAULT 'latam', languages text[] DEFAULT ARRAY['es'],
      play_style text DEFAULT 'casual', voice_preference boolean DEFAULT FALSE, updated_at timestamptz DEFAULT NOW());
    CREATE TEMP TABLE fallback_user_blocks (blocker_id text, blocked_id text);
    CREATE TEMP TABLE fallback_profile_moderation (user_id text, is_suspended boolean, suspended_until timestamptz, suspension_end_date timestamptz);
    CREATE TEMP TABLE fallback_gaming_matches (user_a_id uuid, user_b_id uuid, created_at timestamptz DEFAULT NOW());
    CREATE TEMP TABLE fallback_user_games (user_id uuid, game_id text, platform text, role_key text);
    CREATE TEMP TABLE fallback_game_catalog (id text, name text, active boolean DEFAULT TRUE);
    INSERT INTO fallback_users(id) VALUES (${literal(viewer)}), (${literal(split)}), (${literal(compatible)}), (${literal(noGames)});
    INSERT INTO fallback_gaming_profiles(user_id) SELECT id FROM fallback_users;
    INSERT INTO fallback_game_catalog(id, name) VALUES ('valorant','Valorant'),('fortnite','Fortnite'),('inactive','Inactive');
    UPDATE fallback_game_catalog SET active=FALSE WHERE id='inactive';
    INSERT INTO fallback_user_games VALUES
      (${literal(split)},'valorant','PC','duelist'), (${literal(split)},'fortnite','PlayStation','support'),
      (${literal(compatible)},'valorant','PlayStation','support'), (${literal(noGames)},'inactive','Mobile','support');`
  const checks = []
  checks.push(await assertion({}, [split, compatible, noGames], 'unfiltered discovery and self exclusion'))
  checks.push(await assertion({ gameId: 'valorant', platform: 'playstation' }, [compatible], 'same game and platform'))
  checks.push(await assertion({ gameIds: ['valorant'], roles: ['support'] }, [compatible], 'same game and role'))
  checks.push(await assertion({ platforms: ['pc'], roles: ['support'] }, [], 'same platform and role'))
  checks.push(await assertion({ gameIds: ['valorant', 'fortnite'], platforms: ['playstation'], roles: ['support'] }, [split, compatible], 'OR choices with mandatory AND fields'))
  checks.push(await assertion({ region: 'europe' }, [], 'region is mandatory'))
  checks.push(await assertion({ styles: ['competitive'] }, [], 'style is mandatory'))
  checks.push(await assertion({ languages: ['en'] }, [], 'language is mandatory'))
  checks.push(await assertion({ voiceRequired: true }, [], 'microphone is mandatory'))
  checks.push(await assertion({ gameId: 'inactive' }, [], 'inactive games cannot satisfy filters'))
  checks.push(`INSERT INTO fallback_user_blocks VALUES (${literal(compatible)}, ${literal(viewer)});`)
  checks.push(await assertion({ gameId: 'valorant', platform: 'playstation' }, [], 'candidate blocking viewer'))
  checks.push(`DELETE FROM fallback_user_blocks; INSERT INTO fallback_user_blocks VALUES (${literal(viewer)}, ${literal(compatible)});`)
  checks.push(await assertion({ gameId: 'valorant', platform: 'playstation' }, [], 'viewer blocking candidate'))
  checks.push(`DELETE FROM fallback_user_blocks;
    INSERT INTO fallback_profile_moderation(user_id,is_suspended) VALUES (${literal(compatible)}, TRUE);`)
  checks.push(await assertion({ gameId: 'valorant', platform: 'playstation' }, [], 'indefinite suspension'))
  checks.push(`UPDATE fallback_profile_moderation SET suspended_until=NOW()-INTERVAL '1 day';`)
  checks.push(await assertion({ gameId: 'valorant', platform: 'playstation' }, [compatible], 'expired suspension'))
  checks.push(`INSERT INTO fallback_gaming_matches(user_a_id,user_b_id) VALUES (${literal(viewer)}, ${literal(compatible)});`)
  checks.push(await assertion({ gameIds: ['valorant'], platforms: ['playstation'], excludeRecentMatches: true }, [], 'recent match exclusion'))
  checks.push(`UPDATE fallback_gaming_profiles SET discoverable=FALSE WHERE user_id=${literal(compatible)};`)
  checks.push(await assertion({ gameId: 'valorant', platform: 'playstation' }, [], 'hidden profile'))
  checks.push(`UPDATE fallback_gaming_profiles SET discoverable=TRUE,adult_confirmed=FALSE WHERE user_id=${literal(compatible)};`)
  checks.push(await assertion({ gameId: 'valorant', platform: 'playstation' }, [], 'adult safeguard'))
  // A compatible player must not disappear behind 250 incompatible profiles.
  checks.push(`UPDATE fallback_gaming_profiles SET adult_confirmed=TRUE, updated_at=NOW()-INTERVAL '1 day' WHERE user_id=${literal(compatible)};
    INSERT INTO fallback_users(id) SELECT md5('fixture-' || n)::uuid FROM generate_series(1,260) n;
    INSERT INTO fallback_gaming_profiles(user_id) SELECT md5('fixture-' || n)::uuid FROM generate_series(1,260) n;`)
  checks.push(await assertion({ gameIds: ['valorant'], platforms: ['playstation'] }, [compatible], 'session constraints apply before candidate limit'))
  return [setup, ...checks, 'ROLLBACK;'].join('\n')
}

if (require.main === module) {
  buildRegressionSql().then((sql) => process.stdout.write(sql)).catch(() => {
    console.error('Could not build PlayMatch SQL regression.')
    process.exitCode = 1
  })
}

module.exports = { buildRegressionSql }
