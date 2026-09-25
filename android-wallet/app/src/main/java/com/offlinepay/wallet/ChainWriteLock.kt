package com.offlinepay.wallet

import kotlinx.coroutines.sync.Mutex

/// One on-chain write at a time from this wallet's key. Top-up, auto-lock
/// and settle all sign from the same address; letting them overlap makes
/// two txs pick the same nonce and one fails ("nonce too low" /
/// "replacement underpriced"). Not re-entrant — never nest.
object ChainWriteLock {
    val mutex = Mutex()
}
