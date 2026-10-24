SELECT "id", "recipe", "run", "entity", "key", "kind", "due", "state", "fulfilled_by", "fulfilled_at", "created_at"
FROM (
  SELECT *
  FROM (SELECT *
          FROM (SELECT *
                  FROM "obligation"
                  WHERE "entity" = 'return_request_line') AS "t"
          WHERE "kind" = 'inbound') AS "t0"
  WHERE "state" IN ('open', 'overdue', 'reopened')
) AS "_ttrp_part"
