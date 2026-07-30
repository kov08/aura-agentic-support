<!--
  ShopFast shipping policy — DATA, not prompt text.

  ADR-007a endgame: domain facts live in versioned data files, never inside a prompt string. A
  prompt that hardcodes "3–5 business days" turns a carrier renegotiation into a code change, and
  puts the fact somewhere retrieval cannot see it. Here the policy is a document that ops edits
  directly and the agent reads at runtime.

  Day 15's ingestion pipeline reads THIS directory (kb/) as its source corpus: every *.md file is
  chunked by DocumentChunker, embedded once with the document model, and written to pgvector. The
  heading hierarchy is load-bearing — headings become the chunk breadcrumbs that get embedded
  alongside the text — so keep it meaningful rather than decorative.
-->

# Shipping Policy

This document states what ShopFast promises about getting an order to a customer: when it leaves our
warehouse, how long it takes, what it costs, and what happens when it goes wrong. Delivery estimates
are estimates. The commitments in this document are the processing times and the shipping charges;
transit time depends on the carrier and on conditions we do not control.

## Order Processing

Orders placed before **2:00 PM local warehouse time** on a business day are picked, packed, and
handed to the carrier the same day. Orders placed after the cut-off, at weekends, or on a public
holiday ship on the next business day.

Some orders are held before shipping. The usual reasons are a failed address validation, a payment
authorisation that has not settled, or a fraud review triggered by a mismatch between the billing and
shipping addresses. A held order does not accrue processing time until the hold clears, so a customer
whose order is "still processing" after 48 hours almost always has a hold on it — check the order
record before quoting a delivery date.

### Pre-orders and Backorders

Pre-ordered items ship on the product's published release date, not when the order was placed. If a
cart mixes in-stock and pre-ordered items, we split the shipment and send the in-stock portion
immediately at no extra charge.

Backordered items show an estimated restock date on the product page. We charge the card only when
the item actually ships, so a backorder that never restocks is never billed.

## Delivery Speeds and Costs

| Service | Transit time | Cost |
| --- | --- | --- |
| Standard | 3–5 business days | Free over 35 USD, otherwise 4.99 USD |
| Express | 2 business days | 12.99 USD |
| Overnight | Next business day | 24.99 USD |
| International Economy | 7–21 business days | Calculated at checkout |

Transit time is counted in business days from the day the carrier scans the parcel, not from the day
the order was placed. Overnight orders must be placed before the 2:00 PM cut-off to ship the same
day; an Overnight order placed at 4:00 PM arrives in two calendar days, which is the single most
common source of "my overnight order was late" tickets.

### Free Shipping Threshold

The 35 USD threshold is measured on the merchandise subtotal after discounts and before tax. If a
partial refund brings an order below the threshold after the fact, we do not retroactively charge
shipping.

## Tracking

A tracking number is emailed when the carrier accepts the parcel and appears in the account area
under **Orders → Track**. Tracking can take up to 24 hours to show its first scan; a number that
returns "not found" during that window is normal and not evidence of a problem.

Tracking that stalls for more than 5 business days with no new scan is treated as a potential lost
parcel, and an agent should open a carrier trace without waiting for the customer to ask twice.

## Delivery Problems

### Lost Parcels

A domestic parcel with no movement for 7 business days, or an international parcel with no movement
for 21 calendar days, is declared lost. We then reship the order at no charge, or refund it in full,
at the customer's choice. We do not require the customer to wait for the carrier's own investigation
to conclude before we make them whole — the carrier claim is our problem, not theirs.

### Damaged on Arrival

Damage must be reported within 7 days of delivery, with photographs of both the item and the outer
packaging. The outer packaging photograph matters: it is what the carrier claim is built on, and a
claim without it is usually denied. Once a damage report is filed we ship a replacement immediately
and do not require the damaged item to be returned first.

### Wrong Item Received

If the wrong item arrives, we send the correct item by Express at no charge and issue a prepaid
return label for the incorrect one. There is no restocking deduction and no requirement that the
correct item wait for the wrong one to come back.

### Delivered but Not Received

Where tracking shows delivered and the customer does not have the parcel, the first step is to check
with neighbours, building management, and any parcel locker at the address, and to wait 24 hours —
carriers not infrequently scan a parcel as delivered a day before it physically arrives. If it has
not appeared after that, we file a carrier investigation and, in parallel, reship or refund. The
customer is not held hostage to the investigation timeline.

## Address Changes and Redirects

An address can be changed free of charge at any point before the parcel is handed to the carrier.
After that, we can request a carrier redirect, which succeeds perhaps half the time and costs 5.00
USD when it does. A redirect cannot cross an international border, and cannot change the destination
country under any circumstances.

Parcels returned to us as undeliverable — a bad address, a refused delivery, an unclaimed parcel at a
collection point — are refunded minus the original shipping charge once they arrive back at the
warehouse.

## Restricted Destinations

We do not ship to destinations under trade sanction, to military post addresses outside our carrier's
coverage, or to jurisdictions where a specific product category is prohibited. Lithium-battery
products in particular are restricted on many international lanes and cannot travel by air to some
destinations at any price. Where an order is blocked for a restriction, it is cancelled and refunded
in full within one business day, and the customer is told which specific item caused the block.
