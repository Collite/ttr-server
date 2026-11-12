SELECT "run", "as_of", "line_id", "route", "verdict_reason", "parameters_fp", "run_as", "written_at"
FROM (
  SELECT *
  FROM "monitor_inbound_verdict"
  WHERE "route" = 'remind'
) AS "_ttrp_part"
