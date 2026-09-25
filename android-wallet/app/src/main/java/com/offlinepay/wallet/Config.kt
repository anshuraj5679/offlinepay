package com.offlinepay.wallet

import java.math.BigInteger

object Config {
    // Polygon Amoy testnet.
    const val CHAIN_ID = 80002L
    // Fresh Amoy deploy (deployer 0x0926…e64c) — 2026-09-24.
    // v3.2 vault: adds 2% settler fee to msg.sender on bearer settle paths.
    const val VAULT_ADDRESS  = "0x6cef15E103d4B488cbD475Ce0621b6340c285676"
    const val USDC_ADDRESS   = "0x27612D7262bDB2bE66b261642909de3436077405"

    // Backend deployed on Railway (HTTPS, single host). Resolver still runs
    // but there's only one candidate now.
    val BACKEND_CANDIDATES = listOf(
        "https://offpay-backend-production-2077.up.railway.app"
    )
    @Volatile var BACKEND_BASE: String = BACKEND_CANDIDATES[0]

    // RPC goes through backend's /rpc proxy. Computed dynamically because
    // BACKEND_BASE is var — the resolver may flip it between v4 / v6 at
    // startup based on which network the phone is on.
    val RPC_URL: String get() = "$BACKEND_BASE/rpc"

    // $1,000,000 — matches the raised on-chain cap (setLimits, 2026-09-24).
    // For a demo this is effectively unlimited; the vault will still reject
    // if the payer's lockedBalance can't cover the amount.
    val MAX_SINGLE_USDC: BigInteger = BigInteger("1000000000000")
    const val DEFAULT_TTL_SECONDS    = 24L * 3600

    // Mirrors OfflineVault.SETTLER_FEE_BPS: whoever broadcasts a bearer
    // settle (and isn't the recipient) earns 2% of the voucher amount.
    const val SETTLER_FEE_BPS = 200L
    fun settlerFee(amount: Long): Long = amount * SETTLER_FEE_BPS / 10_000L

    // Ask the backend for a gas top-up below this (0.10 MATIC) — the same
    // threshold the backend uses in /api/wallet/init, so a request below it
    // actually refills the wallet (to 0.20 MATIC). A lower app threshold left
    // relays sitting at e.g. 0.08 MATIC: too little for a settle, never refilled.
    val GAS_FLOOR_WEI: BigInteger = BigInteger("100000000000000000")
    // Wallet USDC below this stays unlocked — not worth two txs of gas.
    const val AUTOLOCK_MIN_BASE_UNITS = 100_000L   // $0.10

    /// Block explorer base — used to render live links from receipts.
    const val EXPLORER_BASE = "https://amoy.polygonscan.com"
    fun txUrl(hash: String) = "$EXPLORER_BASE/tx/$hash"
    fun addressUrl(addr: String) = "$EXPLORER_BASE/address/$addr"

    // Mesh (Nearby Connections)
    const val MESH_SERVICE_ID  = "com.offlinepay.wallet.mesh"
    const val CLAIM_WAIT_MS    = 5_000L
    const val CLAIM_BACKOFF_MS = 30_000L

    // Bluetooth SPP (ESP32 reader)
    const val BT_DEVICE_NAME   = "OfflinePay_Reader"
    val BT_SPP_UUID: java.util.UUID = java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
}
