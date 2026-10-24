SELECT "_ttrp_d_n15_0"
FROM (
  SELECT *
  FROM (SELECT "cal_date" AS "_ttrp_d_n15_0"
          FROM "date_dim") AS "t"
  WHERE "_ttrp_d_n15_0" <= :as_of
) AS "_ttrp_part"
