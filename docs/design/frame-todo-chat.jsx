/* ============================================================
   Kaimahi — Frame 3 · Daily todo
              Frame 4 · Chat (repainted)
   ============================================================ */

function FrameDailyTodo() {
  return (
    <div className="k-frame">
      <StatusBar />

      <div className="k-appbar">
        <div className="k-appbar__icon">
          <svg viewBox="0 0 20 20" fill="none"><path d="M3 6 H 17 M3 10 H 17 M3 14 H 17" stroke="currentColor" strokeLinecap="round" /></svg>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 10, flex: 1 }}>
          <Koru size={22} />
          <div className="k-serif" style={{ fontSize: 19, color: "var(--k-text-strong)", letterSpacing: "0.005em" }}>Daily todo</div>
        </div>
        <div className="k-appbar__icon" title="overflow">
          <svg viewBox="0 0 20 20" fill="none"><circle cx="10" cy="4" r="1.4" fill="currentColor" /><circle cx="10" cy="10" r="1.4" fill="currentColor" /><circle cx="10" cy="16" r="1.4" fill="currentColor" /></svg>
        </div>
      </div>

      {/* date strip */}
      <div style={{ padding: "16px 22px 8px", display: "flex", alignItems: "baseline", justifyContent: "space-between" }}>
        <div>
          <div className="k-serif k-italic" style={{ fontSize: 26, color: "var(--k-text-strong)", lineHeight: 1.05 }}>
            Saturday, 16 May
          </div>
          <div className="k-mono" style={{ fontSize: 10, color: "var(--k-text-muted)", letterSpacing: "0.1em", textTransform: "uppercase", marginTop: 6 }}>
            3 open · 2 done · 1 overdue
          </div>
        </div>
      </div>

      {/* list */}
      <div style={{ padding: "10px 16px 0", display: "flex", flexDirection: "column", gap: 10 }}>
        <TodoItem text="Pick up timber for shed reframe" meta="due today · 14:00" overdue />
        <TodoItem text="Call Marama about the survey peg" meta="any time today" />
        <TodoItem text="Ring Powerco re: temp connection" meta="before Mon" />
        <TodoItem text="Buy bread" meta="added by Kaimahi · 18:42" />
        <TodoItem text="Drop off Aroha's book" meta="done 13:10" done />
        <TodoItem text="Read paragraph 4 of the consent doc" meta="done 09:02" done />
      </div>

      {/* footer card */}
      <div style={{ position: "absolute", left: 16, right: 16, bottom: 92 }}>
        <div className="k-card" style={{
          background: "var(--k-surface-1)",
          borderColor: "rgba(216, 168, 87, 0.35)",
          padding: "12px 14px",
          display: "flex", gap: 12, alignItems: "center",
        }}>
          <div style={{ width: 28, height: 28, borderRadius: 14, background: "rgba(216, 168, 87, 0.14)", display: "flex", alignItems: "center", justifyContent: "center" }}>
            <Koru size={18} />
          </div>
          <div style={{ flex: 1, lineHeight: 1.4 }}>
            <div style={{ fontSize: 12.5, color: "var(--k-text)" }}>
              Built by Kaimahi on <span className="k-mono" style={{ color: "var(--k-koura)" }}>16 May</span>.
            </div>
            <div style={{ fontSize: 11.5, color: "var(--k-text-muted)" }}>Tap to edit the schema.</div>
          </div>
          <span style={{ color: "var(--k-text-muted)", fontSize: 14 }}>›</span>
        </div>
      </div>

      {/* FAB */}
      <div style={{ position: "absolute", right: 18, bottom: 36 }}>
        <button className="k-pill k-pill--whero" style={{ boxShadow: "0 8px 22px -6px rgba(193,39,45,0.55)" }}>
          <span style={{ fontSize: 18, fontWeight: 300 }}>+</span>
          Add
        </button>
      </div>

      <NavBar />
    </div>
  );
}

function TodoItem({ text, meta, done = false, overdue = false }) {
  return (
    <div style={{
      display: "flex", gap: 12, alignItems: "flex-start",
      padding: "12px 12px 12px 14px",
      background: "var(--k-surface-1)",
      border: "1px solid var(--k-line)",
      borderRadius: 12,
      position: "relative",
      overflow: "hidden",
      opacity: done ? 0.55 : 1,
    }}>
      {overdue && <div style={{ position: "absolute", left: 0, top: 0, bottom: 0, width: 3, background: "var(--k-whero)" }} />}
      <div style={{
        width: 20, height: 20, borderRadius: 5, marginTop: 1,
        border: "1.5px solid " + (done ? "var(--k-koura)" : "var(--k-line-strong)"),
        background: done ? "var(--k-koura)" : "transparent",
        display: "flex", alignItems: "center", justifyContent: "center",
        flexShrink: 0,
      }}>
        {done && <svg width="12" height="12" viewBox="0 0 12 12" fill="none"><path d="M2 6 L5 9 L10 3" stroke="#0A0A0A" strokeWidth="1.8" strokeLinecap="round" /></svg>}
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 14.5, color: done ? "var(--k-text-muted)" : "var(--k-text-strong)", textDecoration: done ? "line-through" : "none", textDecorationColor: "var(--k-text-disabled)" }}>{text}</div>
        <div className="k-mono" style={{ fontSize: 10.5, color: overdue ? "var(--k-whero)" : "var(--k-text-muted)", letterSpacing: "0.04em", marginTop: 4 }}>{meta}</div>
      </div>
    </div>
  );
}

/* ============================================================
   Frame 4 — Chat
   ============================================================ */

function FrameChat() {
  return (
    <div className="k-frame">
      <StatusBar />

      <div className="k-appbar">
        <div className="k-appbar__icon">
          <svg viewBox="0 0 20 20" fill="none"><path d="M3 6 H 17 M3 10 H 17 M3 14 H 17" stroke="currentColor" strokeLinecap="round" /></svg>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 8, flex: 1 }}>
          <Koru size={22} />
          <div style={{ display: "flex", flexDirection: "column" }}>
            <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
              <span style={{ fontSize: 14, color: "var(--k-text-strong)" }}>Gemma 4 E2B</span>
              <span className="k-mono" style={{ fontSize: 10, color: "var(--k-text-muted)" }}>· local</span>
              <span style={{ fontSize: 10, color: "var(--k-text-muted)" }}>▾</span>
            </div>
            <div className="k-mono" style={{ fontSize: 10, color: "var(--k-text-muted)", letterSpacing: "0.05em", marginTop: 1 }}>
              shed wiring
            </div>
          </div>
        </div>
        <div className="k-appbar__icon"><svg viewBox="0 0 20 20" fill="none"><circle cx="10" cy="4" r="1.4" fill="currentColor" /><circle cx="10" cy="10" r="1.4" fill="currentColor" /><circle cx="10" cy="16" r="1.4" fill="currentColor" /></svg></div>
      </div>

      {/* messages */}
      <div style={{ padding: "18px 18px 0", display: "flex", flexDirection: "column", gap: 14, height: 615, overflow: "hidden" }}>

        {/* model — first */}
        <ModelBubble>
          Kia ora. What are we working on this evening?
        </ModelBubble>

        {/* user */}
        <UserBubble>
          The 16 A circuit in the new shed — can you check if 2.5 mm² T+E is enough for a 12 m run if I'm pulling about 12 A continuous?
        </UserBubble>

        {/* model — tool-acting */}
        <ToolStripe label="reading · NZS 3000 cable tables" />

        <ModelBubble>
          At 12 A continuous over 12 m, 2.5 mm² T+E gives roughly{" "}
          <span className="k-mono" style={{ color: "var(--k-koura)" }}>2.0 % volt-drop</span>{" "}
          on a 230 V circuit — inside the 5 % limit. Current capacity is fine for 16 A protection.
        </ModelBubble>

        {/* streaming model bubble */}
        <ModelBubble streaming>
          One thing to check — if you're running it in insulated wall cavities
        </ModelBubble>
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

function ModelBubble({ children, streaming = false }) {
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
              boxShadow: "0 0 12px rgba(217,119,66,0.7)",
              animation: "kPulse 1.2s ease-in-out infinite",
            }} />
          </span>
        )}
      </div>
    </div>
  );
}

function UserBubble({ children }) {
  return (
    <div style={{ display: "flex", alignSelf: "flex-end", maxWidth: "82%" }}>
      <div style={{
        background: "rgba(193, 39, 45, 0.14)",
        border: "1px solid rgba(193, 39, 45, 0.35)",
        borderRadius: "14px 14px 4px 14px",
        padding: "11px 14px",
        fontSize: 14, lineHeight: 1.45, color: "var(--k-text-strong)",
      }}>
        {children}
      </div>
    </div>
  );
}

function ToolStripe({ label }) {
  return (
    <div style={{
      alignSelf: "flex-start",
      display: "inline-flex", alignItems: "center", gap: 8,
      padding: "6px 12px",
      borderRadius: 8,
      background: "rgba(217, 119, 66, 0.08)",
      border: "1px solid rgba(217, 119, 66, 0.35)",
    }}>
      <div style={{ width: 5, height: 5, borderRadius: 3, background: "var(--k-ember)" }} />
      <span className="k-mono" style={{ fontSize: 10.5, color: "var(--k-ember)", letterSpacing: "0.08em", textTransform: "uppercase" }}>{label}</span>
    </div>
  );
}

Object.assign(window, { FrameDailyTodo, FrameChat });
