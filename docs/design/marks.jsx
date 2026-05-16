/* ============================================================
   Kaimahi — brand marks
   All math-derived, no decorative illustration.

   Koru          — logarithmic spiral r = a·e^(b·θ)
   MathSpiral    — 8 angular petals + radial circuit traces
   SLGWWSigil    — heraldic circle: vertical bar, diamond gem,
                   four corner dots
   ============================================================ */

/* ---------- Koru spiral ---------- */
/* Sample r = a·e^(b·θ) into a polyline, then ribbon it via stroke. */
function koruPath({ a = 0.35, b = 0.245, turns = 2.6, samplesPerTurn = 90, cx = 0, cy = 0, rotate = 0 } = {}) {
  const total = Math.round(turns * samplesPerTurn);
  const pts = [];
  const phi = (rotate * Math.PI) / 180;
  for (let i = 0; i <= total; i++) {
    const theta = (i / samplesPerTurn) * Math.PI * 2;
    const r = a * Math.exp(b * theta);
    const x = cx + Math.cos(theta + phi) * r;
    const y = cy + Math.sin(theta + phi) * r;
    pts.push([x, y]);
  }
  return pts.map(([x, y], i) => `${i ? 'L' : 'M'}${x.toFixed(3)} ${y.toFixed(3)}`).join(' ');
}

function Koru({ size = 48, gradient = true, stroke = '#C1272D', strokeWidth, id = 'koru' }) {
  const sw = strokeWidth ?? Math.max(1.5, size / 28);
  // Spiral grows outward; centre it inside a 24-unit viewbox.
  const inner = koruPath({ a: 0.4, b: 0.235, turns: 2.7 });
  return (
    <svg width={size} height={size} viewBox="-12 -12 24 24" fill="none" aria-hidden="true">
      {gradient && (
        <defs>
          <linearGradient id={`${id}-g`} x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor="#C1272D" />
            <stop offset="55%" stopColor="#D97742" />
            <stop offset="100%" stopColor="#D8A857" />
          </linearGradient>
        </defs>
      )}
      <path
        d={inner}
        stroke={gradient ? `url(#${id}-g)` : stroke}
        strokeWidth={sw}
        strokeLinecap="round"
      />
    </svg>
  );
}

/* ---------- Math-spiral (8-petal) ---------- */
function MathSpiral({ size = 128, id = 'mspiral' }) {
  // 8 angular "petals": each petal is a kite-shape rotated around centre.
  const petals = [];
  for (let i = 0; i < 8; i++) {
    const a = (i * 45 * Math.PI) / 180;
    const cos = Math.cos(a), sin = Math.sin(a);
    // Kite vertices in local axis (along +x outward)
    const v = [
      [0.0, 0.0],
      [3.4, -2.0],
      [10.0, 0.0],
      [3.4, 2.0],
    ].map(([x, y]) => [x * cos - y * sin, x * sin + y * cos]);
    petals.push(v);
  }
  // Radial circuit traces — short kōura ticks fanning out at each midline
  const traces = [];
  for (let i = 0; i < 8; i++) {
    const a = (i * 45 + 22.5) * Math.PI / 180;
    const x1 = Math.cos(a) * 4.5, y1 = Math.sin(a) * 4.5;
    const x2 = Math.cos(a) * 11.5, y2 = Math.sin(a) * 11.5;
    traces.push([x1, y1, x2, y2]);
    // bracket end
    const px = Math.cos(a) * 12.5, py = Math.sin(a) * 12.5;
    traces.push([px, py, px, py]);
  }
  return (
    <svg width={size} height={size} viewBox="-14 -14 28 28" fill="none" aria-hidden="true">
      <defs>
        <linearGradient id={`${id}-g`} x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stopColor="#C1272D" />
          <stop offset="100%" stopColor="#9b1a20" />
        </linearGradient>
      </defs>
      {/* petals — filled whero */}
      {petals.map((v, i) => (
        <path
          key={`p${i}`}
          d={`M${v[0][0]} ${v[0][1]} L${v[1][0]} ${v[1][1]} L${v[2][0]} ${v[2][1]} L${v[3][0]} ${v[3][1]} Z`}
          fill={`url(#${id}-g)`}
        />
      ))}
      {/* circuit traces — koura */}
      {traces.map(([x1, y1, x2, y2], i) => (
        <line
          key={`t${i}`}
          x1={x1} y1={y1} x2={x2} y2={y2}
          stroke="#D8A857"
          strokeWidth={0.4}
          strokeLinecap="round"
        />
      ))}
      {/* end pips */}
      {Array.from({ length: 8 }).map((_, i) => {
        const a = (i * 45 + 22.5) * Math.PI / 180;
        return (
          <circle
            key={`pip${i}`}
            cx={Math.cos(a) * 12}
            cy={Math.sin(a) * 12}
            r={0.55}
            fill="#D8A857"
          />
        );
      })}
      {/* centre eye */}
      <circle cx="0" cy="0" r="1.6" fill="#0A0A0A" />
      <circle cx="0" cy="0" r="1.1" fill="#D8A857" />
    </svg>
  );
}

/* ---------- SLGWW heraldic sigil ---------- */
function SLGWWSigil({ size = 40, opacity = 1, id = 'slgww' }) {
  return (
    <svg width={size} height={size} viewBox="-12 -12 24 24" fill="none" aria-hidden="true" style={{ opacity }}>
      {/* outer ring */}
      <circle cx="0" cy="0" r="10" stroke="#D8A857" strokeWidth="0.6" />
      {/* inner ring */}
      <circle cx="0" cy="0" r="8.4" stroke="#D8A857" strokeWidth="0.3" opacity="0.6" />
      {/* vertical bar */}
      <rect x="-0.7" y="-7.5" width="1.4" height="15" fill="#D8A857" />
      {/* diamond gem at centre */}
      <path d="M0 -2.6 L2.4 0 L0 2.6 L-2.4 0 Z" fill="#C1272D" stroke="#D8A857" strokeWidth="0.35" />
      {/* four corner dots, at NE/SE/SW/NW relative to bar */}
      {[[6.4, -6.4], [6.4, 6.4], [-6.4, 6.4], [-6.4, -6.4]].map(([x, y], i) => (
        <circle key={i} cx={x} cy={y} r="0.7" fill="#D8A857" />
      ))}
    </svg>
  );
}

Object.assign(window, { Koru, MathSpiral, SLGWWSigil });
