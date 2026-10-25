# Caller-key seed (values `keySeed`, templates/key-seed.yaml). Env: TEAMS (space-separated team ids), REVOKE_STALE,
# PG* (the gateway's database); /keys/<team> holds each team's ttrk- key from the vault. Prints hashes' first 12
# hex digits only, never a key.
set -euo pipefail

for team in $TEAMS; do
  case "$(cat "/keys/$team")" in ttrk-*) ;; *) echo "✖ the vault key for team $team does not hold a ttrk- key"; exit 1 ;; esac
done

# The gateway's first boot runs Flyway (virtual_keys) and upserts the governance teams (virtual_keys.team_id → teams).
want=$(printf "'%s'," $TEAMS); want="${want%,}"
n=$(echo $TEAMS | wc -w)
echo "waiting for virtual_keys and the teams: $TEAMS"
for _ in $(seq 1 120); do
  if psql -X -tAc "SELECT to_regclass('public.virtual_keys') IS NOT NULL AND (SELECT count(*) FROM teams WHERE id IN ($want)) = $n" 2>/dev/null | grep -qx t; then
    ready=1; break
  fi
  sleep 15
done
[ "${ready:-0}" = 1 ] || { echo "✖ no virtual_keys, or a team missing from governance (want: $TEAMS) — is the gateway up?"; exit 1; }

for team in $TEAMS; do
  # Lowercase hex over the exact bytes, no trailing newline: what PgKeyValidator computes.
  hash="$(printf '%s' "$(cat "/keys/$team")" | sha256sum | cut -d' ' -f1)"
  # Matched by the hash (UNIQUE): an existing row of this team, however it got there, is taken over and re-enabled.
  # The id is per hash, so a rotated key gets a row of its own.
  psql -X -v ON_ERROR_STOP=1 -v id="vk_${team}_${hash:0:12}" -v team="$team" -v hash="$hash" <<'SQL'
INSERT INTO virtual_keys (id, team_id, name, key_hash, seeded)
VALUES (:'id', :'team', :'team', :'hash', true)
ON CONFLICT (key_hash) DO UPDATE SET seeded = true, revoked_at = NULL
  WHERE virtual_keys.team_id = EXCLUDED.team_id;
SQL
  owner=$(psql -X -tA -v hash="$hash" <<'SQL'
SELECT team_id FROM virtual_keys WHERE key_hash = :'hash';
SQL
)
  [ "$owner" = "$team" ] || { echo "✖ team $team's key is registered to team '$owner' — refusing to move it"; exit 1; }
  if [ "$REVOKE_STALE" = "true" ]; then
    psql -X -v ON_ERROR_STOP=1 -v team="$team" -v hash="$hash" <<'SQL'
UPDATE virtual_keys SET revoked_at = now()
WHERE team_id = :'team' AND seeded AND key_hash <> :'hash' AND revoked_at IS NULL;
SQL
  fi
done

psql -X -c "SELECT id, team_id, left(key_hash, 12) || '…' AS hash, seeded, revoked_at IS NULL AS active
            FROM virtual_keys WHERE team_id IN ($want) ORDER BY team_id, active DESC, id"
echo "✓ caller keys registered: $TEAMS"
