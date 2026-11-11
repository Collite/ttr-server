SELECT "id", "recipe", "run", "entity", "key", "kind", "due", "state", "fulfilled_by", "fulfilled_at", "created_at"
FROM (
  SELECT *
  FROM "obligation"
) AS "_ttrp_part"
