package com.offlinepay.wallet

import android.content.Context
import android.nfc.Tag
import android.nfc.tech.MifareClassic

/// Writes a card voucher straight from the phone's NFC to a MIFARE Classic
/// 1K card, in exactly the layout the ESP32 reader parses at spend time
/// (firmware/offline_pay_reader: VOUCHER_BLOCKS + DEFAULT_KEY): JSON across
/// data blocks 4..30 skipping sector trailers, NUL-terminated, key A =
/// FF FF FF FF FF FF.
object MifareCardWriter {

    val VOUCHER_BLOCKS = intArrayOf(
        4, 5, 6, 8, 9, 10, 12, 13, 14, 16, 17, 18, 20, 21, 22, 24, 25, 26, 28, 29, 30,
    )
    private const val BLOCK_SIZE = 16
    val CAPACITY = VOUCHER_BLOCKS.size * BLOCK_SIZE  // 336 bytes incl. NUL

    /// MIFARE Classic needs an NXP NFC controller; most other chips only see
    /// the card's UID. Checked before offering this path.
    fun phoneSupportsMifareClassic(ctx: Context): Boolean =
        ctx.packageManager.hasSystemFeature("com.nxp.mifare") &&
        android.nfc.NfcAdapter.getDefaultAdapter(ctx) != null

    fun uidHex(tag: Tag): String = tag.id.joinToString("") { "%02x".format(it) }

    sealed class Result {
        object Ok : Result()
        data class Failed(val reason: String) : Result()
    }

    /// Blocking — call off the main thread while the tag is in the field.
    fun write(tag: Tag, json: String): Result {
        val bytes = json.toByteArray(Charsets.UTF_8)
        if (bytes.size + 1 > CAPACITY) return Result.Failed("voucher too large for card (${bytes.size} B)")
        val buf = ByteArray(CAPACITY).also { System.arraycopy(bytes, 0, it, 0, bytes.size) }

        val mfc = MifareClassic.get(tag)
            ?: return Result.Failed("not a MIFARE Classic card — use a MIFARE Classic 1K card or keyfob")
        return try {
            mfc.connect()
            mfc.timeout = 2_000
            var authedSector = -1
            VOUCHER_BLOCKS.forEachIndexed { i, block ->
                val sector = mfc.blockToSector(block)
                if (sector != authedSector) {
                    if (!mfc.authenticateSectorWithKeyA(sector, MifareClassic.KEY_DEFAULT)) {
                        return Result.Failed("card sector $sector is locked with a non-default key")
                    }
                    authedSector = sector
                }
                mfc.writeBlock(block, buf.copyOfRange(i * BLOCK_SIZE, (i + 1) * BLOCK_SIZE))
            }
            // Read back the first block — catches a card lifted mid-write.
            mfc.authenticateSectorWithKeyA(mfc.blockToSector(VOUCHER_BLOCKS[0]), MifareClassic.KEY_DEFAULT)
            val first = mfc.readBlock(VOUCHER_BLOCKS[0])
            if (!first.contentEquals(buf.copyOfRange(0, BLOCK_SIZE))) {
                Result.Failed("verification failed — hold the card still and try again")
            } else Result.Ok
        } catch (e: Exception) {
            Result.Failed("card moved away during write (${e.javaClass.simpleName}) — hold it still and retry")
        } finally {
            runCatching { mfc.close() }
        }
    }
}
