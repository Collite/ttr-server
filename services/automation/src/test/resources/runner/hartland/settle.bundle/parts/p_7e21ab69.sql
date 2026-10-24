SELECT "_ttrp_d_n20_0", "_ttrp_w_n20_0"
FROM (
  SELECT *
  FROM (SELECT *
          FROM (SELECT "cal_date" AS "_ttrp_d_n20_0", "working_day" AS "_ttrp_w_n20_0"
                  FROM "date_dim") AS "t"
          WHERE "_ttrp_w_n20_0") AS "t0"
  WHERE "_ttrp_d_n20_0" <= :as_of
) AS "_ttrp_part"
