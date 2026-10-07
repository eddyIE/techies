# Spec: ai

Module id `ai` · port 8085 · no schema · depends on: catalog, inventory
Covers no row in the sheet — the product assistant is an addition beyond the brief, so its
scope is set here rather than traced back to a requirement.

## Objective

Answer a customer's questions about the product whose page they are on, in Vietnamese, from
the store's own data, and stream the answer so a phone popup fills in as the model writes.
When the question is about *other* products, run the real catalogue search and let the app
render real cards.

This is the only module that talks to an LLM, and the only one whose output is not
deterministic. Two rules follow from that and shape everything below:

1. **The model never supplies a fact the catalogue owns.** Prices, names and stock reach the
   customer as structured events built from catalog-service and inventory-service responses,
   not as text the model wrote. A model cannot quote a price that disagrees with checkout if
   it is never the thing quoting prices.
2. **The client never supplies a product fact either.** A request carries a `productId` and
   nothing else about the product. The service fetches the detail itself, so a tampered client
   cannot put an invented price or warranty into the prompt and have the assistant repeat it.

## Data Model

None. The service has no schema and `DataSourceAutoConfiguration` is excluded, so it starts
without a database at all.

Conversation state lives in the Android app, which resends the history on every turn. The
service trims it to `techies.gemini.max-history-messages` (10) and forgets it. Closing the
popup discards the conversation, and there is no server-side record that a chat happened.
That is a deliberate trade — no transcripts to protect, and no analytics either.

Validation bounds the resent history before it reaches a prompt — at most 20 messages, each
at most 2000 characters. `@Valid` on the nested `messages` list is what makes the per-message
`@Size` cascade, and without it a client can push an arbitrarily long string into the prompt.

## Endpoints

| Method | Path | Body | Success | Failure |
|---|---|---|---|---|
| POST | `/ai/chat` | `{productId, messages:[{role, content}]}` | 200 `text/event-stream` | 400 validation, 404 `PRODUCT_NOT_FOUND`, 503 `SERVICE_UNAVAILABLE` |
| POST | `/ai/review-summary` | `{productName, reviews:[{rating, comment}]}` | 200 `{pros, cons, verdict}` | 400 validation, 503 `SERVICE_UNAVAILABLE` |

`/ai/chat` requires a JWT. `/ai/review-summary` is internal, reachable only on the compose
network: `order-service` is its sole caller and no customer should be able to spend the day's
quota by reloading a product page. The gateway routes neither to it.

One turn has a wall-clock ceiling of `techies.gemini.timeout-seconds` (60),
and the gateway routes `/api/ai/**` with its own 90s response timeout so the stream is cut by
the service rather than by the gateway. The gateway's global 10s ceiling would cut a streamed
reply mid-sentence, which is why that route overrides it.

A missing `GEMINI_API_KEY` disables the assistant rather than failing startup — the stream
opens and carries a single `error` event, so the rest of the service runs without a key.

## The SSE event contract

| Event | Payload | Meaning |
|---|---|---|
| `token` | `{text}` | a fragment of the reply, appended in order |
| `tool_start` | `{tool, message}` | the model paused to search — the app shows one spinner |
| `products` | `{total, query, products[]}` | real product cards, plus the query to deep-link |
| `done` | `{finishReason}` | the turn finished |
| `error` | `{code, message}` | a failure after the stream opened |

Event names are the contract with the app. The guarantees it may rely on:

1. **`tool_start` arrives at most once per turn**, however many searches the turn runs and
   across however many rounds. The app shows a single indicator and keys nothing on `tool`,
   so one spinner per turn is the useful shape — one per search would only flicker.
2. **`products` arrives at most once per turn, and never before the first `token`.** Finding
   the products costs a whole Gemini call that completes before the reply begins, so cards
   emitted when they were fetched landed in an empty bubble — the app looked like it had
   answered with products and no words. They are held until text starts arriving, giving
   `tool_start` → `token` → `products` → more `token`s.
3. **`done` never follows zero `token` events.** A turn that produces no text at all sends a
   short Vietnamese fallback first. The app appends tokens as they arrive and has no way to
   distinguish "finished with nothing to say" from "still thinking", so a bare `done` left it
   waiting for text that was never coming.
4. **Exactly one terminal event** — `done` or `error` — and nothing after it.
5. **`error` is only for failures after the stream opened.** Once the response has begun the
   HTTP status is locked at 200, so a mid-stream failure cannot be a status code and must not
   be an exception either. Failures *before* streaming still throw and produce the project's
   normal error envelope with a real status.

## The turn

One turn is a loop, not a fixed pair of calls.

```
  stream a request
    ├── text only ............................ answer the customer, done
    ├── one or more function_call steps ...... run them all, hand every result back, repeat
    └── error ................................ error event, stop
```

Each pass through the loop is one Gemini request. A plain question costs one, a question that
searches costs two, and a comparison ("so sánh X với Y") costs three or more — the model asks
for two catalogue lookups, and sometimes asks for the second only after reading the first
result.

**Every requested call must be answered, in one continuation.** The interaction stays at
`status: requires_action` until each call it announced has a matching `function_result`, and a
model still waiting on a result never writes a word. Answering only the first call, or
dropping a call the continuation asked for, ends the turn with cards and zero text. Both were
real bugs, and both looked like a timeout from the app — the turn completed in about 15
seconds with nothing to say.

**Concurrent calls are separated by the event index.** A stream can announce several
`function_call` steps at once, and the arguments of each arrive as `arguments_delta` fragments.
Every event carries a top-level `index` and that index is the only thing that tells the calls
apart. Accumulating the fragments into one buffer produces malformed JSON like
`{"keyword":"Neo QLED"}{"keyword":"OLED"}`, of which Jackson parses the first object and
silently ignores the rest, so one of the two searches disappears. Pending calls are therefore
keyed on the index, each with its own id, name and argument buffer.

**The loop is capped at 3 tool rounds.** The request carrying the third round's results
declares no tools, so the model has nothing left to call and has to answer with what it has.
If it asks anyway, the request is ignored and the turn finishes. Without the cap a confused
model can bounce tool calls back and forth until the 60s SSE ceiling — which, unlike the hang
above, really is a timeout — and each round spends another request from a daily quota of
twenty. Four streams per turn is the hard ceiling.

## `search_products`

Ours to execute. The model states the criteria, this service runs the real catalogue search,
and the cards are emitted as their own `products` event rather than written by the model.

`category` is declared as an enum of the real category names, because keyword search covers a
product's name and description and never its category — "tai nghe" appears in no product name,
so without the enum "find me headphones" returns nothing. The model returns sort values like
`price_asc` where the catalogue enum is `PRICE_ASC`, so sort is normalised and an unrecognised
value falls back to `NEWEST` rather than 400ing the search.

### Merging several searches into one `products` event

A turn emits one `products` event, so the searches of a turn are merged:

1. **Interleaved**, one card from each search in turn, so a comparison keeps a card from each
   side of it instead of filling the popup with the first search's matches.
2. **Deduped by product id.** The two halves of a comparison frequently overlap.
3. **Capped** at `techies.gemini.max-products`.

`total` is then the number of cards actually sent, never the catalogue's match count. The
search routinely matches more than the popup shows, and a number above the cards on screen
reads as missing products whether the reply says it or a "see all 23" button does. `query` is
echoed instead so the app can open the product list screen, which pages properly and states
its own total honestly. One event carries one query — so a merged event echoes the first
search's.

What the model is told is derived from the same merged set — each call's `function_result`
describes only the cards of that search which survived the merge, plus a `shown` count. Told a
wider total the model announced "có 8 mẫu" above five cards, which reads as four missing
products rather than a capped display.

### The stock join

`catalog-service` does not carry stock, so each merged card's availability is read from
`inventory-service` and joined into the tool result as `còn hàng` / `hết hàng` / `không rõ`.

Without it the assistant recommends sold-out products, and `PRICE_ASC` sorts the most
likely-sold-out item straight to the top. If inventory is unreachable the label is `không rõ`
rather than a guess — claiming stock we cannot verify is the failure this exists to prevent.

The join feeds the **model only**. The cards are unchanged, so the app's contract is untouched
and the number of extra calls is bounded by `max-products`.

## `google_search`

Declared as `{"type": "google_search"}` and executed by Google inside the same turn. Unlike
`search_products` it never comes back as a `function_call`, so it costs no extra round trip
and needs no handling in the event loop.

It exists because the seeded product descriptions are one line long — and customers ask about
battery capacity, RAM and screen size constantly. The alternative to grounding is refusing
every such question.

`techies.gemini.web-search: false` turns it off with a restart and no rebuild. It is the one
tool declaration a provider could reject outright — and a rejected declaration 400s the whole
chat rather than only grounding.

## Prompt rules: two kinds of fact

`SystemPromptBuilder` injects the real product block, then splits the world in two. This split
is the module's main prompt-level design.

| | Source | Rule |
|---|---|---|
| **Store facts** — price, stock, warranty, promotions, returns, delivery | the injected block only | looking these up is forbidden |
| **Manufacturer facts** — battery, RAM, screen, chip, camera, weight | the block, else Google Search | must be labelled a manufacturer reference, not a store promise |

A web page claiming 24 months' warranty on something sold with 12 contradicts checkout, and a
grounded answer that contradicts our own checkout is worse than no answer. Specifications, by
contrast, are identical wherever the product is sold.

With `web-search` off the rule inverts rather than relaxing — the model is told it has no
lookup tool and may not dress a remembered number as a manufacturer specification.

The prompt also pins the pronouns ("em" to "anh/chị"), caps replies at 2-3 sentences for a
phone popup, forbids re-listing search results the app already shows as cards, and forbids
inventing features for a searched product whose name and price are all it knows.

The store the customer is told about is **ElecGo**, the name of the Android app. Techies is the
backend, and naming it to a customer names a system they have never heard of.

## Review summaries

`POST /ai/review-summary` turns a product's reviews into a short brief: a few `pros`, a few
`cons`, and a one or two sentence `verdict`, all in Vietnamese. `order-service` owns the reviews
and the cache (SPEC-order.md, AI review summary); this service only writes the text.

**The reviews arrive in the request body.** This service does not fetch them, which keeps it a
read-only leaf that calls nobody but `catalog` and `inventory`, and keeps it free of a schema.
It also means the summary is a pure function of its input, so the same reviews always produce
comparable output and the endpoint is trivially testable without a database.

**Not streamed.** The chat endpoint streams because a customer is watching a bubble fill. A
summary is generated once, cached by the caller and then read many times, so there is nothing
to watch and SSE would only complicate both ends. One request, one JSON response.

**No tools and no web search.** The whole point is a summary of *these* reviews. A model free to
search would import opinions from the internet and present them as what ElecGo's own customers
said, which is worse than no summary at all. The prompt is told to use nothing but the supplied
text and to omit a point rather than infer one.

**Structured, not prose.** `pros` and `cons` come back as short phrases for the app to render as
chips. Prose would be re-paraphrasing reviews the customer can already scroll, and it would make
the section unskimmable at phone width. The same reasoning as products being their own SSE event
rather than model-formatted text.

**Minority opinions survive.** The prompt requires a `con` to be carried whenever several
reviewers raise it, even against an otherwise positive average. A summary that reads as uniformly
glowing is the failure mode here: it looks fabricated, which is exactly the impression the
deliberately uneven seeded ratings in `V7__product_reviews.sql` exist to avoid.

## Quota

Gemini's free tier allows **5 requests per minute and 20 per day** — and the per-day limit is
the one that bites. A searching turn costs two requests and a comparison three or more, so the
free tier is roughly ten searching turns a day. Exceeding it produces an error the app maps to
`RATE_LIMITED` and shows as "Trợ lý đang bận, vui lòng thử lại sau một phút".

Nothing else on the product page depends on the assistant, so the chat button is expected to
degrade rather than the page failing with it. A review summary costs one request, but only when
a product's review count has changed since the last one, so it is charged per new review rather
than per page view. It degrades the same way: `order-service` serves the previous summary, or
nothing, rather than failing the page.

The Postman AI folder is skipped in a collection run unless `RUN_AI` is `true`, so a Newman
run does not spend the day's quota. Casual verification against the live API is not worth a
request.

## Rules

- Product facts come from `productId` alone. The client supplies no product data, ever.
- Nothing is persisted. `store: true` is set on the Gemini request because
  `previous_interaction_id` chaining requires it, which means **Google** retains the
  interaction while we retain nothing. Disclosed in `docs/SECURITY-NOTES.md`.
- Unknown stream event types are ignored, not treated as failures. The API adds them over time
  and an unrecognised event is not a reason to fail a turn.
- The system instruction and the tool declarations are restated on every continuation.
  Chaining on `previous_interaction_id` alone was not enough: the reply written after a search
  is a fresh generation, and without the rules restated it re-listed every product, invented
  features for them and dropped the pinned pronouns — precisely the turn where those rules
  matter most.
- One virtual thread per in-flight chat — a turn spends nearly all its time blocked on Gemini,
  not on CPU.
- Catalogue or inventory failures degrade rather than fail the turn — no category list means
  keyword-only search, no stock means `không rõ`. Only the product fetch is fatal, because
  without it there is nothing to talk about.

## Known limits

- **The two-kinds-of-fact split is a prompt rule, not an enforced one.** It constrains a
  cooperative model — nothing in the service inspects the reply text for a warranty claim.
- **`{"type": "google_search"}` has not been verified against the live API.** If the provider
  rejects the declaration the whole chat 400s — hence the `web-search` switch.
- **A merged `products` event echoes one query**, the first search's, because the DTO carries
  one. "See all" after a comparison therefore deep-links half of it.
- **Cards found after the `products` event has been sent are not resent.** In practice the
  model asks for its searches before writing anything, so this needs a model that searches,
  speaks, then searches again.
- **The event loop is written against Gemini's vocabulary** (`event_type`, `step.delta`,
  `arguments_delta`, `previous_interaction_id`). Rotating to another free provider when a quota
  runs out needs an adapter normalising into a neutral stream of text and tool calls, not just
  a different API key. Tool calling is the gating feature — a provider without function
  calling cannot serve a searching turn at all. Failing over must also be quota-aware and must happen
  before the first token, since a stream cannot be restarted once the app has text.
- **Vietnamese only.** The prompt is Vietnamese and replies must be — a real constraint on
  which models can be adopted.

## Acceptance Criteria

- [ ] A plain question streams `token`s then `done`, with no `tool_start` and no `products`.
- [ ] A question about other products gives `tool_start` → `token` → `products` → `token`s →
      `done`, in that order.
- [ ] `products.total` equals `products.products.length` in every event.
- [ ] A comparison asking for two parallel searches runs **both**, with the arguments of each
      kept apart, and sends both results in one continuation.
- [ ] A comparison where the model asks for its second search only after reading the first
      still produces reply text.
- [ ] Merged cards are deduped by product id and never exceed `max-products`.
- [ ] A model that keeps asking for tools is cut off after 3 rounds and still reaches `done`.
- [ ] A turn that produces no model text sends a Vietnamese `token` before `done`.
- [ ] A sold-out product is not recommended by a search-driven reply.
- [ ] Asking the price of a *different* product never produces a figure the catalogue
      disagrees with.
- [ ] Asking for the warranty gets the store's own answer, never a looked-up one.
- [ ] Asking for battery capacity gets a figure labelled as the manufacturer's.
- [ ] With `GEMINI_API_KEY` unset the service starts and the stream carries one `error` event.
- [ ] With `web-search: false` the service runs and specification questions are refused rather
      than answered from memory.
- [ ] A rate-limited turn surfaces as `error` with code `RATE_LIMITED`.
- [ ] A 25-message or 2001-character request is rejected with 400 before any Gemini call.
- [ ] `POST /ai/review-summary` with five mixed reviews returns at least one `pro` and one `con`.
- [ ] A criticism raised by several reviewers appears in `cons` despite a high average rating.
- [ ] The summary names no product, feature or opinion absent from the supplied reviews.
- [ ] `pros` and `cons` are short phrases, not sentences restating a review verbatim.
- [ ] Direct `POST /api/ai/review-summary` through the gateway → 404.
