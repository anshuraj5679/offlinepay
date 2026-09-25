import { ethers } from "ethers";
import { config } from "./config.js";
import { RPC_UPSTREAMS, orderedUpstreams, markFail, markOk } from "./rpcpool.js";

const network = ethers.Network.from(config.chainId);

// No JSON-RPC batching: a throttled batch comes back as one error object
// ethers can't map to the individual calls ("could not coalesce error").
// 10s per request so a stalled node fails over instead of hanging for 5 min.
function makeBackend(url) {
  const req = new ethers.FetchRequest(url);
  req.timeout = 10_000;
  return new ethers.JsonRpcProvider(req, network, { staticNetwork: network, batchMaxCount: 1 });
}
const backends = new Map(RPC_UPSTREAMS.map(u => [u, makeBackend(u)]));

// Errors that are the same on every node — retrying elsewhere is pointless
// (and for sends, harmful).
const DETERMINISTIC = new Set([
  "INSUFFICIENT_FUNDS", "NONCE_EXPIRED", "REPLACEMENT_UNDERPRICED",
  "TRANSACTION_REPLACED", "INVALID_ARGUMENT", "ACTION_REJECTED", "NUMERIC_FAULT",
]);

// ~1 minute of Amoy blocks. Nodes a few blocks apart are normal.
const STALE_HEAD_BLOCKS = 30;

function isRetryable(e) {
  // ethers reports ANY eth_call failure as CALL_EXCEPTION. A real revert
  // carries revert data; a node outage ("Unable to complete request") shows
  // up as "missing revert data" with none.
  if (e?.code === "CALL_EXCEPTION") return e.data == null && e.reason == null;
  return !DETERMINISTIC.has(e?.code);
}

/// Per-request failover across all upstreams, healthiest first. Unlike
/// ethers.FallbackProvider it never tries to "coalesce" differing errors
/// from several nodes — it just moves on to the next node.
class FailoverProvider extends ethers.AbstractProvider {
  // Highest head any upstream has reported. A load-balanced node can lag by
  // an hour (seen on Amoy); trusting it would make balances jump backwards.
  #maxHead = 0;

  constructor() { super(network, { pollingInterval: 2000 }); }

  async _detectNetwork() { return network; }

  async _perform(req) {
    let lastErr;
    for (const url of orderedUpstreams()) {
      try {
        const result = await backends.get(url)._perform(req);
        if (req.method === "getBlockNumber") {
          const head = Number(result);
          if (head < this.#maxHead - STALE_HEAD_BLOCKS) {
            markFail(url);
            lastErr = new Error(`stale head ${head} < ${this.#maxHead}`);
            continue;
          }
          this.#maxHead = Math.max(this.#maxHead, head);
        }
        markOk(url);
        return result;
      } catch (e) {
        // Re-broadcast of a tx an earlier node already accepted.
        if (req.method === "broadcastTransaction" &&
            /already known|known transaction|already imported/i.test(String(e?.message))) {
          markOk(url);
          return ethers.keccak256(req.signedTransaction);
        }
        if (!isRetryable(e)) throw e;
        markFail(url);
        lastErr = e;
      }
    }
    throw lastErr;
  }
}

export const provider = new FailoverProvider();

const wallet = new ethers.Wallet(config.backendKey, provider);
// NonceManager serializes pending nonces locally so back-to-back txs don't race.
export const signer = new ethers.NonceManager(wallet);
// Convenience: expose the address (NonceManager doesn't have .address until awaited).
signer.address = wallet.address;

// v3 Voucher struct: (payer, merchant, recipient, amount, expiry, nonce, voucherId)
const VOUCHER = "(address,address,address,uint256,uint256,uint256,bytes32)";
const VAULT_ABI = [
  "function lockFunds(uint256 amount)",
  "function unlock(uint256 amount)",
  "function lockedBalance(address) view returns (uint256)",
  "function lastNonce(address) view returns (uint256)",
  "function usedVouchers(bytes32) view returns (bool)",
  "function maxSinglePayment() view returns (uint256)",
  "function maxLockedBalance() view returns (uint256)",
  `function settleVoucher(${VOUCHER},bytes)`,
  `function settleBatch(${VOUCHER}[],bytes[])`,
  // settleBearer / settleBearerBatch — recipient is now embedded in the voucher,
  // not a separate argument, so anyone can broadcast (relay-friendly).
  `function settleBearer(${VOUCHER},bytes)`,
  `function settleBearerBatch(${VOUCHER}[],bytes[])`,
  // True-bearer + ESP32 endorsement (B2 cards). Voucher.recipient = 0;
  // payout goes to `merchantPrimary` committed in the device's signature.
  `function settleBearerWithEndorsement(${VOUCHER},bytes,address,address,uint256,bytes)`,
  "event VoucherSettled(bytes32 indexed voucherId, address indexed payer, address indexed recipient, uint256 amount, uint256 nonce)",
  "event FundsLocked(address indexed payer, uint256 amount, uint256 newBalance)"
];

const USDC_ABI = [
  "function balanceOf(address) view returns (uint256)",
  "function decimals() view returns (uint8)",
  "function approve(address,uint256) returns (bool)",
  "function transfer(address,uint256) returns (bool)",
  "function mint(address,uint256)" // Mock USDC only
];

export const vaultIface = new ethers.Interface(VAULT_ABI);
export const usdcIface = new ethers.Interface(USDC_ABI);

// Canonical Multicall3 (same address on every EVM chain, deployed on Amoy).
export const MULTICALL3 = "0xcA11bde05977b3631167028862bE2a173976CA11";
export const multicallIface = new ethers.Interface([
  "function aggregate3((address target, bool allowFailure, bytes callData)[] calls) payable returns ((bool success, bytes returnData)[] returnData)",
  "function getEthBalance(address addr) view returns (uint256 balance)",
]);
export const multicall = new ethers.Contract(MULTICALL3, multicallIface, provider);

export const vault = config.vault
  ? new ethers.Contract(config.vault, VAULT_ABI, signer)
  : null;

export const usdc = config.usdc
  ? new ethers.Contract(config.usdc, USDC_ABI, signer)
  : null;
