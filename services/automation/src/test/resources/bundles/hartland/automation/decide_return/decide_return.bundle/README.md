# decide_return

## What this recipe decides

- `reject_not_ours`: not bought here
- `reject_late`: delivered more than {return_window} days ago
- `reject_abuse`: return share {share} above the limit
- `to_customer_care`: request above the care threshold
- `refund_no_return`: small value, {reason}
- `replacement_no_return`: {reason}, replacement on stock
- `exchange`: requested variant on stock
- `refund_after_return`: goods back, then the money

## What it reads

- Customer (`er.entity.customer`)
- Inventory Snapshots (`er.entity.inventory`)
- Order (`er.entity.order`)
- Return Request (`er.entity.return_request`)
- Return Request Line (`er.entity.return_request_line`)
- Order Line (`er.entity.sales_line`)

## What it does

- `way.reject_not_ours`, `way.reject_late`, `way.reject_abuse` → `set_state`: `line` = `line_id`, `to` = `rejected`
- `way.reject_not_ours`, `way.reject_late`, `way.reject_abuse` → `notify_customer`: `customer` = `customer`, `template` = `"return-rejected"`
- `way.to_customer_care` → `decide_way`: `line` = `line_id`, `request` = `request_number`, `to` = `settling`, `way` = `"care"`
- `way.refund_no_return` → `set_state`: `line` = `line_id`, `to` = `settling`, `way` = `refund_no_return`
- `way.refund_no_return` → `expect`: `due` = `working_days(:settlement)`, `kind` = `credit_note`, `line` = `line_id`
- `way.replacement_no_return` → `set_state`: `line` = `line_id`, `to` = `settling`, `way` = `replacement_no_return`
- `way.replacement_no_return` → `expect`: `due` = `working_days(:settlement)`, `kind` = `replacement`, `line` = `line_id`
- `way.exchange` → `set_state`: `line` = `line_id`, `to` = `awaiting_goods`, `way` = `exchange`
- `way.refund_after_return` → `set_state`: `line` = `line_id`, `to` = `awaiting_goods`, `way` = `refund_after_return`
- `way.exchange`, `way.refund_after_return` → `expect`: `due` = `days(:inbound_reminder)`, `kind` = `inbound`, `line` = `line_id`
- `way.exchange`, `way.refund_after_return` → `notify_customer`: `customer` = `customer`, `template` = `"return-instructions"`

## Parameters

- `return_window` = `30` (days)
- `abuse_share` = `0.40` (decimal)
- `small_value` = `15` (decimal)
- `care_threshold` = `500` (decimal)
- `settlement` = `5` (working_days)
- `inbound_reminder` = `14` (days)

## Mode

`per-candidate`
