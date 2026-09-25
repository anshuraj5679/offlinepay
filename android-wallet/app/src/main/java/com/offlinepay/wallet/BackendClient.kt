package com.offlinepay.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/// Talks to the OfflinePay backend over HTTP. Reads the base URL from
/// `Config.BACKEND_BASE` on every request so a runtime flip by
/// BackendResolver (v4 ↔ v6) propagates without recreating clients.
class BackendClient(initialBaseUrl: String = Config.BACKEND_BASE) {
    private val baseUrl: String get() = Config.BACKEND_BASE
    // 5-minute call timeout: Amoy testnet txs are slow (~5-10s each) and a
    // topup does mint + approve + lock + sign-batch serialized via the
    // backend's chain mutex. Several stacked requests can take minutes.
    private val http = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.MINUTES)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    // Dashboard polling must fail fast so a dead network never stalls the UI.
    private val fastHttp = http.newBuilder()
        .callTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable data class StateReq(
        val address: String,
        val voucherIds: List<String> = emptyList(),
        val payers: List<String> = emptyList(),
    )
    /// Everything read at one block, so locked/usdc/used can never disagree.
    @Serializable data class StateResp(
        val ok: Boolean = false,
        val blockNumber: Long = 0,
        val address: String? = null,
        val locked: String = "0",
        val usdc: String = "0",
        val matic: String = "0",
        val used: Map<String, Boolean> = emptyMap(),
        val payerLocked: Map<String, String> = emptyMap(),
        val error: String? = null,
    )

    suspend fun walletState(address: String, voucherIds: List<String>, payers: List<String> = emptyList()): StateResp =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(StateReq.serializer(), StateReq(address, voucherIds, payers))
            val text = post(fastHttp, "/api/wallet/state", body)
            json.decodeFromString(StateResp.serializer(), text).also {
                if (!it.ok) error(it.error ?: "state unavailable")
            }
        }

    /// POST and return the body. Non-2xx responses become exceptions carrying
    /// the server's `error` field instead of a JSON-decode crash on an HTML page.
    private fun post(client: OkHttpClient, path: String, body: String): String {
        val r = client.newCall(
            Request.Builder().url("$baseUrl$path")
                .post(body.toRequestBody("application/json".toMediaType())).build()
        ).execute()
        r.use {
            val text = it.body?.string() ?: ""
            if (!it.isSuccessful) {
                val msg = runCatching {
                    json.parseToJsonElement(text).let { el ->
                        (el as? kotlinx.serialization.json.JsonObject)?.get("error")?.toString()?.trim('"')
                    }
                }.getOrNull()
                error(msg ?: "server error ${it.code}")
            }
            return text.ifEmpty { "{}" }
        }
    }

    @Serializable data class InitReq(val address: String, val amountUsdc: String)
    @Serializable data class InitResp(
        val ok: Boolean = false,
        val address: String? = null,
        val amountUsdc: String? = null,
        val error: String? = null,
    )

    @Serializable data class TopupReq(
        val address: String,
        val amountUsdc: String,
        val denomUsdc: String? = null,
        val count: Int? = null,
    )
    @Serializable data class VoucherFields(
        val payer: String, val merchant: String, val recipient: String,
        val amount: String,
        val expiry: Long, val nonce: Long, val voucherId: String
    )
    @Serializable data class IssuedVoucher(
        val voucher: VoucherFields,
        val signature: String,
        val cardPayload: String,
    )
    @Serializable data class TopupResp(
        val ok: Boolean = false,
        val owner: String? = null,
        val amountUsdc: String? = null,
        val vouchers: List<IssuedVoucher> = emptyList(),
        val error: String? = null,
    )

    @Serializable data class RedeemItem(val voucher: VoucherFields, val signature: String)
    /// recipient is now embedded in each voucher's signed digest, so the
    /// redeem request body just needs the voucher list.
    @Serializable data class RedeemReq(val vouchers: List<RedeemItem>)
    @Serializable data class Reject(val voucherId: String, val reason: String)
    @Serializable data class RedeemResp(
        val ok: Boolean = false,
        val settled: Int = 0,
        val tx: String? = null,
        val rejected: List<Reject> = emptyList(),
        val error: String? = null,
    )

    suspend fun init(address: String, amountBaseUnits: Long): InitResp =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(
                InitReq.serializer(),
                InitReq(address, amountBaseUnits.toString())
            )
            json.decodeFromString(InitResp.serializer(), post(http, "/api/wallet/init", body))
        }

    suspend fun topup(address: String, amountBaseUnits: Long, denomBaseUnits: Long, count: Int): TopupResp =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(
                TopupReq.serializer(),
                TopupReq(address, amountBaseUnits.toString(), denomBaseUnits.toString(), count)
            )
            val r = http.newCall(
                Request.Builder().url("$baseUrl/api/wallet/topup")
                    .post(body.toRequestBody("application/json".toMediaType())).build()
            ).execute()
            val text = r.body?.string() ?: "{}"
            r.close()
            json.decodeFromString(TopupResp.serializer(), text)
        }

    suspend fun redeem(items: List<RedeemItem>): RedeemResp =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(
                RedeemReq.serializer(),
                RedeemReq(items)
            )
            json.decodeFromString(RedeemResp.serializer(), post(http, "/api/wallet/redeem", body))
        }
}
