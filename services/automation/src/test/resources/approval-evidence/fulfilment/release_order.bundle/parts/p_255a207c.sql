SELECT "id", "recipe", "run", "entity", "key", "kind", "due", "state", "fulfilled_by", "fulfilled_at", "created_at"
FROM (
  SELECT *
  FROM (SELECT *
          FROM (SELECT *
                  FROM (SELECT *
                          FROM "obligation"
                          WHERE "entity" = 'er.entity.return_request_line') AS "t"
                  WHERE "key" = '{"line_id":9007199254740993}') AS "t0"
          WHERE "kind" = 'inbound') AS "t1"
  WHERE "state" IN ('open', 'overdue', 'reopened')
) AS "_ttrp_part"
