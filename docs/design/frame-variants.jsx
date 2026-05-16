/* ============================================================
   Kaimahi — Light variants + fun combinations
     · FrameDailyTodoLight
     · FrameAboutLight
     · FrameChatLight
     · FrameStealthChat       (OLED-true night register)
     · FrameKoruBreathSplash  (alt splash · primary mark)
     · FrameFirstChat         (empty / first-launch hero)
   ============================================================ */

/* ---------- Re-use the dark frames but recoloured via .k-frame--light ----------
   We can't just add a className because the frames render their own <div className="k-frame">.
   Instead we render a light-tuned re-implementation that pulls the same CSS vars.
   The dark and light frames share components only where it's safe.
*/

/* ============================================================
   3-light · Daily todo · light
   ============================================================ */
function FrameDailyTodoLight() {
  return (
    <div className="k-frame k-frame--light">
      <StatusBar />
      <div className="k-appbar">
        <div className="k-appbar__icon">
          <svg viewBox="0 0 20 20" fill="none"><path d="M3 6 H 17 M3 10 H 17 M3 14 H 17" stroke="currentColor" strokeLinecap="round" /></svg>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 10, flex: 1 }}>
          <Koru size={22} />
          <div className="k-serif" style={{ fontSize: 19, color: "var(--k-text-strong)" }}>Daily todo</div>
        </div>
        <div className="k-appbar__icon">
          <svg viewBox="0 0 20 20" fill="none"><circle cx="10" cy="4" r="1.4" fill="currentColor" /><circle cx="10" cy="10" r="1.4" fill="currentColor" /><circle cx="10" cy="16" r="1.4" fill="currentColor" /></svg>
        </div>
      </div>

      <div style={{ padding: "16px 22px 8px" }}>
        <div className="k-serif k-italic" style={{ fontSize: 28, color: "var(--k-text-strong)", lineHeight: 1.05 }}>
          Saturday, 16 May
        </div>
        <div className="k-mono" style={{ fontSize: 10, color: "var(--k-text-muted)", letterSpacing: "0.1em", textTransform: "uppercase", marginTop: 6 }}>
          3 open · 2 done · 1 overdue
        </div>
      </div>

      <div style={{ padding: "10px 16px 0", display: "flex", flexDirection: "column", gap: 10 }}>
        <TodoItemLight text="Pick up timber for shed reframe" meta="due today · 14:00" overdue />
        <TodoItemLight text="Call Marama about the survey peg" meta="any time today" />
        <TodoItemLight text="Ring Powerco re: temp connection" meta="before Mon" />
        <TodoItemLight text="Buy bread" meta="added by Kaimahi · 18:42" />
        <TodoItemLight text="Drop off Aroha's book" meta="done 13:10" done />
        <TodoItemLight text="Read paragraph 4 of the consent doc" meta="done 09:02" done />
      </div>

      <div style={{ position: "absolute", left: 16, right: 16, bottom: 92 }}>
        <div style={{
          background: "var(--k-surface-1)",
          border: "1px solid rgba(140, 100, 24, 0.5)",
          borderRadius: 16,
          padding: "12px 14px",
          display: "flex", gap: 12, alignItems: "center",
        }}>
          <div style={{ width: 28, height: 28, borderRadius: 14, background: "rgba(140, 100, 24, 0.14)", display: "flex", alignItems: "center", justifyContent: "center" }}>
            <Koru size={18} />
          </div>
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 12.5, color: "var(--k-text)" }}>
              Built by Kaimahi on <span className="k-mono" style={{ color: "var(--k-koura)" }}>16 May</span>.
            </div>
            <div style={{ fontSize: 11.5, color: "var(--k-text-muted)" }}>Tap to edit the schema.</div>
          </div>
          <span style={{ color: "var(--k-text-muted)", fontSize: 14 }}>›</span>
        </div>
      </div>

      <div style={{ position: "absolute", right: 18, bottom: 36 }}>
        <button className="k-pill k-pill--whero" style={{
          color: "#FFF6EC",
          boxShadow: "0 8px 22px -6px rgba(178,34,40,0.4)",
        }}>
          <span style={{ fontSize: 18, fontWeight: 300 }}>+</span>
          Add
        </button>
      </div>
      <NavBar />
    </div>
  );
}

function TodoItemLight({ text, meta, done = false, overdue = false }) {
  return (
    <div style={{
      display: "flex", gap: 12, alignItems: "flex-start",
      padding: "12px 12px 12px 14px",
      background: "var(--k-surface-1)",
      border: "1px solid var(--k-line)",
      borderRadius: 12,
      position: "relative",
      overflow: "hidden",
      opacity: done ? 0.6 : 1,
    }}>
      {overdue && <div style={{ position: "absolute", left: 0, top: 0, bottom: 0, width: 3, background: "var(--k-whero)" }} />}
      <div style={{
        width: 20, height: 20, borderRadius: 5, marginTop: 1,
        border: "1.5px solid " + (done ? "var(--k-koura)" : "var(--k-line-strong)"),
        background: done ? "var(--k-koura)" : "transparent",
        display: "flex", alignItems: "center", justifyContent: "center",
        flexShrink: 0,
      }}>
        {done && <svg width="12" height="12" viewBox="0 0 12 12" fill="none"><path d="M2 6 L5 9 L10 3" stroke="#FBF6EE" strokeWidth="1.8" strokeLinecap="round" /></svg>}
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 14.5, color: done ? "var(--k-text-muted)" : "var(--k-text-strong)", textDecoration: done ? "line-through" : "none", textDecorationColor: "var(--k-text-disabled)" }}>{text}</div>
        <div className="k-mono" style={{ fontSize: 10.5, color: overdue ? "var(--k-whero)" : "var(--k-text-muted)", letterSpacing: "0.04em", marginTop: 4 }}>{meta}</div>
      </div>
    </div>
  );
}

/* ============================================================
   7-light · About · light · manuscript register
   ============================================================ */
function FrameAboutLight() {
  return (
    <div className="k-frame k-frame--light">
      <StatusBar />
      <div className="k-appbar">
        <div className="k-appbar__icon">
          <svg viewBox="0 0 20 20" fill="none"><path d="M12 5 L 6 10 L 12 15" stroke="currentColor" strokeLinecap="round" fill="none" /></svg>
        </div>
        <div style={{ flex: 1, color: "var(--k-text-strong)", fontSize: 15 }}>About Kaimahi</div>
        <div style={{ position: "relative", width: 40, height: 40, display: "flex", alignItems: "center", justifyContent: "center" }}>
          {/* SLGWW in dark for light mode */}
          <svg width="36" height="36" viewBox="-12 -12 24 24" fill="none">
            <circle cx="0" cy="0" r="10" stroke="#8C6418" strokeWidth="0.6" />
            <circle cx="0" cy="0" r="8.4" stroke="#8C6418" strokeWidth="0.3" opacity="0.6" />
            <rect x="-0.7" y="-7.5" width="1.4" height="15" fill="#8C6418" />
            <path d="M0 -2.6 L2.4 0 L0 2.6 L-2.4 0 Z" fill="#B22228" stroke="#8C6418" strokeWidth="0.35" />
            {[[6.4, -6.4], [6.4, 6.4], [-6.4, 6.4], [-6.4, -6.4]].map(([x, y], i) => (
              <circle key={i} cx={x} cy={y} r="0.7" fill="#8C6418" />
            ))}
          </svg>
        </div>
      </div>

      <div style={{ padding: "18px 22px 100px", height: 791, overflow: "hidden" }}>
        <div className="k-mono" style={{ fontSize: 10, color: "var(--k-koura)", letterSpacing: "0.18em", textTransform: "uppercase" }}>
          Kaimahi · v0.3.0
        </div>
        <p style={{ marginTop: 10, fontSize: 14.5, lineHeight: 1.55, color: "var(--k-text)" }}>
          Kaimahi is a quiet, capable helper that lives on your phone. It chats, it listens,
          it remembers your projects, and it can do small jobs — edit a file, run a command,
          build you a <span className="k-italic k-serif">daily-todo</span> screen and pin it to your sidebar.
        </p>
        <p style={{ marginTop: 12, fontSize: 14.5, lineHeight: 1.55, color: "var(--k-text-secondary)" }}>
          It can run completely offline. No account, no internet, no telemetry. When you
          want extra grunt, it'll borrow a cloud model — but only if you say so.
        </p>
        <p style={{ marginTop: 12, fontSize: 13, lineHeight: 1.5, color: "var(--k-text-muted)" }}>
          Open-source, made in Aotearoa, part of the Cathedral AI family.
        </p>

        <div style={{ height: 1, background: "var(--k-line)", margin: "22px 0" }} />

        <div className="k-mono" style={{ fontSize: 10.5, letterSpacing: "0.18em", textTransform: "uppercase", color: "var(--k-text-muted)", marginBottom: 8 }}>
          The Cathedral family
        </div>

        <FamilyRowLight name="Crimson" tone="whero" desc="Same thinking on a Raspberry Pi 5 — sovereign, always-on, work + code." />
        <FamilyRowLight name="Lux" tone="ember" desc="Companion tier — entertainment, games, counsellors, friends. Made for keeping people company well." />
        <FamilyRowLight name="Scarlet" tone="koura" desc="Research mother-model. Not for sale. We don't sell what can't yet consent." muted />

        <div style={{ height: 1, background: "var(--k-line)", margin: "22px 0" }} />

        <div className="k-mono" style={{ fontSize: 10.5, letterSpacing: "0.18em", textTransform: "uppercase", color: "var(--k-text-muted)", marginBottom: 8 }}>
          Te reo whakapā
        </div>
        <p className="k-serif k-italic" style={{ fontSize: 18, color: "var(--k-text-strong)", lineHeight: 1.5 }}>
          He aha te mea nui o te ao? He tāngata, he tāngata, he tāngata.
        </p>
        <p style={{ fontSize: 12, color: "var(--k-text-muted)", marginTop: 4 }}>
          What is the most important thing in the world? It is people.
        </p>
      </div>
      <NavBar />
    </div>
  );
}

function FamilyRowLight({ name, desc, tone, muted = false }) {
  const colors = { whero: "var(--k-whero)", ember: "var(--k-ember)", koura: "var(--k-koura)" };
  return (
    <div style={{
      display: "flex", gap: 14, padding: "10px 0",
      opacity: muted ? 0.75 : 1,
    }}>
      <div style={{ width: 3, borderRadius: 3, background: colors[tone], flexShrink: 0 }} />
      <div>
        <div className="k-serif" style={{ fontSize: 18, color: "var(--k-text-strong)" }}>{name}</div>
        <div style={{ fontSize: 12.5, color: "var(--k-text-secondary)", lineHeight: 1.5, marginTop: 2 }}>{desc}</div>
      </div>
    </div>
  );
}

/* ============================================================
   4-light · Chat · light · daylight outdoor register
   ============================================================ */
function FrameChatLight() {
  return (
    <div className="k-frame k-frame--light">
      <StatusBar />
      <div className="k-appbar">
        <div className="k-appbar__icon">
          <svg viewBox="0 0 20 20" fill="none"><path d="M3 6 H 17 M3 10 H 17 M3 14 H 17" stroke="currentColor" strokeLinecap="round" /></svg>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 8, flex: 1 }}>
          <Koru size={22} />
          <div>
            <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
              <span style={{ fontSize: 14, color: "var(--k-text-strong)" }}>Gemma 4 E2B</span>
              <span className="k-mono" style={{ fontSize: 10, color: "var(--k-text-muted)" }}>· local</span>
              <span style={{ fontSize: 10, color: "var(--k-text-muted)" }}>▾</span>
            </div>
            <div className="k-mono" style={{ fontSize: 10, color: "var(--k-text-muted)", letterSpacing: "0.05em", marginTop: 1 }}>shed wiring</div>
          </div>
        </div>
        <div className="k-appbar__icon">
          <svg viewBox="0 0 20 20" fill="none"><circle cx="10" cy="4" r="1.4" fill="currentColor" /><circle cx="10" cy="10" r="1.4" fill="currentColor" /><circle cx="10" cy="16" r="1.4" fill="currentColor" /></svg>
        </div>
      </div>

      <div style={{ padding: "18px 18px 0", display: "flex", flexDirection: "column", gap: 14, height: 615, overflow: "hidden" }}>
        <ModelBubbleLight>Kia ora. What are we working on this evening?</ModelBubbleLight>
        <UserBubbleLight>
          The 16 A circuit in the new shed — can you check if 2.5 mm² T+E is enough for a 12 m run if I'm pulling about 12 A continuous?
        </UserBubbleLight>
        <ToolStripeLight label="reading · NZS 3000 cable tables" />
        <ModelBubbleLight>
          At 12 A continuous over 12 m, 2.5 mm² T+E gives roughly{" "}
          <span className="k-mono" style={{ color: "var(--k-koura)" }}>2.0 % volt-drop</span>{" "}
          on a 230 V circuit — inside the 5 % limit. Current capacity is fine for 16 A protection.
        </ModelBubbleLight>
        <ModelBubbleLight streaming>
          One thing to check — if you're running it in insulated wall cavities
        </ModelBubbleLight>
      </div>

      <div style={{
        position: "absolute", bottom: 24, left: 12, right: 12,
        background: "var(--k-surface-1)",
        border: "1px solid var(--k-line-strong)",
        borderRadius: 22,
        padding: "10px 12px 10px 16px",
        display: "flex", alignItems: "center", gap: 10,
      }}>
        <span style={{ color: "var(--k-text-muted)", fontSize: 20, lineHeight: 1 }}>+</span>
        <div style={{ flex: 1, color: "var(--k-text-muted)", fontSize: 14 }}>Message Kaimahi…</div>
        <div style={{
          width: 36, height: 36, borderRadius: 18,
          background: "var(--k-brand-gradient)",
          display: "flex", alignItems: "center", justifyContent: "center",
        }}>
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
            <path d="M3 8 H 13 M9 4 L 13 8 L 9 12" stroke="#FBF6EE" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </div>
      </div>
      <NavBar />
    </div>
  );
}

function ModelBubbleLight({ children, streaming = false }) {
  return (
    <div style={{ display: "flex", alignItems: "flex-end", gap: 8, alignSelf: "flex-start", maxWidth: "82%" }}>
      <div style={{
        background: "var(--k-surface-1)",
        border: "1px solid var(--k-line)",
        borderRadius: "14px 14px 14px 4px",
        padding: "11px 14px",
        fontSize: 14, lineHeight: 1.45, color: "var(--k-text)",
      }}>
        {children}
        {streaming && (
          <span style={{ display: "inline-block", marginLeft: 6, verticalAlign: "middle" }}>
            <span style={{
              display: "inline-block",
              width: 7, height: 7, borderRadius: 4,
              background: "var(--k-ember)",
              boxShadow: "0 0 12px rgba(184,90,36,0.5)",
              animation: "kPulse 1.2s ease-in-out infinite",
            }} />
          </span>
        )}
      </div>
    </div>
  );
}

function UserBubbleLight({ children }) {
  return (
    <div style={{ display: "flex", alignSelf: "flex-end", maxWidth: "82%" }}>
      <div style={{
        background: "rgba(178, 34, 40, 0.10)",
        border: "1px solid rgba(178, 34, 40, 0.45)",
        borderRadius: "14px 14px 4px 14px",
        padding: "11px 14px",
        fontSize: 14, lineHeight: 1.45, color: "var(--k-text-strong)",
      }}>
        {children}
      </div>
    </div>
  );
}

function ToolStripeLight({ label }) {
  return (
    <div style={{
      alignSelf: "flex-start",
      display: "inline-flex", alignItems: "center", gap: 8,
      padding: "6px 12px",
      borderRadius: 8,
      background: "rgba(184, 90, 36, 0.10)",
      border: "1px solid rgba(184, 90, 36, 0.45)",
    }}>
      <div style={{ width: 5, height: 5, borderRadius: 3, background: "var(--k-ember)" }} />
      <span className="k-mono" style={{ fontSize: 10.5, color: "var(--k-ember)", letterSpacing: "0.08em", textTransform: "uppercase" }}>{label}</span>
    </div>
  );
}

/* ============================================================
   FUN · 1 · Stealth chat — OLED-true, nightstand register
   ============================================================ */
function FrameStealthChat() {
  return (
    <div className="k-frame k-frame--stealth">
      <StatusBar time="03:42" />
      <div className="k-appbar" style={{ borderBottom: "none" }}>
        <div className="k-appbar__icon" style={{ color: "#3C3833" }}>
          <svg viewBox="0 0 20 20" fill="none"><path d="M3 6 H 17 M3 10 H 17 M3 14 H 17" stroke="currentColor" strokeLinecap="round" /></svg>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 8, flex: 1, color: "#5A554F" }}>
          <Koru size={18} />
          <div className="k-mono" style={{ fontSize: 11, letterSpacing: "0.08em", textTransform: "uppercase" }}>
            stealth · local only
          </div>
        </div>
      </div>

      <div style={{ padding: "60px 22px 0", display: "flex", flexDirection: "column", gap: 18 }}>
        <div className="k-serif k-italic" style={{ fontSize: 20, color: "#807872", lineHeight: 1.5 }}>
          Anything you'd like to think through before sleep?
        </div>

        <div style={{ marginTop: 8, padding: "10px 14px", borderLeft: "2px solid #1C1C1C", color: "#807872", fontSize: 13, lineHeight: 1.55 }}>
          The roof eave detail — I want to mock the flashing return one more time tomorrow before we order zincalume.
        </div>

        <div style={{ display: "flex", alignItems: "center", gap: 10, padding: "12px 0" }}>
          <div style={{
            width: 8, height: 8, borderRadius: 5,
            background: "var(--k-ember)",
            boxShadow: "0 0 16px rgba(217,119,66,0.8), 0 0 4px rgba(217,119,66,1)",
            animation: "kPulse 2.4s ease-in-out infinite",
          }} />
          <span className="k-mono" style={{ fontSize: 10.5, color: "#5A554F", letterSpacing: "0.1em", textTransform: "uppercase" }}>
            thinking · 18 tokens
          </span>
        </div>

        <div style={{ color: "#807872", fontSize: 13.5, lineHeight: 1.55 }}>
          Right — if you sketch the return overhang on a serviette, I'll diff it against your earlier ridge dimensions in the morning and flag anything that drifts.
        </div>

        <div style={{ marginTop: 10 }} className="k-mono">
          <div style={{ fontSize: 10, color: "#3C3833", letterSpacing: "0.1em", textTransform: "uppercase" }}>
            ◌ saved to memory · 03:42 · expires in 30 days
          </div>
        </div>
      </div>

      <div style={{
        position: "absolute", bottom: 26, left: 16, right: 16,
        padding: "10px 14px",
        border: "1px solid #141414",
        borderRadius: 22,
        color: "#3C3833",
        fontSize: 13,
        display: "flex", alignItems: "center", justifyContent: "space-between",
      }}>
        <span>say something quietly…</span>
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
          <rect x="6" y="2" width="4" height="8" rx="2" stroke="#3C3833" />
          <path d="M3 8 V 9 A 5 5 0 0 0 13 9 V 8" stroke="#3C3833" />
          <path d="M8 13 V 15" stroke="#3C3833" />
        </svg>
      </div>
      <NavBar />
    </div>
  );
}

/* ============================================================
   FUN · 2 · Koru-breathing splash — alt cold start
   Uses the PRIMARY mark (the logarithmic-spiral koru), not the
   math-spiral. Breathing pulse rather than rotation.
   ============================================================ */
function FrameKoruBreathSplash() {
  return (
    <div className="k-frame" style={{ background: "#070707" }}>
      <StatusBar />
      <div style={{
        position: "absolute", inset: 0,
        display: "flex", flexDirection: "column",
        alignItems: "center", justifyContent: "center",
        paddingBottom: 100,
      }}>
        {/* halo */}
        <div style={{ position: "relative", width: 230, height: 230, display: "flex", alignItems: "center", justifyContent: "center" }}>
          <div style={{
            position: "absolute", inset: 0,
            background: "radial-gradient(circle, rgba(193,39,45,0.22), rgba(216,168,87,0.10) 45%, transparent 70%)",
            borderRadius: "50%",
            animation: "kBreathe 6s ease-in-out infinite",
          }} />
          {/* faint outer ring */}
          <svg width="230" height="230" viewBox="-12 -12 24 24" style={{ position: "absolute", inset: 0 }}>
            <circle cx="0" cy="0" r="10.2" stroke="rgba(216,168,87,0.16)" strokeWidth="0.12" fill="none" />
            <circle cx="0" cy="0" r="11.4" stroke="rgba(193,39,45,0.12)" strokeWidth="0.08" fill="none" />
          </svg>
          <div style={{ animation: "kBreathe 6s ease-in-out infinite" }}>
            <Koru size={150} />
          </div>
        </div>

        <div style={{ marginTop: 30, textAlign: "center" }}>
          <div className="k-serif" style={{ fontSize: 44, color: "var(--k-text-strong)", letterSpacing: "0.01em", lineHeight: 1 }}>
            Kaimahi
          </div>
          <div className="k-serif k-italic" style={{ fontSize: 16, color: "var(--k-koura)", marginTop: 10, letterSpacing: "0.02em" }}>
            kia ora — ready when you are.
          </div>
        </div>
      </div>

      <div style={{ position: "absolute", left: 0, right: 0, bottom: 56, textAlign: "center" }}>
        <span className="k-mono" style={{ fontSize: 9.5, color: "#3a3833", letterSpacing: "0.22em", textTransform: "uppercase" }}>
          r = a · e<span style={{ verticalAlign: "super", fontSize: 7 }}>b·θ</span>
        </span>
      </div>
      <NavBar />
    </div>
  );
}

/* ============================================================
   FUN · 3 · First chat hero — empty / first-launch state
   Combines splash quietude with composer-led affordance.
   ============================================================ */
function FrameFirstChat() {
  return (
    <div className="k-frame">
      <StatusBar />
      <div className="k-appbar">
        <div className="k-appbar__icon">
          <svg viewBox="0 0 20 20" fill="none"><path d="M3 6 H 17 M3 10 H 17 M3 14 H 17" stroke="currentColor" strokeLinecap="round" /></svg>
        </div>
        <div style={{ flex: 1, display: "flex", alignItems: "center", gap: 8 }}>
          <Koru size={22} />
          <div>
            <div style={{ fontSize: 13.5, color: "var(--k-text-secondary)" }}>
              <span style={{ color: "var(--k-text-strong)" }}>Gemma 4 E2B</span>
              <span className="k-mono" style={{ fontSize: 10, color: "var(--k-text-muted)", marginLeft: 6 }}>· local</span>
              <span style={{ fontSize: 10, color: "var(--k-text-muted)", marginLeft: 4 }}>▾</span>
            </div>
            <div className="k-mono" style={{ fontSize: 9.5, color: "var(--k-text-muted)", letterSpacing: "0.06em", textTransform: "uppercase", marginTop: 1 }}>
              new chat · no folder
            </div>
          </div>
        </div>
      </div>

      {/* hero */}
      <div style={{ position: "relative", padding: "70px 32px 0", minHeight: 430 }}>
        {/* ghost math-spiral behind */}
        <div style={{
          position: "absolute", top: 30, right: -40,
          opacity: 0.06,
          pointerEvents: "none",
        }}>
          <MathSpiral size={310} />
        </div>

        <div style={{ position: "relative" }}>
          <div className="k-mono" style={{ fontSize: 10, color: "var(--k-koura)", letterSpacing: "0.22em", textTransform: "uppercase" }}>
            Kia ora
          </div>
          <div className="k-serif" style={{
            fontSize: 40, color: "var(--k-text-strong)",
            lineHeight: 1.05, letterSpacing: "0.005em",
            marginTop: 14,
          }}>
            What are we{" "}
            <span className="k-italic" style={{ color: "var(--k-koura)" }}>working on</span>{" "}
            this evening?
          </div>
          <div style={{ marginTop: 18, fontSize: 13.5, color: "var(--k-text-secondary)", lineHeight: 1.55, maxWidth: 320 }}>
            I run locally, remember our projects, and can build you small screens for things you care about.
          </div>
        </div>
      </div>

      {/* suggestion grid */}
      <div style={{ padding: "16px 22px 0", display: "flex", flexDirection: "column", gap: 10 }}>
        <div className="k-mono" style={{ fontSize: 10, color: "var(--k-text-muted)", letterSpacing: "0.14em", textTransform: "uppercase", marginBottom: 4 }}>
          or, try
        </div>
        <SuggestCard tone="whero" title="Build me a daily-todo screen" hint="pinned to your sidebar" icon="□" />
        <SuggestCard tone="koura" title="What's in memory about Marama?" hint="reads · person" icon="◇" />
        <SuggestCard tone="ember" title='Run "git status" in shed-wiring/' hint="shell · agent-acting" icon="›_" />
      </div>

      {/* composer */}
      <div style={{
        position: "absolute", bottom: 24, left: 12, right: 12,
        background: "var(--k-surface-1)",
        border: "1px solid var(--k-line-strong)",
        borderRadius: 22,
        padding: "10px 12px 10px 16px",
        display: "flex", alignItems: "center", gap: 10,
      }}>
        <span style={{ color: "var(--k-text-muted)", fontSize: 20, lineHeight: 1 }}>+</span>
        <div style={{ flex: 1, color: "var(--k-text-muted)", fontSize: 14 }}>Message Kaimahi…</div>
        <div style={{
          width: 36, height: 36, borderRadius: 18,
          background: "var(--k-brand-gradient)",
          display: "flex", alignItems: "center", justifyContent: "center",
        }}>
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
            <path d="M3 8 H 13 M9 4 L 13 8 L 9 12" stroke="#0A0A0A" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </div>
      </div>
      <NavBar />
    </div>
  );
}

function SuggestCard({ tone, title, hint, icon }) {
  const palette = {
    whero: { fg: "var(--k-whero)", border: "rgba(193, 39, 45, 0.35)", bg: "rgba(193, 39, 45, 0.06)" },
    koura: { fg: "var(--k-koura)", border: "rgba(216, 168, 87, 0.35)", bg: "rgba(216, 168, 87, 0.06)" },
    ember: { fg: "var(--k-ember)", border: "rgba(217, 119, 66, 0.35)", bg: "rgba(217, 119, 66, 0.06)" },
  }[tone];
  return (
    <div style={{
      display: "flex", alignItems: "center", gap: 12,
      padding: "12px 14px",
      background: "var(--k-surface-1)",
      border: "1px solid var(--k-line)",
      borderRadius: 14,
    }}>
      <div className="k-mono" style={{
        width: 30, height: 30, borderRadius: 9,
        display: "flex", alignItems: "center", justifyContent: "center",
        fontSize: 13, color: palette.fg,
        background: palette.bg,
        border: "1px solid " + palette.border,
      }}>{icon}</div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 14, color: "var(--k-text-strong)" }}>{title}</div>
        <div className="k-mono" style={{ fontSize: 10, color: "var(--k-text-muted)", letterSpacing: "0.06em", textTransform: "uppercase", marginTop: 3 }}>{hint}</div>
      </div>
      <span style={{ color: palette.fg, fontSize: 16 }}>›</span>
    </div>
  );
}

Object.assign(window, {
  FrameDailyTodoLight, FrameAboutLight, FrameChatLight,
  FrameStealthChat, FrameKoruBreathSplash, FrameFirstChat,
});
