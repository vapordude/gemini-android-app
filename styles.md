:root {
  --ais-bg: #191919;
  --ais-panel: rgba(31, 31, 31, 0.6);
  --ais-panel-solid: #232323;
  --ais-line: #2a2a2a;
  --ais-line-strong: #3e3e3e;
  --ais-text: #d4d4d4;
  --ais-muted: #8c8c8c;
  --ais-chip: #323232;
  --ais-chip-hover: #3c3c3c;
  --ais-radius-xl: 32px;
  --ais-radius-lg: 16px;
  --ais-radius-md: 12px;
  --ais-radius-sm: 9px;
}

html,
body {
  margin: 0;
  background: var(--ais-bg);
  color: var(--ais-text);
  font-family: Inter, system-ui, -apple-system, BlinkMacSystemFont, sans-serif;
}

.ais-shell {
  position: relative;
  min-height: 100vh;
  overflow: hidden;
  background: var(--ais-bg);
}

.ais-shell::before {
  content: "";
  position: absolute;
  inset: 0;
  pointer-events: none;
  opacity: 0.45;
  background-image:
    linear-gradient(to right, rgba(255,255,255,0.045) 1px, transparent 1px),
    linear-gradient(to bottom, rgba(255,255,255,0.045) 1px, transparent 1px),
    radial-gradient(circle at 20% 10%, rgba(255,255,255,0.05), transparent 22%),
    radial-gradient(circle at 80% 18%, rgba(255,255,255,0.04), transparent 18%);
  background-size: 28px 28px, 28px 28px, 100% 100%, 100% 100%;
  mask-image: linear-gradient(180deg, #fff 0%, transparent 72%);
}

.ais-container {
  position: relative;
  z-index: 1;
  width: min(1200px, calc(100% - 40px));
  margin: 0 auto;
}

.ais-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 0;
}

.ais-nav {
  display: flex;
  align-items: center;
  gap: 24px;
  color: var(--ais-muted);
  font-size: 14px;
}

.ais-hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: 64px 0 40px;
}

.ais-title {
  margin: 0;
  color: #f2f2f2;
  font-family: "Google Sans Flex", "Google Sans Text", Inter, sans-serif;
  font-size: clamp(42px, 5vw, 72px);
  line-height: 0.96;
  font-weight: 500;
  letter-spacing: -0.04em;
}

.ais-subtitle {
  margin-top: 14px;
  color: var(--ais-muted);
  font-family: "Inter Tight", Inter, sans-serif;
  font-size: clamp(18px, 2vw, 28px);
  font-weight: 300;
  line-height: 1.2;
}

.ais-hero-media {
  width: min(980px, 100%);
  margin-top: 36px;
  border-radius: 28px;
  background: #000;
  overflow: hidden;
  box-shadow: 0 0 0 1px rgba(255,255,255,0.03);
}

.ais-hero-media video,
.ais-hero-media img {
  display: block;
  width: 100%;
  height: auto;
}

.ais-actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  justify-content: center;
  margin-top: 28px;
}

.ais-btn-large {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  height: 40px;
  padding: 0 16px;
  border-radius: var(--ais-radius-lg);
  border: 1px solid var(--ais-line-strong);
  background: transparent;
  color: var(--ais-text);
  text-decoration: none;
  font-size: 14px;
  transition: background-color 160ms ease, border-color 160ms ease, opacity 160ms ease;
}

.ais-btn-large:hover {
  background: rgba(255,255,255,0.03);
}

.ais-btn-small {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 36px;
  padding: 0 12px;
  border-radius: var(--ais-radius-md);
  border: 1px solid var(--ais-line-strong);
  background: var(--ais-chip);
  color: var(--ais-text);
  text-decoration: none;
  font-size: 14px;
  opacity: 0.92;
  transition: background-color 160ms ease, opacity 160ms ease;
}

.ais-btn-small:hover {
  background: var(--ais-chip-hover);
  opacity: 1;
}

.ais-divider {
  border: 0;
  border-top: 1px solid var(--ais-line);
  margin: 80px 0;
}

.ais-feature-list {
  display: flex;
  flex-wrap: wrap;
  gap: 24px 16px;
}

.ais-feature-card {
  display: flex;
  flex: 1 1 100%;
  flex-direction: row;
  justify-content: space-between;
  gap: 24px;
  background: transparent;
  border: 0;
  margin-bottom: 120px;
}

.ais-feature-copy {
  max-width: 400px;
  padding: 70px 45px 0 0;
}

.ais-feature-tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 20px;
  color: var(--ais-muted);
  font-family: "Inter Tight", Inter, sans-serif;
  font-size: 16px;
  font-weight: 300;
}

.ais-feature-title {
  margin: 0 0 12px;
  color: var(--ais-text);
  font-family: "Inter Tight", Inter, sans-serif;
  font-size: 28px;
  font-weight: 300;
}

.ais-feature-description {
  margin: 0 0 20px;
  color: var(--ais-muted);
  font-size: 16px;
  line-height: 1.5;
}

.ais-feature-media {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  max-width: 500px;
  width: 100%;
}

.ais-feature-video {
  width: 100%;
  max-height: 500px;
  aspect-ratio: 1 / 1;
  object-fit: cover;
  border-radius: var(--ais-radius-xl);
}

.ais-code-card {
  display: grid;
  grid-template-columns: 1fr 1.3fr;
  gap: 24px;
}

.ais-code-panel {
  background: rgba(31,31,31,0.7);
  border: 1px solid var(--ais-line);
  border-radius: 24px;
  padding: 24px;
}

.ais-code-title {
  margin: 0 0 8px;
  font-family: "Inter Tight", Inter, sans-serif;
  font-size: 24px;
  font-weight: 300;
}

.ais-code-muted {
  color: var(--ais-muted);
  font-size: 16px;
  line-height: 1.5;
}
