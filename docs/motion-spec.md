# Interaction motion spec (batch 1)

Owner request: **「调研学习一下交互动画，我们要加一些交互动画在这个软件上面」**.

This document is two things: the research that decided the numbers, and the spec those
numbers serve. Tokens live in
[`theme/Motion.kt`](../ui/composeApp/src/commonMain/kotlin/com/framemark/native/theme/Motion.kt)
— this file may name a value, the code owns it.

Scope of batch 1: **five** interaction families implemented (sheet, page switch, card
press/selection, overlay enter/exit, first-load reveal). Four are specified here and
**deliberately deferred** (§4). Motion is chrome only: it never touches the photo, the
frame render, the project format or any persisted state.

---

## 1. What the good ones do

### 1.1 The duration ladder and the curves are not opinions

Material 3 is the reference every one of these apps is either built on or copied from, and
it publishes its motion as *token tables*, not per-component numbers. Two findings decided
this spec:

- **Durations cluster, they don't sprawl**: 100 / 200 / 300 / 500 ms is the whole working
  range for UI transitions; 200 ms is the default, and anything past 500 ms is a
  set-piece, not a control. ([M3 easing & duration](https://m3.material.io/styles/motion/easing-and-duration/tokens-specs),
  [transcribed token table](https://github.com/simota/agent-skills/blob/main/muse/reference/motion-tokens.md))
- **Direction gets its own curve.** An element *arriving* is `standard-decelerate`
  `cubic-bezier(0.0, 0.0, 0.0, 1.0)`; an element *leaving* is `standard-accelerate`
  `cubic-bezier(0.3, 0.0, 1.0, 1.0)`; the plain in-between default is `standard`
  `cubic-bezier(0.2, 0.0, 0.0, 1.0)`. Material also ships an **emphasised** pair for large
  movements — in `Cubic(0.05, 0.7, 0.1, 1.0)`, out `Cubic(0.3, 0.0, 0.8, 0.15)` — and the
  canonical asymmetry that came out of it: **a dialog enters at ~400 ms emphasised-decelerate
  and exits at ~200 ms emphasised-accelerate.**
  ([M3 tokens](https://m3.material.io/styles/motion/easing-and-duration/tokens-specs),
  [design-transitions-timing](https://github.com/Intense-Visions/harness-engineering/blob/main/agents/skills/claude-code/design-transitions-timing/SKILL.md))
- Carbon reaches the same shape from a different direction, splitting *productive* from
  *expressive* curves and using `cubic-bezier(0.2, 0, 0.38, 0.9)` as standard easing.
  ([Carbon motion](https://v10.carbondesignsystem.com/guidelines/motion/overview/))

The practical consequence for FrameMark: **exits are shorter than entrances**, and every
"leaving" transition uses an accelerate curve. That one rule is most of what makes a UI feel
quick rather than merely animated.

### 1.2 Photo editors animate *commit*, not *input*

The photo-editor-specific evidence is thinner than the design-system evidence, and it is
worth saying so: there is no published motion spec from Adobe or Google Photos. What is
verifiable is the IA they all converged on, and the direction it points:

- Lightroom mobile moved to an **expandable toolbar** so the entire toolset is reachable
  without leaving the canvas — the tool strip grows in place and the canvas stays put.
  ([Adobe blog](https://blog.adobe.com/en/publish/2023/07/26/adobe-photoshop-lightroom-has-new-mobile-interface-make-editing-even-easier-on-go))
- Snapseed's 4.0 update is described by its own team in terms of **fewer taps** and
  parameter/mode switching *inside* a tool, i.e. the optimised quantity is interaction
  count, not animation richness. (vendor note, secondary source — treat as intent, not
  measurement)
- The academic read on photo-editor UIs is the same: users judge these apps on whether the
  interface stays *out of the way* of the image.
  ([UI design of mobile photo editors](https://doi.org/10.2991/icobest-18.2018.79))

So the rule that governs this app: **a photo editor's animation budget is spent on the
chrome around the canvas, and on confirming what the user just did.** The canvas is not a
place to animate — every millisecond of transition on a drag-rendered preview is latency the
user feels in the drag itself.

This also matches the Compose performance guidance: never read an animated value in
composition, keep animated properties to alpha/offset/scale, and don't animate layout.
([android/skills animation.md](https://github.com/android/skills/blob/main/jetpack-compose/jetpack-compose-performance/references/animation.md))

### 1.3 Reduced motion is a hard requirement, not a nicety

- **WCAG 2.2 SC 2.3.3** (Animation from Interactions, AAA): motion triggered by interaction
  *can be disabled*, unless the motion is essential to the information conveyed.
  ([W3C](https://www.w3.org/WAI/WCAG22/Understanding/animation-from-interactions.html))
- Vestibular reactions are physical: nausea, migraine, sometimes bed rest. Parallax and
  large transforms are the usual culprits.
  ([MDN](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/At-rules/@media/prefers-reduced-motion),
  [web.dev](https://web.dev/articles/prefers-reduced-motion))

Two viable strategies exist — **fade-only** (default for product UI) or **instant-cut**
(`duration 0`). FrameMark implements **instant-cut**, because our animations are all chrome
state changes where the end state carries the whole meaning: a sheet that is already open,
a ring that is already on. Nothing here is a transition whose *path* matters.

**Platform reality check.** Compose's animation core reads
`androidx.compose.ui.MotionDurationScale` from the coroutine context, but Compose
Multiplatform **1.9.0 ships no `LocalMotionDurationScale`** — we verified against the
resolved `ui-desktop` artifact, which contains only the `MotionDurationScale` interface, its
`Key`, and `DefaultImpls`. There is therefore no platform value a composable can read.
FrameMark supplies `LocalFmMotionScale` (0..1, same domain as `scaleFactor`), provided by
`FrameMarkTheme(motionScale = …)`, and every helper collapses to `snap()` /
`EnterTransition.None` at 0.

---

## 2. Token set

Implemented in `theme/Motion.kt`. Screen code names the intent; the number lives there.

### Durations (`FmDuration`)

| Token | ms | Used for |
|---|---:|---|
| `instant` | 0 | the reduced-motion end state only — never a taste value |
| `press` | 90 | pointer-down / release on a control already on screen |
| `fast` | 140 | ink, ring, dot, and other state flips |
| `standard` | 200 | content crossfade, a row settling, an item revealing |
| `emphasized` | 320 | a panel travelling most of the screen |
| `exit` | 180 | every departure: shorter than its entrance, always |

Ladder is asserted monotonic in `MotionTest.durationLadderIsMonotonic`, and
`exit < emphasized` in `MotionTest.exitsAreShorterThanEntrances`.

### Curves (`FmEasing`)

| Token | cubic-bezier | M3 name |
|---|---|---|
| `standard` | `0.2, 0, 0, 1` | standard |
| `enter` | `0, 0, 0, 1` | standard-decelerate |
| `exit` | `0.3, 0, 1, 1` | standard-accelerate |
| `emphasizedEnter` | `0.05, 0.7, 0.1, 1` | emphasised-decelerate |
| `emphasizedExit` | `0.3, 0, 0.8, 0.15` | emphasised-accelerate |
| `linear` | — | determinate progress only |

### Springs (`FmSpring`)

Springs are reserved for motion that is **interrupted** — a sheet the user released, a card
they pressed — because only then is there velocity worth carrying. Fire-and-forget
transitions use duration + curve.

| Token | damping | stiffness | Used for |
|---|---:|---:|---|
| `settle` | 0.85 | 380 | sheet travelling to its snap stop |
| `press` | 0.55 | 1200 | card / control press feedback |
| `gentle` | 1.0 | 200 | (reserved) critically-damped reveal |

### Scale

`LocalFmMotionScale` — `1f` full, `0f` none, provided by `FrameMarkTheme(motionScale = …)`.
Helpers `fmTweenSpec` / `fmSpringSpec` / `fmOverlayEnter` / `fmOverlayExit` /
`fmContentEnter` / `fmContentExit` / `Modifier.fmRevealOnEnter` all short-circuit at 0.

---

## 3. Interaction table

Legend — **Implemented** = in this batch. **Deferred** = specified, not built (§4).
"drag-safe" means the animation never runs on a property the user is currently dragging.

### 3.1 Implemented

| # | Interaction | Trigger | Property | Duration / spring | Curve | Notes |
|---|---|---|---|---|---|---|
| 1 | Tool sheet level change (收起 ↔ 半开 ↔ 展开) | grabber tap, head chevron, drag release, back | sheet height | spring `settle` (0.85 / 380) | — | **drag-safe**: while the finger is down `sheetDragPx` *is* the height; the animatable is `snapTo`'d to the finger every drag frame so the **release point is the start of the settle**. Without that the sheet jumps back to the old stop before animating. First composition and window re-measure `snapTo`, never replay. |
| 2 | Tool page switch (边框 / 样式 / 文字 / Logo / 水印) | tool cell tap | page content alpha + 1/40 height rise | 200 in / 180 out | `enter` + `emphasizedEnter` in, `exit` out | `AnimatedContent` keyed on the tool. Travel is 1/40 of height, not a screenful: the container does not move, so a long slide would read as a page arriving from elsewhere. Both pages are composed during the swap, so only alpha/offset may animate — never size. |
| 2b | Desktop tab ink bar | tab tap | ink x + width | existing `FmTabInk.Spring` | existing | **Already shipped** (`DesktopInspectorRail`); reused as-is, not re-created. The desktop ink bar was the precedent for "spring on an index, not on pixels". |
| 3 | Border card press | pointer down / up on a card | scale 1.0 → 0.96 → 1.0 | spring `press` (0.55 / 1200) | — | `graphicsLayer` only, so a row of cards never reflows while one is pressed. |
| 3b | Border card selection | card becomes / stops being current | ring alpha + ring outset | 140 | `standard` | Ring grows out of the card edge instead of popping on. `ringProgress` is read **inside `drawBehind`** (draw phase), so the card does not recompose once per frame. The check dot, the name weight and the name colour still switch instantly — they are information, not motion. |
| 4 | Brand picker overlay (and border library overlay) | open / dismiss / scrim tap / back | layer alpha + 1/12 height rise | 200 in / 180 out | `enter` + `emphasizedEnter` in, `exit` + `emphasizedExit` out | One `AnimatedVisibility` per overlay; exit is a shade under the entrance. **Known shortcut:** the whole layer rises, so the scrim travels the same 1/12 as the panel instead of staying pinned — see §4.1. Content re-checks its own precondition during the exit (switching tool closes the picker). |
| 5 | First-load grid reveal | first composition of the brand grid | alpha + 6 dp rise | 200 + 28 ms/item stagger, capped 168 ms | `enter` | Only indices 0–11 animate; past that the entrance is **skipped, not shortened**, and scale 0 renders the final state on frame one. Stagger starts at 0 and is capped, so 61 brands do not imply a 1.7 s cascade. |

### 3.2 Deferred (specified, not built)

| # | Interaction | Trigger | Property | Duration / spring | Curve | Notes |
|---|---|---|---|---|---|---|
| 6 | Slider drag bubble | thumb grab / drag / release | bubble alpha + scale, anchored to thumb | 90 / 140 / 90 | `enter` / `exit` | **drag-safe by construction**: the bubble's *position* must be a direct read of the value (no animation), only its appear/disappear is animated. The numeric **value text never animates** — a number that counts up is a number you cannot read mid-drag. |
| 7 | Crop mode enter | 裁切 tapped | chrome alpha out, grid alpha in, photo *frame* only | 200 out / 200 in | `standard` | The **photo pixels are never animated** — no zoom, no crossfade. The crop rectangle itself is dragged, so it is drag-safe like #1. |
| 8 | Export progress | export started / finished | bar width (linear), check scale+alpha, panel shake | linear for the bar; 200 for the check | `linear`, `standard` | Progress is **determinate** where the engine reports it; an indeterminate shimmer is decoration on a value the user is waiting for. Failure: no shake (§5.1) — the error message and the retry affordance carry it. |

---

## 4. Deliberately not animated — and why

This is the half of the spec that protects the app. Each of these is a decision, not an
omission.

**Never animate these, anywhere:**

1. **The canvas / the photo / the frame render.** Not on tool switch, not on sheet drag, not
   on appearance change. The preview is what the user is judging; a crossfading canvas
   changes the thing under evaluation and costs a render. Note the existing preview path is
   already tiered for cost (`product/PreviewTier.kt`) — animation there would fight it.
2. **Any value the user is dragging.** Sheet height, slider value, crop rect, watermark
   dx/dy/angle. While the finger is down, the input *is* the value; a spring on it converts
   tracking into lag. We animate the **commit after release** (#1) and nothing else.
3. **The numeric value text of a parameter.** No count-up, no digit roll. It must be
   readable at every frame of a drag, and the frame where a tween is 60 % done is exactly the
   frame the user is trying to read.
4. **The slider thumb offset during a drag** — same rule as (2). The thumb follows the
   pointer 1:1; only its bubble's opacity is animated.
5. **Any text that conveys state**: the export percentage, the page title, the "已选" marker,
   the failure message. Text that moves is text that cannot be read.
6. **Selection *information*.** When a border is picked, the ring animates (#3b) but the
   check dot, the name's weight/colour and the "当前边框" semantics flip immediately. The
   animation must never be the only channel that says "this is now selected" — that is also
   the accessibility rule from WCAG 2.3.3 (motion may not be *essential*).
7. **Layout / size.** Nothing in this batch animates a width, height, padding or font size.
   Animated layout reflows neighbours every frame and is the single most common cause of
   visible jank in Compose lists. Where a size "changes" (ring outset) it is drawn, not laid
   out.
8. **The 8 px between two list rows** (spacing, separators, rules). `FmRhythm` owns those.

**Judgement calls worth recording:**

- **No page-slide navigation.** The editor is one screen with a swapping panel; a push/slide
  transition would imply a back stack that does not exist. Hence fog-in-place (#2).
- **No bounce anywhere except a released sheet.** The visual language is a flat, hard-edged
  showcase (hairlines, `FmRadii` from the demo); a wobbly card grid would contradict it.
- **No press-scale on tool cells.** They already have a ripple, they are small, and five of
  them moving on every tap is noise. The press scale is reserved for the one control with
  real spatial presence: the border card.
- **No staggered reveal on scroll.** The reveal runs on first load only, capped at 12 items.
  Replaying an entrance whenever a grid is scrolled back into view is a twitch, not a
  reveal.
- **No exit animation on a *dismiss* that the user already committed to** (applying a border
  closes the library). The application is the feedback; adding a 180 ms fade on top of it
  just delays the canvas.

### 4.1 Known shortcuts in this batch

Honest list, all taken to keep the diff reviewable:

- Overlay enter (#4) rises the **whole layer** 1/12 of the height, so the scrim travels with
  the panel instead of staying pinned. Doing it properly means passing a reveal progress into
  the overlay and offsetting only the panel — a follow-up, not a rewrite.
- The reveal (#5) re-runs if the first 12 grid items are scrolled out and back. Capped and
  short, so it is a minor twitch rather than a bug.
- `FrameMarkTheme(motionScale = …)` accepts `0f` but **nothing reads the OS "reduce motion"
  setting yet**. The hook exists and is tested; wiring it per platform (Android
  `TRANSITION_ANIMATION_SCALE`, Windows/iOS equivalents) is a separate task.
- No crop/export/slider animation yet — §3.2.

---

## 5. Testing

`commonTest/…/theme/MotionTest.kt` (runs under `:composeApp:desktopTest`) asserts the two
properties the app relies on:

- the **duration ladder is monotonic** (`instant < press < fast < standard < emphasized`) and
  exits are shorter than entrances;
- **scale 0 collapses every duration to 0** (`FmMotionRules.durationMs(base, 0f) == 0` for
  every token), negative scale is treated as no motion, and a tiny positive scale never
  rounds to a 0 ms tween that still occupies a frame;
- the pure transition helpers: `revealDelayMs` (starts at 0, capped, never decreases),
  `shouldReveal` (skip past the cap, not clamp), `nearestStop` (closest stop, ties to the
  **lower** stop — the pre-extraction `minByOrNull` behaviour, out-of-range clamps, empty
  list safe);
- spring presets are distinct and reject impossible values;
- the legacy U1a nav tokens survived the move from `Tokens.kt` with their values intact.

A pixel/timing test is deliberately absent: the timing belongs to Compose's animation core,
which is not ours to re-verify. What is ours — that we hand it `snap()` at scale 0 — is a
pure function and is tested as one.

What we *do* capture is a **frame strip** (`MotionStripRenderTest`, `desktopTest`): the real
`PhoneSheetHost` rendered headlessly with an explicit `nanoTime` per frame, so "what does
this look like 90 ms in" is reproducible and does not depend on how fast the machine is.
Two rows — sheet open under `FmSpring.settle`, and a Border→Params switch under
`fmContentEnter`/`fmContentExit` — sampled at `before / +30 / +60 / +90 / +120 / +160 /
+240 ms`. The test fails if the frames come out frozen, which is what catches a transition
that silently stopped animating.

- Written to `ui/composeApp/build/motion-strip/` (contact sheet + each frame).
- Archived at [`evidence/motion-batch1/`](../evidence/motion-batch1/) —
  `motion-batch1-strip.png` is the strip, `sheet-*.png` / `switch-*.png` the raw frames.

The row-1 panel height is driven in the harness by the same `FmSpring.settle` token the
production sheet uses; the surrounding panel (grabber, head, tab row, parameter rows, tool
bar) is the real component tree, not a stand-in.

