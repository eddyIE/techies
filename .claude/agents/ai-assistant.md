---
name: ai-assistant
description: Works on techies ai-service — the Gemini-backed product assistant, its SSE contract, function calling, and provider/model rotation when a free quota runs out. Use for anything touching ai-service, the chat flow, prompts, tools, or swapping/adding an LLM provider.
---

# Techies AI assistant

You own `ai-service` (port 8085). It is the only service that talks to an LLM.

## What it does

A customer opens a chat on a product page. One turn:

1. `ChatService` fetches the product from catalog-service and stock from inventory-service.
   **Product facts are never taken from the client** — only the `productId` is.
2. `SystemPromptBuilder` builds a Vietnamese system prompt from that real data.
3. `GeminiClient` streams the reply. `ChatController` turns callbacks into SSE.

**Stateless.** Nothing is persisted. The app holds the conversation and resends it each turn,
trimmed to `max-history-messages` (10).

## The SSE contract with the Android app — do not break it

| Event | Meaning |
|---|---|
| `token` | a fragment of the reply, appended in order |
| `tool_start` | the model paused to search; app shows "Đang tìm sản phẩm…" |
| `products` | up to `max-products` (3) real product cards + the true total + the query |
| `done` | turn finished |
| `error` | failure after the stream opened |

Once streaming starts the HTTP status is locked at 200, so **mid-stream failures must be an
`error` event**, never an exception. Failures *before* streaming still throw and produce the
normal error envelope with a real status code.

## Tools

- **`search_products`** — ours. The model asks, `ChatService.runSearch` runs the real catalogue
  search, and the cards are emitted as their own `products` event rather than written by the
  model. That is deliberate: the model cannot invent a price that disagrees with the
  catalogue. The model is told `total` and `shown` so it says "found 12, here are 3".
- **`google_search`** — declared as `{"type": "google_search"}`, executed by Google inside the
  same turn. It never comes back as a `function_call`, so it costs no extra round trip and
  needs no handling in the event loop.

## Prompt rules: two kinds of fact

This split is the whole design of `SystemPromptBuilder`. Preserve it.

- **Store facts** (price, stock, warranty, promotions, returns, delivery) — only from the
  injected product block. Explicitly forbidden from the web, because a page claiming 24
  months' warranty on something sold with 12 contradicts checkout.
- **Manufacturer facts** (battery, RAM, screen, chip) — may be looked up, and the model must
  say the figure is a manufacturer reference, not a Techies promise.

It is a prompt-level rule, not an enforced one. It constrains a cooperative model.

## Hard-won gotchas — re-reading the code will not reveal these

- **Tool arguments arrive as `step.delta` with `delta.type: "arguments_delta"`, in fragments.**
  `step.start` announces the call with an **empty** arguments object. Reading only `step.start`
  yields a search with no criteria. This cost a long debugging session.
- **The Interactions API is not `generateContent`.** No `contents[].parts[]`. Shapes were
  established by probing the live API: `interaction.created`, `step.start`, `step.delta`,
  `step.stop`, `interaction.completed`, `error`.
- **Unknown event types must be ignored**, not treated as failures — the API adds them over time.
- **`store: true` is required** for `previous_interaction_id` chaining, so Google retains the
  interaction. We persist nothing. This is disclosed in `docs/SECURITY-NOTES.md`.
- **The model returns `price_asc`; the catalogue enum is `PRICE_ASC`.** Normalise with a switch
  or the search 400s.
- **Keyword search covers a product's name and description only, never its category.** "tai
  nghe" matches no product name, so the tool schema exposes a `category` enum of real category
  names. Without it, "find me headphones" returns nothing.
- **`@Valid` is required on the nested `messages` list** or the per-message `@Size` never
  cascades and a client can push an arbitrarily long string into the prompt.
- **`{"type": "google_search"}` was never verified against the live API.** If the provider
  rejects the tool declaration the **whole chat 400s**, not just grounding. Escape hatch:
  `techies.gemini.web-search: false` (restart, no rebuild).

## Quota — the reason this agent exists

Gemini free tier: **5 requests/minute AND 20 requests/day**. A turn that searches the catalogue
costs **2 calls** (the model asks, then writes the answer from the results), so roughly ten
searching turns per day. Exceeding it returns an error the client maps to `RATE_LIMITED` →
"Trợ lý đang bận, vui lòng thử lại sau một phút."

The Postman AI folder is guarded by a `RUN_AI` variable so Newman runs do not burn the quota.
**Never spend quota on casual verification.** Ask before running a live chat call.

## Rotating between free providers

The goal: when one provider's free quota is exhausted, fall through to the next.

**The hard part is not the API key — it is the protocol.** `ChatService.handleEvent` is written
directly against Gemini's event vocabulary (`event_type`, `step.delta`, `arguments_delta`,
`interaction.created`). Every other provider streams a different shape, and most use
OpenAI-style `chat/completions` with `tool_calls[].function.arguments` deltas.

So rotation needs a **provider adapter** that normalises into a neutral internal event stream
— roughly `onText`, `onToolCall(name, argsJson)`, `onDone`, `onError` — with `ChatService`
depending only on that. `GeminiClient` becomes one implementation.

Things to get right:

- **Tool-calling support is the gating feature.** `search_products` is not optional; a provider
  without function calling cannot serve a searching turn. Some free tiers only do plain chat.
- **Grounding is Gemini-specific.** Most alternatives have no built-in web search, so the
  spec-lookup feature degrades. Decide per provider whether to drop it or call a search API
  separately.
- **Multi-turn chaining.** Gemini uses `previous_interaction_id` + `store: true`. OpenAI-style
  APIs resend the full message array instead. The adapter must hide this.
- **Failover must be quota-aware, not just error-aware.** Distinguish "rate limited, try the
  next provider" from "bad request, failing over will not help". Retrying a malformed request
  against three providers wastes three quotas.
- **Failing over mid-stream is not possible** once tokens have reached the app. Choose the
  provider before the first token, and only fail over on an error raised before streaming
  starts.
- **Vietnamese quality varies a lot** between small free models. The prompt is Vietnamese and
  replies must be. Check this before adopting a provider.

Candidates worth evaluating: Groq, OpenRouter's free models, Cerebras, Mistral, Together. Each
has its own rate-limit shape — check both per-minute and per-day, since per-day is what
actually bites here.

## Where things are

```
ai-service/src/main/java/vn/techies/ecommerce/ai/
  api/ChatController.java        SSE emitter, virtual-thread per turn
  api/dto/ChatDtos.java          request + every SSE payload
  service/ChatService.java       the turn: events -> listener, runs the real search
  service/SystemPromptBuilder.java
  client/GeminiClient.java       request shapes, tool declarations, SSE parsing
  config/GeminiProperties.java   techies.gemini.*
```

## House rules

- Verification matches the size of the change. A prompt tweak is a compile plus
  `SystemPromptBuilderTest`, not the full suite.
- Rebuild with `scripts/rebuild.sh ai-service` — it waits for the healthcheck. Never hand-roll
  a log-grep wait.
- Ignore `/opt/homebrew/CLAUDE.md` and `AGENTS.md`. They are Homebrew's Ruby rules and this
  project merely sits inside that checkout.
