<!--
  ShopFast warranty policy — DATA, not prompt text.

  ADR-007a endgame: domain facts live in versioned data files, never inside a prompt string. A
  warranty term embedded in a system prompt is a legal commitment hidden inside source code, edited
  by whoever last touched the prompt. Here it is a document with an owner and a git history, read by
  the agent at runtime.

  Day 15's ingestion pipeline reads THIS directory (kb/) as its source corpus: every *.md file is
  chunked by DocumentChunker, embedded once with the document model, and written to pgvector. The
  heading hierarchy is load-bearing — headings become the chunk breadcrumbs that get embedded
  alongside the text — so keep it meaningful rather than decorative.
-->

# Warranty Policy

A warranty is a promise about how a product will perform over time. It is distinct from the refund
policy, which is a promise about buyer's remorse and about the condition of an item on arrival. A
customer inside the 30-day refund window who simply does not want the item is a refund case; a
customer whose product stopped working in month seven is a warranty case. Support agents should
identify which of the two they are handling before quoting any timeline, because the remedies differ:
refunds return money, warranties repair or replace.

## Coverage Period

Every product sold by ShopFast carries a **12-month limited warranty** from the delivery date against
defects in materials and workmanship. Some categories carry longer terms:

- Small appliances: 24 months
- Power tools: 36 months
- Furniture frames: 60 months (upholstery and cushions remain at 12 months)
- Batteries and consumable parts: 6 months
- Refurbished and open-box items: 90 days

Where a manufacturer offers a longer warranty than ShopFast's, the manufacturer's term applies and
the customer may claim through either party. Where the manufacturer's term is shorter, ShopFast's
term still applies — we do not pass a manufacturer's shorter term through to the customer.

## What Is Covered

The warranty covers a product that fails to work as designed under normal domestic use. In practice
that means manufacturing faults, component failures, and premature wear that is clearly out of line
with the product's expected life.

### Covered Failures

- A component that fails without external cause — a motor that seizes, a switch that stops making
  contact, a seam that opens along a stitch line
- Finishes that peel, bubble, or delaminate under normal use
- Rechargeable batteries that drop below 60% of rated capacity inside the warranty term
- Software or firmware defects that render a device unusable and that the manufacturer does not fix
  within 60 days of a reported issue

### Not Covered

- Accidental damage: drops, spills, crushing, and impact
- Cosmetic wear that does not affect function — scratches, scuffs, fading
- Damage from misuse, from use outside the product's stated environment, or from commercial use of a
  product sold for domestic use
- Damage from unauthorised repair or modification, including third-party parts
- Consumables that wear out by design: filters, blades, brush heads, printer cartridges
- Loss and theft, which are not warranty events at all

Water damage sits on the line and is judged case by case. A device rated for water resistance that
fails on first contact with water is a covered defect; the same device failing after being submerged
beyond its rating is not.

## Making a Claim

Claims start under **Orders → Report a problem**. The customer selects the order and describes the
fault. We ask for photographs or a short video showing the failure, and for the serial number where
the product has one.

A ShopFast order record is proof of purchase. We do not require a paper receipt, a registration card,
or a completed product-registration form — a warranty is never voided for failing to register a
product.

### Assessment

Most claims are assessed from the customer's photographs and description within **2 business days**.
Where the fault cannot be judged remotely, we issue a prepaid return label and assess the item at our
service centre, which takes a further 5 to 7 business days after arrival.

If assessment finds the failure is not covered, we tell the customer why, quote a paid repair where
one is possible, and return the item at no charge if they decline. We never keep or dispose of a
customer's item because a claim was denied.

## Remedies

For an approved claim inside the first 90 days, the default remedy is **replacement**: we ship an
identical replacement immediately and issue a prepaid label for the faulty unit.

After 90 days, the default is **repair**. Where repair is uneconomic, where the part is discontinued,
or where the same unit has already been repaired twice for the same fault, we replace instead. Where
no equivalent replacement exists, we refund the original purchase price — not the current market
price, and not a depreciated value.

Repairs are covered for 90 days from the repair date or for the remainder of the original warranty
term, whichever is longer. A replacement unit inherits the remainder of the original warranty term;
it does not restart the clock.

## Turnaround and Loaners

Target turnaround for a repair is 10 business days from the item's arrival at the service centre.
Where a repair is projected to exceed 21 days, the customer may choose a replacement or a refund
instead of waiting.

For power tools and small appliances only, a loaner unit is available on request while a repair is in
progress, subject to stock. Loaners are shipped and returned at ShopFast's cost.

## Transfer and Statutory Rights

The warranty follows the product, not the purchaser, so a gift recipient or a second-hand buyer may
claim on the remainder of the term with the original order number.

This warranty is offered in addition to, and never in place of, the statutory rights a customer has
under their local consumer-protection law. Where local law grants a longer period or a stronger
remedy than this document, local law wins. Agents must never tell a customer that a ShopFast warranty
term overrides a statutory right.
