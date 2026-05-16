/* ============================================================
   Kaimahi — Frame 1 · App shell with drawer open
   Frame 2 · Project list inside drawer (variant on shell)
   ============================================================ */

function StatusBar({ time = "21:14" }) {
  return (
    <div className="k-statusbar">
      <span>{time}</span>
      <div className="k-statusbar__right" style={{ color: "currentColor" }}>
        <svg width="11" height="11" viewBox="0 0 12 12" fill="none">
          <path d="M1 8 L4 5 L7 7 L11 2" stroke="currentColor" strokeWidth="1" fill="none" strokeLinecap="round" />
        </svg>
        <svg width="12" height="11" viewBox="0 0 14 12" fill="none">
          <path d="M7 2 C 4 2 2 4 1 6 M7 4 C 5 4 4 5 3 6 M7 6 C 6 6 6 6.5 5.5 7" stroke="currentColor" strokeWidth="1" fill="none" strokeLinecap="round" />
          <circle cx="7" cy="9.5" r="0.8" fill="currentColor" />
        </svg>
        <svg width="20" height="10" viewBox="0 0 22 11" fill="none">
          <rect x="0.5" y="0.5" width="19" height="10" rx="1.5" stroke="currentColor" />
          <rect x="2" y="2" width="13" height="7" rx="0.5" fill="currentColor" />
          <rect x="20" y="3.5" width="1.5" height="4" rx="0.5" fill="currentColor" />
        </svg>
      </div>
    </div>
  );
}

function NavBar() { return <div className="k-navbar"></div>; }

/* ---------- Drawer chrome shared by Frames 1 & 2 ---------- */
function DrawerHeader() {
  return (
    <div style={{ padding: "20px 20px 14px", borderBottom: "1px solid var(--k-line)" }}>
      <div style={{ display: "flex", alignItems: "center", gap: 14 }}>
        <Koru size={44} />
        <div>
          <div className="k-serif" style={{ fontSize: 26, color: "var(--k-text-strong)", lineHeight: 1, letterSpacing: "0.01em" }}>
            Kaimahi
          </div>
          <div className="k-mono" style={{ fontSize: 9.5, color: "var(--k-text-muted)", letterSpacing: "0.14em", textTransform: "uppercase", marginTop: 6 }}>
            Your local AI worker
          </div>
        </div>
      </div>
    </div>
  );
}

function NewChatPill() {
  return (
    <div style={{ padding: "16px 20px 8px" }}>
      <button className="k-pill k-pill--whero" style={{ width: "100%", justifyContent: "flex-start", paddingLeft: 18 }}>
        <span style={{ fontSize: 18, fontWeight: 300, marginRight: 4 }}>+</span>
        New chat
      </button>
    </div>
  );
}

function DrawerItem({ label, icon, hint, active = false, dim = false }) {
  return (
    <div style={{
      display: "flex",
      alignItems: "center",
      gap: 14,
      padding: "12px 22px",
      borderRadius: 0,
      color: dim ? "var(--k-text-muted)" : (active ? "var(--k-text-strong)" : "var(--k-text)"),
      background: active ? "var(--k-surface-2)" : "transparent",
      borderLeft: active ? "2px solid var(--k-whero)" : "2px solid transparent",
    }}>
      <span style={{ width: 20, height: 20, color: dim ? "var(--k-text-muted)" : (active ? "var(--k-whero)" : "var(--k-text-secondary)") }}>{icon}</span>
      <span style={{ fontSize: 15, flex: 1 }}>{label}</span>
      {hint && <span className="k-mono" style={{ fontSize: 10, color: "var(--k-text-muted)", letterSpacing: "0.04em" }}>{hint}</span>}
    </div>
  );
}

/* tiny inline icons — geometric, no detail */
const ico = {
  todo: <svg viewBox="0 0 20 20" fill="none"><rect x="3" y="3" width="14" height="14" rx="2" stroke="currentColor" /><path d="M6 10 l3 3 l5 -6" stroke="currentColor" strokeLinecap="round" /></svg>,
  memory: <svg viewBox="0 0 20 20" fill="none"><circle cx="10" cy="6" r="2.5" stroke="currentColor" /><circle cx="5" cy="14" r="2.5" stroke="currentColor" /><circle cx="15" cy="14" r="2.5" stroke="currentColor" /><path d="M10 8.5 L5 11.5 M10 8.5 L15 11.5" stroke="currentColor" /></svg>,
  trace: <svg viewBox="0 0 20 20" fill="none"><path d="M3 16 L8 9 L11 13 L17 4" stroke="currentColor" strokeLinecap="round" /><circle cx="8" cy="9" r="1.2" fill="currentColor" /><circle cx="11" cy="13" r="1.2" fill="currentColor" /></svg>,
  deploy: <svg viewBox="0 0 20 20" fill="none"><path d="M10 3 L16 7 V 13 L10 17 L4 13 V 7 Z" stroke="currentColor" /><path d="M10 3 V 10 M4 7 L10 10 L16 7" stroke="currentColor" /></svg>,
  model: <svg viewBox="0 0 20 20" fill="none"><rect x="3" y="6" width="14" height="9" rx="1.5" stroke="currentColor" /><circle cx="7" cy="10.5" r="1" fill="currentColor" /><path d="M10 11 H 15" stroke="currentColor" strokeLinecap="round" /><path d="M10 13 H 13" stroke="currentColor" strokeLinecap="round" /></svg>,
  settings: <svg viewBox="0 0 20 20" fill="none"><circle cx="10" cy="10" r="2.4" stroke="currentColor" /><path d="M10 3 V 5 M10 15 V 17 M3 10 H 5 M15 10 H 17 M5 5 L 6.5 6.5 M13.5 13.5 L 15 15 M5 15 L 6.5 13.5 M13.5 6.5 L 15 5" stroke="currentColor" strokeLinecap="round" /></svg>,
  chat: <svg viewBox="0 0 20 20" fill="none"><path d="M3 5 H 17 V 13 H 11 L 7 16 V 13 H 3 Z" stroke="currentColor" /></svg>,
  archive: <svg viewBox="0 0 20 20" fill="none"><rect x="3" y="4" width="14" height="3" stroke="currentColor" /><path d="M4 7 V 16 H 16 V 7" stroke="currentColor" /><path d="M8 10 H 12" stroke="currentColor" strokeLinecap="round" /></svg>,
};

function DrawerFooter() {
  return (
    <div style={{
      padding: "16px 20px 22px",
      borderTop: "1px solid var(--k-line)",
      display: "flex",
      alignItems: "flex-end",
      justifyContent: "space-between",
    }}>
      <div>
        <div className="k-mono" style={{ fontSize: 10, color: "var(--k-text-muted)", letterSpacing: "0.08em" }}>v0.3.0</div>
        <div className="k-mono" style={{ fontSize: 10, color: "var(--k-text-disabled)", letterSpacing: "0.12em", textTransform: "uppercase", marginTop: 3 }}>Cathedral AI</div>
      </div>
      <SLGWWSigil size={22} opacity={0.3} />
    </div>
  );
}

/* ---------- Frame 1 ---------- */
function FrameAppShell() {
  return (
    <div className="k-frame">
      {/* dimmed chat behind */}
      <div style={{ position: "absolute", inset: 0, background: "var(--k-bg)" }}>
        <StatusBar />
        <div className="k-appbar">
          <div className="k-appbar__icon">
            <svg viewBox="0 0 20 20" fill="none"><path d="M3 6 H 17 M3 10 H 17 M3 14 H 17" stroke="currentColor" strokeLinecap="round" /></svg>
          </div>
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 15, color: "var(--k-text-secondary)" }}>
              <span className="k-serif" style={{ fontSize: 18, color: "var(--k-text)" }}>shed wiring</span>
            </div>
            <div className="k-mono" style={{ fontSize: 10, color: "var(--k-text-muted)", letterSpacing: "0.06em", marginTop: 2 }}>
              GEMMA 4 E2B · LOCAL
            </div>
          </div>
        </div>
        {/* faint silhouette of message bubbles */}
        <div style={{ padding: 20, opacity: 0.4 }}>
          <div style={{ background: "var(--k-surface-1)", borderRadius: 14, padding: "12px 14px", maxWidth: "75%", marginBottom: 14 }}>
            <div style={{ height: 9, background: "var(--k-line)", borderRadius: 3, marginBottom: 8 }} />
            <div style={{ height: 9, background: "var(--k-line)", borderRadius: 3, width: "80%" }} />
          </div>
          <div style={{ background: "rgba(193,39,45,0.12)", border: "1px solid rgba(193,39,45,0.25)", borderRadius: 14, padding: "12px 14px", maxWidth: "70%", marginLeft: "auto" }}>
            <div style={{ height: 9, background: "rgba(193,39,45,0.3)", borderRadius: 3, width: "70%" }} />
          </div>
        </div>
      </div>

      {/* scrim */}
      <div className="k-scrim"></div>

      {/* drawer */}
      <div className="k-drawer">
        <StatusBar />
        <DrawerHeader />
        <NewChatPill />

        <div style={{ padding: "10px 22px 6px", display: "flex", alignItems: "center", justifyContent: "space-between" }}>
          <div className="k-section-label">Projects</div>
          <div className="k-mono" style={{ fontSize: 10, color: "var(--k-text-muted)" }}>4 active</div>
        </div>

        <div style={{ padding: "0 14px 6px" }}>
          <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
            <ProjectMini name="shed wiring" when="2 hours ago" count={47} active />
            <ProjectMini name="te reo notes" when="yesterday" count={12} />
            <ProjectMini name="kōrero with Hemi" when="3 days ago" count={8} />
            <ProjectMini name="firmware port" when="last week" count={211} />
          </div>
          <div style={{ display: "flex", alignItems: "center", gap: 8, padding: "10px 8px 4px", color: "var(--k-text-muted)", fontSize: 13 }}>
            <span style={{ fontSize: 10 }}>▸</span>
            <span className="k-mono" style={{ fontSize: 11, letterSpacing: "0.08em", textTransform: "uppercase" }}>Archived (12)</span>
          </div>
        </div>

        <div style={{ flex: 1, overflow: "hidden" }}>
          <div className="k-section-label" style={{ padding: "12px 22px 6px" }}>Tools</div>
          <DrawerItem label="Daily todo" icon={ico.todo} hint="3" active />
          <DrawerItem label="Memory browser" icon={ico.memory} />
          <DrawerItem label="Trace viewer" icon={ico.trace} />
          <DrawerItem label="Deployments" icon={ico.deploy} />
          <DrawerItem label="Model picker" icon={ico.model} hint="local" />
          <DrawerItem label="Settings" icon={ico.settings} />
        </div>

        <DrawerFooter />
      </div>

      <NavBar />
    </div>
  );
}

function ProjectMini({ name, when, count, active = false }) {
  return (
    <div style={{
      display: "flex", alignItems: "center", gap: 10,
      padding: "10px 12px",
      background: active ? "var(--k-surface-2)" : "transparent",
      border: "1px solid " + (active ? "var(--k-line-strong)" : "transparent"),
      borderRadius: 10,
    }}>
      <div style={{ width: 6, height: 6, borderRadius: 6, background: active ? "var(--k-whero)" : "var(--k-text-disabled)" }} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 14, color: "var(--k-text)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{name}</div>
        <div className="k-mono" style={{ fontSize: 10, color: "var(--k-text-muted)", letterSpacing: "0.04em", marginTop: 1 }}>{when}</div>
      </div>
      <div className="k-mono" style={{
        minWidth: 22, height: 22, padding: "0 7px",
        borderRadius: 11,
        background: "rgba(216, 168, 87, 0.14)",
        color: "var(--k-koura)",
        fontSize: 11, fontWeight: 600,
        display: "inline-flex", alignItems: "center", justifyContent: "center",
        letterSpacing: 0,
      }}>{count}</div>
    </div>
  );
}

/* ---------- Frame 2 — drawer focused on projects ---------- */
function FrameProjectList() {
  return (
    <div className="k-frame">
      <div style={{ position: "absolute", inset: 0, background: "var(--k-bg)" }} />
      <div className="k-scrim"></div>

      <div className="k-drawer">
        <StatusBar />
        <DrawerHeader />

        <div style={{ padding: "14px 20px 8px", display: "flex", alignItems: "center", justifyContent: "space-between" }}>
          <div className="k-section-label">Projects</div>
          <div className="k-mono" style={{ fontSize: 10, color: "var(--k-text-muted)" }}>4 active · 12 archived</div>
        </div>

        <div style={{ padding: "0 14px", flex: 1, overflow: "hidden" }}>
          <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
            <ProjectCard name="shed wiring" when="2 hours ago" count={47} active swipe />
            <ProjectCard name="te reo notes" when="yesterday" count={12} />
            <ProjectCard name="kōrero with Hemi" when="3 days ago" count={8} />
            <ProjectCard name="firmware port — Pi 5" when="last week" count={211} />
          </div>

          {/* Archived expanded */}
          <div style={{ display: "flex", alignItems: "center", gap: 8, padding: "18px 8px 8px", color: "var(--k-text-secondary)" }}>
            <span style={{ fontSize: 10, color: "var(--k-text-muted)", transform: "rotate(90deg)", display: "inline-block" }}>▸</span>
            <span className="k-mono" style={{ fontSize: 11, letterSpacing: "0.08em", textTransform: "uppercase" }}>Archived</span>
            <span className="k-mono" style={{ fontSize: 11, color: "var(--k-text-muted)" }}>12</span>
          </div>
          <div style={{ display: "flex", flexDirection: "column", gap: 6, opacity: 0.6 }}>
            <ProjectCard name="solar inverter spec" when="2 weeks ago" count={34} compact />
            <ProjectCard name="garden roster" when="3 weeks ago" count={5} compact />
            <ProjectCard name="reading log — Frame" when="4 weeks ago" count={18} compact />
          </div>
        </div>

        <NewChatPill />
        <DrawerFooter />
      </div>
      <NavBar />
    </div>
  );
}

function ProjectCard({ name, when, count, active = false, swipe = false, compact = false }) {
  return (
    <div style={{
      position: "relative",
      background: active ? "var(--k-surface-2)" : "var(--k-surface-1)",
      border: "1px solid " + (active ? "var(--k-line-strong)" : "var(--k-line)"),
      borderRadius: 12,
      padding: compact ? "10px 12px" : "12px 14px",
      overflow: "hidden",
    }}>
      {active && <div style={{ position: "absolute", left: 0, top: 0, bottom: 0, width: 3, background: "var(--k-whero)" }} />}

      <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: compact ? 13.5 : 15, color: "var(--k-text-strong)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{name}</div>
          <div className="k-mono" style={{ fontSize: 10.5, color: "var(--k-text-muted)", letterSpacing: "0.05em", marginTop: 3 }}>{when}</div>
        </div>
        <div className="k-mono" style={{
          minWidth: 26, padding: "3px 8px",
          borderRadius: 11,
          background: "rgba(216, 168, 87, 0.14)",
          color: "var(--k-koura)",
          fontSize: 11, fontWeight: 600,
          textAlign: "center",
        }}>{count}</div>
      </div>

      {swipe && (
        <div style={{
          marginTop: 10, paddingTop: 10,
          borderTop: "1px dashed var(--k-line-strong)",
          display: "flex", gap: 8,
        }}>
          <SwipeAction label="Rename" />
          <SwipeAction label="Archive" />
          <SwipeAction label="Delete" danger />
        </div>
      )}
    </div>
  );
}

function SwipeAction({ label, danger = false }) {
  return (
    <div className="k-mono" style={{
      fontSize: 10, letterSpacing: "0.1em", textTransform: "uppercase",
      padding: "5px 10px", borderRadius: 6,
      border: "1px solid " + (danger ? "rgba(193,39,45,0.5)" : "var(--k-line-strong)"),
      color: danger ? "var(--k-whero)" : "var(--k-text-secondary)",
    }}>{label}</div>
  );
}

Object.assign(window, { FrameAppShell, FrameProjectList, StatusBar, NavBar });
