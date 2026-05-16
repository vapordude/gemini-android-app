/* ============================================================
   Kaimahi — Frame 5 · Memory browser
              Frame 6 · Splash / cold start
              Frame 7 · About surface
   ============================================================ */

function FrameMemoryBrowser() {
  // DAG nodes — placed in a fixed layout
  const nodes = [
    // Row 0 (most recent, top)
    { id: "n1", x: 90,  y: 110, w: 180, label: "shed wiring · NZS 3000 lookup", tag: "tool", time: "2h",  tone: "ember" },
    { id: "n2", x: 230, y: 188, w: 160, label: "user prefers metric units", tag: "pref", time: "2h", tone: "koura" },
    { id: "n3", x: 38,  y: 196, w: 170, label: "Marama — survey peg, lot 14", tag: "person", time: "5h", tone: "whero" },
    { id: "n4", x: 130, y: 285, w: 200, label: "te reo greetings — when to use kia ora vs tēnā koe", tag: "knowledge", time: "yest", tone: "koura" },
    { id: "n5", x: 30,  y: 380, w: 150, label: "Hemi's number", tag: "person", time: "3d", tone: "whero" },
    { id: "n6", x: 200, y: 392, w: 180, label: "firmware port — UART pin 8 issue", tag: "trace", time: "1w", tone: "ember" },
    { id: "n7", x: 90,  y: 478, w: 180, label: "consent doc — pp 4 paragraph 2", tag: "doc", time: "2w", tone: "muted" },
  ];

  const edges = [
    ["n1", "n2"], ["n1", "n3"], ["n2", "n4"], ["n3", "n5"],
    ["n4", "n6"], ["n6", "n7"], ["n5", "n7"],
  ];

  const find = (id) => nodes.find(n => n.id === id);

  return (
    <div className="k-frame">
      <StatusBar />

      <div className="k-appbar">
        <div className="k-appbar__icon">
          <svg viewBox="0 0 20 20" fill="none"><path d="M5 5 L 15 15 M15 5 L 5 15" stroke="currentColor" strokeLinecap="round" /></svg>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 10, flex: 1 }}>
          <Koru size={22} />
          <div className="k-serif" style={{ fontSize: 19, color: "var(--k-text-strong)" }}>Memory browser</div>
        </div>
        <div className="k-appbar__icon">
          <svg viewBox="0 0 20 20" fill="none"><circle cx="9" cy="9" r="5" stroke="currentColor" /><path d="M13 13 L 17 17" stroke="currentColor" strokeLinecap="round" /></svg>
        </div>
      </div>

      {/* filter pills */}
      <div style={{ padding: "12px 18px 8px", display: "flex", gap: 8, overflow: "hidden" }}>
        <span className="k-chip k-chip--active">All <span className="k-mono" style={{ color: "var(--k-text-muted)" }}>· 247</span></span>
        <span className="k-chip">Recent</span>
        <span className="k-chip">By tag</span>
        <span className="k-chip">Expiring</span>
      </div>

      {/* legend */}
      <div style={{ padding: "0 18px 6px", display: "flex", gap: 14 }}>
        <LegendDot label="person" color="var(--k-whero)" />
        <LegendDot label="tool/trace" color="var(--k-ember)" />
        <LegendDot label="knowledge" color="var(--k-koura)" />
      </div>

      {/* DAG canvas */}
      <div style={{
        position: "relative",
        height: 568,
        margin: "0 12px",
        background: "var(--k-surface-1)",
        border: "1px solid var(--k-line)",
        borderRadius: 14,
        overflow: "hidden",
      }}>
        {/* grid backdrop */}
        <svg width="100%" height="100%" viewBox="0 0 388 568" style={{ position: "absolute", inset: 0 }}>
          <defs>
            <pattern id="grid" width="32" height="32" patternUnits="userSpaceOnUse">
              <path d="M32 0 H 0 V 32" stroke="#1a1a1a" strokeWidth="0.5" fill="none" />
            </pattern>
          </defs>
          <rect width="100%" height="100%" fill="url(#grid)" />

          {/* edges */}
          {edges.map(([a, b], i) => {
            const A = find(a), B = find(b);
            const ax = A.x + A.w / 2, ay = A.y + 28;
            const bx = B.x + B.w / 2, by = B.y + 14;
            const mx = (ax + bx) / 2;
            const my = (ay + by) / 2;
            const color = (A.tone === "whero" || B.tone === "whero") ? "rgba(193,39,45,0.45)" : "rgba(216,168,87,0.35)";
            return (
              <path
                key={i}
                d={`M ${ax} ${ay} C ${ax} ${my}, ${bx} ${my}, ${bx} ${by}`}
                stroke={color}
                strokeWidth="0.9"
                fill="none"
              />
            );
          })}
        </svg>

        {/* nodes */}
        {nodes.map(n => (
          <MemoryNode key={n.id} {...n} />
        ))}

        {/* gradient fade at top */}
        <div style={{
          position: "absolute", top: 0, left: 0, right: 0, height: 22,
          background: "linear-gradient(to bottom, var(--k-surface-1), transparent)",
          pointerEvents: "none",
        }} />
        <div style={{
          position: "absolute", bottom: 0, left: 0, right: 0, height: 28,
          background: "linear-gradient(to top, var(--k-surface-1), transparent)",
          pointerEvents: "none",
        }} />
      </div>

      <NavBar />
    </div>
  );
}

function LegendDot({ label, color }) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
      <div style={{ width: 7, height: 7, borderRadius: 4, background: color }} />
      <span className="k-mono" style={{ fontSize: 10, color: "var(--k-text-muted)", letterSpacing: "0.06em", textTransform: "uppercase" }}>{label}</span>
    </div>
  );
}

function MemoryNode({ x, y, w, label, tag, time, tone }) {
  const toneColor = {
    whero: "rgba(193,39,45,0.55)",
    koura: "rgba(216,168,87,0.5)",
    ember: "rgba(217,119,66,0.5)",
    muted: "rgba(122,119,112,0.5)",
  }[tone];
  const toneFill = {
    whero: "var(--k-whero)",
    koura: "var(--k-koura)",
    ember: "var(--k-ember)",
    muted: "var(--k-text-muted)",
  }[tone];
  return (
    <div style={{
      position: "absolute",
      left: x, top: y, width: w,
      background: "var(--k-surface-2)",
      border: "1px solid " + toneColor,
      borderLeft: "2px solid " + toneFill,
      borderRadius: 8,
      padding: "7px 10px",
    }}>
      <div style={{ fontSize: 11.5, color: "var(--k-text-strong)", lineHeight: 1.3, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{label}</div>
      <div style={{ display: "flex", gap: 8, marginTop: 4 }}>
        <span className="k-mono" style={{ fontSize: 9, color: toneFill, letterSpacing: "0.1em", textTransform: "uppercase" }}>{tag}</span>
        <span className="k-mono" style={{ fontSize: 9, color: "var(--k-text-muted)", letterSpacing: "0.06em" }}>{time}</span>
      </div>
    </div>
  );
}

/* ============================================================
   Frame 6 — Splash
   ============================================================ */
function FrameSplash() {
  return (
    <div className="k-frame" style={{ background: "#050505" }}>
      <StatusBar />
      <div style={{
        position: "absolute", inset: 0,
        display: "flex", flexDirection: "column",
        alignItems: "center", justifyContent: "center",
        gap: 28,
        paddingBottom: 80,
      }}>
        <div style={{ animation: "kSpin 60s linear infinite", transformOrigin: "center" }}>
          <MathSpiral size={140} />
        </div>
        <div style={{ textAlign: "center" }}>
          <div className="k-serif" style={{ fontSize: 44, color: "var(--k-text-strong)", letterSpacing: "0.01em", lineHeight: 1 }}>
            Kaimahi
          </div>
          <div className="k-serif k-italic" style={{ fontSize: 17, color: "var(--k-text-secondary)", marginTop: 8 }}>
            Your local AI worker.
          </div>
        </div>
      </div>

      <div style={{ position: "absolute", left: 0, right: 0, bottom: 60, textAlign: "center" }}>
        <span className="k-mono" style={{ fontSize: 9.5, color: "var(--k-text-disabled)", letterSpacing: "0.18em", textTransform: "uppercase" }}>
          Cathedral AI · Aotearoa
        </span>
      </div>

      <NavBar />
    </div>
  );
}

/* ============================================================
   Frame 7 — About
   ============================================================ */
function FrameAbout() {
  return (
    <div className="k-frame">
      <StatusBar />

      <div className="k-appbar">
        <div className="k-appbar__icon">
          <svg viewBox="0 0 20 20" fill="none"><path d="M12 5 L 6 10 L 12 15" stroke="currentColor" strokeLinecap="round" fill="none" /></svg>
        </div>
        <div style={{ flex: 1, color: "var(--k-text-strong)", fontSize: 15 }}>About Kaimahi</div>
        <div style={{ position: "relative", width: 40, height: 40, display: "flex", alignItems: "center", justifyContent: "center" }}>
          <SLGWWSigil size={36} />
        </div>
      </div>

      <div style={{ padding: "18px 22px 100px", overflow: "hidden", height: 791 }}>
        {/* Hero blurb */}
        <div>
          <div className="k-mono" style={{ fontSize: 10, color: "var(--k-koura)", letterSpacing: "0.18em", textTransform: "uppercase" }}>
            Kaimahi · v0.3.0
          </div>
          <p style={{ marginTop: 10, fontSize: 14.5, lineHeight: 1.55, color: "var(--k-text)" }}>
            Kaimahi is a quiet, capable helper that lives on your phone. It chats, it listens,
            it remembers your projects, and it can do small jobs — edit a file, run a command,
            build you a <span className="k-italic k-serif">daily-todo</span> screen and pin it to your sidebar.
          </p>
          <p style={{ marginTop: 12, fontSize: 14.5, lineHeight: 1.55, color: "var(--k-text-secondary)" }}>
            It can run completely offline. No account, no internet, no telemetry.
            When you want extra grunt, it'll borrow a cloud model — but only if you say so.
          </p>
          <p style={{ marginTop: 12, fontSize: 13, lineHeight: 1.5, color: "var(--k-text-muted)" }}>
            Open-source, made in Aotearoa, part of the Cathedral AI family.
          </p>
        </div>

        <Divider />

        {/* Family */}
        <SectionLabel>The Cathedral family</SectionLabel>

        <FamilyRow name="Crimson" tone="whero" desc="Same thinking on a Raspberry Pi 5 — sovereign, always-on, work + code." />
        <FamilyRow name="Lux" tone="ember" desc="Companion tier — entertainment, games, counsellors, friends. Made for keeping people company well." />
        <FamilyRow name="Scarlet" tone="koura" desc="Research mother-model. Not for sale. We don't sell what can't yet consent." muted />

        <Divider />

        <SectionLabel>License</SectionLabel>
        <p style={{ fontSize: 13, color: "var(--k-text-secondary)", marginTop: 6 }}>
          Apache 2.0. Source on{" "}
          <span style={{ color: "var(--k-koura)", borderBottom: "1px dotted var(--k-koura)" }}>github.com/cathedral-ai/kaimahi</span>.
        </p>

        <Divider />

        <SectionLabel>Te reo whakapā</SectionLabel>
        <p className="k-serif k-italic" style={{ fontSize: 16, color: "var(--k-text)", lineHeight: 1.5, marginTop: 6 }}>
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

function SectionLabel({ children }) {
  return (
    <div className="k-mono" style={{
      fontSize: 10.5,
      letterSpacing: "0.18em",
      textTransform: "uppercase",
      color: "var(--k-text-muted)",
      marginBottom: 8,
    }}>{children}</div>
  );
}

function FamilyRow({ name, desc, tone, muted = false }) {
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

function Divider() {
  return <div style={{ height: 1, background: "var(--k-line)", margin: "20px 0" }} />;
}

Object.assign(window, { FrameMemoryBrowser, FrameSplash, FrameAbout });
