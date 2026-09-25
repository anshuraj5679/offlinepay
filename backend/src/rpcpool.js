import { config, maskRpcUrl } from "./config.js";

// Public Amoy endpoints appended after the configured (Alchemy) keys. Any of
// them can be degraded at a given moment, so every caller goes through the
// health ranking below rather than a fixed order.
const PUBLIC_FALLBACKS = [
  "https://polygon-amoy.gateway.tenderly.co",
  "https://polygon-amoy.drpc.org",
  "https://polygon-amoy-bor-rpc.publicnode.com",
];

export const RPC_UPSTREAMS = [...new Set([...config.rpcUrls, ...PUBLIC_FALLBACKS])];

const health = new Map(RPC_UPSTREAMS.map(u => [u, { fails: 0, benchedUntil: 0 }]));

/// Configured priority order, except upstreams that failed recently are
/// benched (30s, 60s, … up to 2min) and moved to the back.
export function orderedUpstreams() {
  const now = Date.now();
  const ready = RPC_UPSTREAMS.filter(u => health.get(u).benchedUntil <= now);
  const benched = RPC_UPSTREAMS.filter(u => health.get(u).benchedUntil > now)
    .sort((a, b) => health.get(a).benchedUntil - health.get(b).benchedUntil);
  return [...ready, ...benched];
}

export function markFail(u) {
  const h = health.get(u);
  if (!h) return;
  h.fails += 1;
  h.benchedUntil = Date.now() + Math.min(h.fails, 4) * 30_000;
}

export function markOk(u) {
  const h = health.get(u);
  if (h) { h.fails = 0; h.benchedUntil = 0; }
}

export function healthReport() {
  const now = Date.now();
  return orderedUpstreams().map(u => ({
    rpc: maskRpcUrl(u),
    benchedForSec: Math.max(0, Math.round((health.get(u).benchedUntil - now) / 1000)),
  }));
}

/// JSON-RPC error bodies that mean "this node can't serve you right now" —
/// try another upstream. Reverts and nonce/funds errors are NOT in here.
export function isRetryableRpcError(err) {
  if (!err) return false;
  const code = Number(err.code);
  const msg = String(err.message || "").toLowerCase();
  return code === -32001 || code === -32005 || code === -32603 || code === -32701 ||
    code === 429 || code === 31 ||
    msg.includes("unable to complete") || msg.includes("rate limit") ||
    msg.includes("limit exceeded") || msg.includes("exceeded") || msg.includes("temporar") ||
    msg.includes("capacity") || msg.includes("batch") || msg.includes("header not found") ||
    msg.includes("no available nodes") || msg.includes("timeout") || msg.includes("unavailable");
}
