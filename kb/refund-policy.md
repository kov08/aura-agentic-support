<!--
  ShopFast refund policy — DATA, not prompt text.

  ADR-007a endgame: domain facts live in versioned data files, never inside a prompt string. A
  prompt that hardcodes "30 days" turns a policy change into a code change — un-reviewable by the
  people who actually own the policy, and invisible to retrieval. Here the policy is a document:
  support/legal/ops edit it directly, git records who changed what and when, and the agent reads it
  at runtime instead of reciting it from a system prompt it was compiled with.

  Day 15's ingestion pipeline reads THIS directory (kb/) as its source corpus: every *.md file is
  chunked by DocumentChunker, embedded once with the document model, and written to pgvector. The
  heading hierarchy is load-bearing for that pipeline — headings become the chunk breadcrumbs that
  get embedded alongside the text — so keep it meaningful rather than decorative.
-->

# Refund Policy

This document is the authoritative statement of when a ShopFast customer can get their money back,
how much of it comes back, and how long it takes to arrive. Returns and refunds are related but
separate: a return is the physical movement of an item back to us, a refund is the movement of money
back to the customer. Most refunds require a return first, but some — a cancelled order, a duplicate
charge, a lost parcel — do not.

## Standard Refund Window

Customers have **30 days from the delivery date** to request a refund. The delivery date is the date
recorded by the carrier, not the date the order was placed and not the date the customer opened the
parcel. For deliveries to a parcel locker or a neighbour, the carrier's first delivery scan is what
counts.

Items purchased between 1 November and 24 December may be returned up to 31 January of the following
year. This holiday extension applies automatically; the customer does not need to ask for it.

### Eligible Items

An item is eligible for a full refund when it is returned unused, in its original packaging, with all
tags, manuals, cables, and free promotional items that shipped with it. Apparel must be unworn and
unwashed, electronics must have been factory reset, and any sealed hygiene wrapper must be intact.

### Items We Cannot Refund

The following are final sale, except where the item arrived damaged, defective, or was not the item
ordered:

- Perishable goods, including food and fresh flowers
- Personalised or custom-manufactured items, including engraved products
- Digital downloads once the file has been accessed
- Opened hygiene products such as earbuds, razors, and cosmetics
- Items marked "Final Sale" on the product page at the time of purchase

Gift cards are NOT on this list. They have their own, shorter window — see the next section.

### Gift Cards

**ShopFast gift cards are refundable within 7 days of purchase**, and the refund is available even
after the code has been revealed. The 7 days run from the purchase date, not the delivery date, and
this is the one product category where revealing the code does not end the refund right.

The card must be unspent. A partially redeemed card cannot be refunded at all — not for the unspent
balance, and not for the difference. Where a card was bought as a gift, only the purchaser can
request the refund, because the money goes back to the card that paid for it.

Gift cards are deliberately outside the standard 30-day window in both directions: shorter, because
an unspent code is trivially resold and a month is long enough for that to become a fraud channel,
and refundable-after-reveal, because a revealed code is not a used one and treating the two as the
same thing generated more disputes than it prevented.

## How to Start a Refund

Refunds start in the account area under **Orders → Request a refund**: the customer selects the order,
the items, and a reason, and we issue a prepaid return label immediately for domestic orders. Guests
can use the order-lookup form with the order number and the checkout email.

The item must be handed to the carrier within 14 days of the label being issued. Labels expire after
21 days and can be regenerated once, free, from the same screen.

An agent may approve a refund without a return — a "keep it" refund — where return shipping would cost
more than the item, or where damage makes return shipping unsafe. That is the agent's decision, not
the customer's, and it is recorded on the order.

## Refund Processing Times

Inspection at our warehouse takes up to **3 business days** after the returned item arrives. Once
inspection passes we release the refund the same day, and the money then moves at the speed of the
customer's payment provider: 5–10 business days for credit and debit cards, 1–3 for PayPal and
digital wallets, 3–7 for bank transfer, and immediately for ShopFast store credit.

Store credit is always the customer's explicit choice. We never convert a card refund to store credit
on their behalf.

## International Orders

International refunds follow the same 30-day window and the same eligibility rules as domestic ones,
but the mechanics differ enough to surprise customers routinely, so they are spelled out in full here.

Return shipping is the customer's responsibility unless the item arrived damaged, defective, or was
not the item ordered. We do not issue prepaid international labels automatically, because cross-border
label costs frequently exceed the value of the item. An agent reviews each request and chooses one of
three outcomes: a prepaid label where the item's value justifies it, a partial "keep it" refund where
return shipping would cost more than the item is worth, or a customer-paid return where we reimburse
documented shipping up to 20 USD once the item is received.

Customers must include the original commercial invoice and mark the parcel
**"RETURNED GOODS — NO COMMERCIAL VALUE"**. A parcel without that declaration is treated by customs as
a fresh commercial import and can be assessed duty a second time; that duty is billed to ShopFast as
importer of record and deducted from the refund. This is the most common reason an international
refund arrives smaller than expected, and declaring the parcel correctly avoids it entirely.

Duties and import taxes paid on the original outbound shipment are refunded only where the local
customs authority actually returns them to us. Most authorities do refund duty on proven re-export,
but it takes 30 to 90 days, and a few jurisdictions do not refund it at all. Where duty is
recoverable we refund it as a second, separate payment after the authority settles, so an
international customer often sees two credits rather than one. Where it is not recoverable, we say so
explicitly in the confirmation email rather than leaving the customer to work it out from the numbers.

Currency conversion is handled by the customer's card issuer, not by ShopFast. We refund the exact
amount originally charged, in the original transaction currency. Because the exchange rate on the
refund date differs from the rate on the purchase date, the amount landing in the customer's home
currency may be slightly higher or lower than what they paid. That gap is currency movement, not a
deduction, and ShopFast cannot adjust or top it up — agents should explain this plainly rather than
promising an exact-to-the-cent match.

Refunds are released only after the item clears inbound customs and reaches our warehouse for
inspection. Clearance typically adds 5 to 15 business days on top of transit, so an international
refund commonly takes 4 to 6 weeks end to end from the day the customer ships. Customers who need the
money sooner should be offered store credit, which we can issue as soon as the carrier's first inbound
scan confirms the item is on its way back — that is the one lever we have to shorten this timeline,
and it should be offered proactively.

Orders shipped to a freight forwarder are a separate case. Our delivery obligation completes at the
forwarder's address, so the 30-day window starts at that scan, we cannot take responsibility for
damage or loss after the forwarder takes possession, and refunds are limited to the item price with
all shipping charges excluded.

## Partial Refunds and Restocking

We may issue a partial refund where a returned item shows signs of use, is missing accessories or
packaging, or arrives outside the standard window under an agent-granted extension. The deduction is
proportional to the lost resale value and must be explained to the customer in writing before it is
applied.

ShopFast charges no restocking fee on standard consumer orders. Business and bulk orders of ten or
more units of the same SKU carry a 15% restocking fee, disclosed at checkout.

## Disputes and Chargebacks

If a customer opens a chargeback with their bank, the refund for that order is frozen until the bank
resolves the dispute — refunding an amount that is simultaneously being clawed back would pay the
customer twice. Agents should encourage customers to withdraw the chargeback and let the standard
refund complete, which is almost always faster than the bank's 90-day dispute cycle.
