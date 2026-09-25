package com.offlinepay.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.*
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.TransactionEncoder
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import org.web3j.utils.Numeric
import java.math.BigInteger

private val MIN_GAS_PRICE: BigInteger = BigInteger.valueOf(35_000_000_000L) // 35 gwei

/// Calls OfflineVault.settle* directly. Caller MUST have MATIC for gas.
/// Returns the tx hash on success. settleBearerBatch additionally waits
/// for the receipt and asserts on-chain status==1, so the caller can be
/// sure the funds actually moved.
class SettlementClient(
    private val rpcUrl: String,
    private val vaultAddress: String,
    private val chainId: Long,
    private val keyPair: ECKeyPair,
    private val fromAddress: String,
) {
    private val web3 = Web3j.build(HttpService(rpcUrl))

    /// Build the on-chain `Voucher` struct tuple. v3 layout:
    ///   (payer, merchant, recipient, amount, expiry, nonce, voucherId)
    /// Uses StaticStruct (not Dynamic) — every field is a static type, and
    /// DynamicStruct adds an offset header that doesn't match the contract's
    /// inline calldata layout.
    private fun voucherTuple(r: VoucherRow) = StaticStruct(
        Address(r.payer),
        Address(r.merchant),
        Address(r.recipient),
        Uint256(BigInteger(r.amount)),
        Uint256(BigInteger.valueOf(r.expiry)),
        Uint256(BigInteger.valueOf(r.nonce)),
        Bytes32(Numeric.hexStringToByteArray(r.voucherId)),
    )

    private fun bearerBatchData(rows: List<VoucherRow>): String {
        val tuples = rows.map { voucherTuple(it) }
        val sigs   = rows.map { DynamicBytes(Numeric.hexStringToByteArray(it.signature)) }
        val function = Function(
            "settleBearerBatch",
            listOf(
                @Suppress("UNCHECKED_CAST")
                DynamicArray(StaticStruct::class.java, tuples) as Type<*>,
                DynamicArray(DynamicBytes::class.java, sigs) as Type<*>,
            ),
            emptyList()
        )
        return FunctionEncoder.encode(function)
    }

    /// Dry-run settleBearerBatch via eth_call. Null = would succeed; else the
    /// revert reason ("already settled", "insufficient locked", …). One bad
    /// voucher reverts the whole batch, so callers check before paying gas.
    suspend fun preflightBearerBatch(rows: List<VoucherRow>): String? =
        withContext(Dispatchers.IO) {
            val resp = web3.ethCall(
                org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(
                    fromAddress, vaultAddress, bearerBatchData(rows)
                ),
                org.web3j.protocol.core.DefaultBlockParameterName.LATEST,
            ).send()
            if (resp.hasError()) {
                decodeRevertReason(resp.error?.message ?: "eth_call reverted", resp.error?.data?.toString())
            } else if (resp.isReverted) {
                resp.revertReason ?: "reverted"
            } else null
        }

    /// Bearer settle (relay-friendly). Recipient comes from each voucher
    /// itself — it was signed by the payer at tap time. msg.sender can be
    /// anyone; this is the mesh-relay path. Waits for the receipt.
    suspend fun settleBearerBatch(rows: List<VoucherRow>): String =
        withContext(Dispatchers.IO) {
            require(rows.isNotEmpty()) { "no vouchers to settle" }
            val gasLimit = BigInteger.valueOf(250_000L * rows.size + 150_000L)
            sendAndWait(vaultAddress, bearerBatchData(rows), gasLimit, "settle")
        }

    /// Sign + broadcast under ChainWriteLock using the PENDING nonce (so a
    /// still-unmined earlier tx from this wallet doesn't collide), then wait
    /// for the receipt and require status 1.
    private suspend fun sendAndWait(to: String, data: String, gasLimit: BigInteger, what: String): String =
        ChainWriteLock.mutex.withLock {
            val nonce = web3.ethGetTransactionCount(
                fromAddress, org.web3j.protocol.core.DefaultBlockParameterName.PENDING
            ).send().transactionCount
            // Reserve what the tx actually needs (+25%), not a fixed worst
            // case: a card settle uses ~160k gas, and reserving 400k made the
            // wallet look 2.5x poorer than it is ("insufficient funds for gas").
            val limit = estimateGas(to, data) ?: gasLimit
            val tx = RawTransaction.createTransaction(nonce, gasPrice(), limit, to, BigInteger.ZERO, data)
            val signed = TransactionEncoder.signMessage(tx, chainId, org.web3j.crypto.Credentials.create(keyPair))
            val resp = web3.ethSendRawTransaction(Numeric.toHexString(signed)).send()
            if (resp.hasError()) error("$what submit failed: ${resp.error.message}")
            val txHash = resp.transactionHash
            val rcpt = waitForReceipt(txHash) ?: error("$what tx not mined within 90s: $txHash")
            if (rcpt.status != "0x1") error("$what reverted on chain: $txHash")
            txHash
        }

    private fun estimateGas(to: String, data: String): BigInteger? = runCatching {
        val r = web3.ethEstimateGas(
            org.web3j.protocol.core.methods.request.Transaction.createFunctionCallTransaction(
                fromAddress, null, null, null, to, data
            )
        ).send()
        if (r.hasError()) null
        else r.amountUsed.multiply(BigInteger.valueOf(5)).divide(BigInteger.valueOf(4))
    }.getOrNull()

    /// Amoy rejects tips under ~25 gwei and eth_gasPrice lags spikes, which
    /// left txs stuck in the mempool. Pay 30% over the suggestion, min 35 gwei.
    private fun gasPrice(): BigInteger {
        val suggested = runCatching { web3.ethGasPrice().send().gasPrice }.getOrNull() ?: BigInteger.ZERO
        val bumped = suggested.multiply(BigInteger.valueOf(13)).divide(BigInteger.TEN)
        return bumped.max(MIN_GAS_PRICE)
    }

    /// Pre-flight via eth_call: returns null if the call would succeed,
    /// otherwise a human-friendly revert reason. Saves gas on guaranteed-
    /// to-fail submissions and surfaces a precise diagnosis instead of
    /// "settle reverted on chain: 0x…".
    suspend fun preflightBearerWithEndorsement(row: VoucherRow): String? =
        withContext(Dispatchers.IO) {
            val device  = row.endorsementDevice  ?: return@withContext "missing endorsement device"
            val primary = row.endorsementPrimary ?: return@withContext "missing endorsement primary"
            val ts      = row.endorsementTs      ?: return@withContext "missing endorsement timestamp"
            val esig    = row.endorsementSig     ?: return@withContext "missing endorsement signature"
            val voucher  = voucherTuple(row)
            val function = Function(
                "settleBearerWithEndorsement",
                listOf(
                    voucher,
                    DynamicBytes(Numeric.hexStringToByteArray(row.signature)),
                    Address(device),
                    Address(primary),
                    Uint256(BigInteger.valueOf(ts)),
                    DynamicBytes(Numeric.hexStringToByteArray(esig)),
                ),
                emptyList()
            )
            val data = FunctionEncoder.encode(function)
            val resp = web3.ethCall(
                org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(
                    fromAddress, vaultAddress, data
                ),
                org.web3j.protocol.core.DefaultBlockParameterName.LATEST,
            ).send()
            if (resp.hasError()) {
                val data4 = resp.error?.data?.toString()
                decodeRevertReason(resp.error?.message ?: "eth_call reverted", data4)
            } else null
        }

    /// True-bearer settle, paid out to the merchant primary committed by
    /// the ESP32 endorsement. Used for B2 cards where recipient = 0x0
    /// and the merchant binding only happens at tap time.
    ///
    /// Reverts via the contract's existing checks if the voucher sig
    /// doesn't match the payer, or the endorsement sig doesn't match
    /// the device, or any of {voucher.recipient != 0, primary == 0,
    /// expired, double-spend, insufficient locked} fail.
    suspend fun settleBearerWithEndorsement(row: VoucherRow): String =
        withContext(Dispatchers.IO) {
            val device  = row.endorsementDevice  ?: error("missing endorsement device")
            val primary = row.endorsementPrimary ?: error("missing endorsement primary")
            val ts      = row.endorsementTs      ?: error("missing endorsement timestamp")
            val esig    = row.endorsementSig     ?: error("missing endorsement signature")

            val voucher  = voucherTuple(row)
            val function = Function(
                "settleBearerWithEndorsement",
                listOf(
                    voucher,
                    DynamicBytes(Numeric.hexStringToByteArray(row.signature)),
                    Address(device),
                    Address(primary),
                    Uint256(BigInteger.valueOf(ts)),
                    DynamicBytes(Numeric.hexStringToByteArray(esig)),
                ),
                emptyList()
            )
            val data  = FunctionEncoder.encode(function)
            sendAndWait(vaultAddress, data, BigInteger.valueOf(400_000L), "settle")
        }

    /// Poll Web3j for the receipt of [txHash]. Returns null if not mined
    /// within ~90s. Transient RPC errors while polling are retried.
    private suspend fun waitForReceipt(txHash: String): org.web3j.protocol.core.methods.response.TransactionReceipt? =
        withContext(Dispatchers.IO) {
            repeat(45) {
                val r = runCatching { web3.ethGetTransactionReceipt(txHash).send().transactionReceipt }.getOrNull()
                if (r != null && r.isPresent) return@withContext r.get()
                kotlinx.coroutines.delay(2000)
            }
            null
        }

    /// USDC wallet balance — `IERC20.balanceOf(addr)` against the
    /// configured USDC contract. Receivers see their wallet grow here
    /// when their bearer/recipient-bound vouchers settle on chain;
    /// senders see this stay flat (their locked funds are inside the
    /// vault, not the USDC wallet, until they `unlock` them).
    suspend fun usdcBalance(addr: String): BigInteger = withContext(Dispatchers.IO) {
        val function = Function(
            "balanceOf",
            listOf(Address(addr)),
            listOf<TypeReference<*>>(object : TypeReference<Uint256>() {})
        )
        val data = FunctionEncoder.encode(function)
        val resp = web3.ethCall(
            org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(
                fromAddress, Config.USDC_ADDRESS, data
            ),
            org.web3j.protocol.core.DefaultBlockParameterName.LATEST
        ).send()
        if (resp.hasError()) BigInteger.ZERO
        else BigInteger(resp.value.removePrefix("0x").ifEmpty { "0" }, 16)
    }

    suspend fun maticBalance(addr: String): BigInteger = withContext(Dispatchers.IO) {
        web3.ethGetBalance(addr, org.web3j.protocol.core.DefaultBlockParameterName.LATEST).send().balance
    }

    /// Bulk-check `usedVouchers[voucherId]` for several voucherIds. Used by
    /// the sender side to figure out which in-flight vouchers have settled
    /// (and thus their lockedBalance has decremented).
    suspend fun usedVouchers(ids: List<String>): Map<String, Boolean> =
        withContext(Dispatchers.IO) {
            ids.associateWith { id ->
                runCatching {
                    val function = Function(
                        "usedVouchers",
                        listOf(Bytes32(Numeric.hexStringToByteArray(id))),
                        listOf<TypeReference<*>>(object : TypeReference<org.web3j.abi.datatypes.Bool>() {})
                    )
                    val data = FunctionEncoder.encode(function)
                    val resp = web3.ethCall(
                        org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(
                            fromAddress, vaultAddress, data
                        ),
                        org.web3j.protocol.core.DefaultBlockParameterName.LATEST
                    ).send()
                    if (resp.hasError()) false
                    else BigInteger(resp.value.removePrefix("0x").ifEmpty { "0" }, 16) != BigInteger.ZERO
                }.getOrDefault(false)
            }
        }

    suspend fun lockedBalance(addr: String): BigInteger = withContext(Dispatchers.IO) {
        val function = Function(
            "lockedBalance",
            listOf(Address(addr)),
            listOf<TypeReference<*>>(object : TypeReference<Uint256>() {})
        )
        val data = FunctionEncoder.encode(function)
        val resp = web3.ethCall(
            org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(
                fromAddress, vaultAddress, data
            ),
            org.web3j.protocol.core.DefaultBlockParameterName.LATEST
        ).send()
        if (resp.hasError()) BigInteger.ZERO
        else BigInteger(resp.value.removePrefix("0x").ifEmpty { "0" }, 16)
    }

    /// Submit approve + lockFunds back-to-back with explicit consecutive
    /// nonces, then wait until the lock is mined. Returns the lockFunds tx hash.
    suspend fun approveAndLock(usdcAddr: String, amount: BigInteger): String =
        withContext(Dispatchers.IO) { ChainWriteLock.mutex.withLock {
            val baseNonce = web3.ethGetTransactionCount(
                fromAddress, org.web3j.protocol.core.DefaultBlockParameterName.PENDING
            ).send().transactionCount
            val gasPrice = gasPrice()
            val creds    = org.web3j.crypto.Credentials.create(keyPair)

            val approveData = FunctionEncoder.encode(Function(
                "approve",
                listOf(Address(vaultAddress), Uint256(amount)),
                emptyList(),
            ))
            val approveTx = RawTransaction.createTransaction(
                baseNonce, gasPrice, BigInteger.valueOf(120_000L),
                usdcAddr, BigInteger.ZERO, approveData,
            )
            val approveSigned = TransactionEncoder.signMessage(approveTx, chainId, creds)
            val approveResp = web3.ethSendRawTransaction(Numeric.toHexString(approveSigned)).send()
            if (approveResp.hasError()) error("approve: ${approveResp.error.message}")

            val lockData = FunctionEncoder.encode(Function(
                "lockFunds",
                listOf(Uint256(amount)),
                emptyList(),
            ))
            val lockTx = RawTransaction.createTransaction(
                baseNonce + BigInteger.ONE, gasPrice, BigInteger.valueOf(180_000L),
                vaultAddress, BigInteger.ZERO, lockData,
            )
            val lockSigned = TransactionEncoder.signMessage(lockTx, chainId, creds)
            val lockResp = web3.ethSendRawTransaction(Numeric.toHexString(lockSigned)).send()
            if (lockResp.hasError()) error("lock: ${lockResp.error.message}")
            val lockHash = lockResp.transactionHash
            val rcpt = waitForReceipt(lockHash) ?: error("lock tx not mined within 90s: $lockHash")
            if (rcpt.status != "0x1") error("lock reverted on chain: $lockHash")
            lockHash
        } }

    /// Translate raw RPC error / revert data into a one-line user-friendly
    /// reason. Handles plain message reverts (Error(string) selector
    /// 0x08c379a0), OZ ECDSA's custom errors, and the contract's own
    /// require strings. Falls back to the raw message for anything else.
    private fun decodeRevertReason(message: String, errData: String?): String {
        // Solidity `revert("...")` ABI-encodes as Error(string):
        //   0x08c379a0 + 32-byte offset + 32-byte length + bytes(string).
        if (errData != null && errData.startsWith("0x08c379a0") && errData.length > 138) {
            return try {
                val lenHex = errData.substring(74, 138)
                val len = BigInteger(lenHex, 16).toInt().coerceAtMost(200)
                val strHex = errData.substring(138, 138 + len * 2)
                val bytes = Numeric.hexStringToByteArray(strHex)
                String(bytes).trim()
            } catch (_: Throwable) { message }
        }
        // Custom errors: 4-byte selector + ABI-encoded args.
        if (errData != null && errData.length >= 10) {
            return when (errData.substring(0, 10)) {
                "0xd78bce0c" -> "endorsement signature has high s (firmware bug — re-tap)"
                "0xfb8f41b2" -> "ECDSA recover error (bad endorsement)"
                "0xf645eedf" -> "ECDSA invalid signature length"
                else -> message
            }
        }
        return message
    }
}
