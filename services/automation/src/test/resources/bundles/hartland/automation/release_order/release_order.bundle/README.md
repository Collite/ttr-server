# release_order

## What this recipe decides

- `hold_sales`: stock shortfall or price difference (details: `shortfall`, `price_differences`)
- `hold_ar`: overdue invoices (details: `overdue`)
- `release_confirmed`: all checks passed; above the confirmation threshold
- `release`: all checks passed

## What it reads

- Customer Price (`er.entity.customer_price`)
- Inventory Snapshots (`er.entity.inventory`)
- Invoice (`er.entity.invoice`)
- Order (`er.entity.order`)
- Price List (`er.entity.price_list`)
- Order Line (`er.entity.sales_line`)
- Warehouse Outage (`er.entity.warehouse_outage`)

## What it does

- `verdict.release` → `release`: `order` = `order_number`. would release order {order}
- `verdict.release_confirmed` → `release_confirmed`: `order` = `order_number`, `value` = `value`. would ask {by} to confirm the release of {order} ({value})
- `verdict.hold_sales` → `hold`: `department` = `"Sales"`, `order` = `order_number`. would hold order {order} for {department}
- `verdict.hold_sales` → `notify`: `detail` = `shortfall, price_differences`, `who` = `role.sales`. would notify {who} with {detail.count} rows
- `verdict.hold_ar` → `hold`: `department` = `"AR"`, `order` = `order_number`. would hold order {order} for {department}
- `verdict.hold_ar` → `notify`: `detail` = `overdue`, `who` = `role.ar`. would notify {who} with {detail.count} rows

## Parameters

- Price tolerance (`tolerance`) = `0.01` (decimal)
- Confirmation threshold (`confirmation_threshold`) = `50000` (decimal)

## Mode

`per-candidate`
