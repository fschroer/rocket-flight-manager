package com.steampigeon.flightmanager.data.fullrecord

import com.steampigeon.flightmanager.data.fullrecord.FullRecordCodec.SAMPLE_SIZE
import com.steampigeon.flightmanager.data.fullrecord.FullRecordCodec.SAMPLES_PER_PAGE
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.Properties

/**
 * Where full records live on the phone, finished and in progress (ADR "The
 * radio download becomes the primary data path", decisions 5 and 8).
 *
 * A record is identified by (locator id, record timestamp, record CRC).  The
 * timestamp is the flight's own start time from FlightMetadata, so it names the
 * upload; the CRC is what the wire carries, so a slot rewritten by a new flight
 * can never be spliced onto the old one.  ARCHIVE_VERSION is not on the wire:
 * the codec and its 94 B sample are v8-only, and a record of any other layout
 * has a different CRC anyway.
 *
 * Per record, in [dir]:
 *   <key>.part   the committed prefix: whole pages of raw sample bytes, append-only
 *   <key>.state  the page in progress: its packet count and every packet held for it
 *   <key>.bin    the finished record, byte-identical to flash, CRC verified
 *   <key>.csv    the same record as the locator's USB export prints it
 *   <key>.meta   what the history and the uploader need (slot, samples, apogee, upload state)
 *
 * Plain java.io, so the JVM tests run it against a temp directory.  Writes that
 * replace a file go through a temp file and a rename, so a kill mid-write leaves
 * the old copy, never half of each.
 */
class FullRecordStore(private val dir: File) {

    data class Key(val locatorId: Long, val recordTs: Long, val crc: Long) {
        val name get() = "L%08X_T%d_C%08X".format(locatorId, recordTs, crc)
    }

    data class Finished(
        val key: Key,
        val slot: Int,
        val samples: Int,
        val apogeeM: Float,
        val bin: File,
        val csv: File,
        val uploadedBin: Boolean,
        val uploadedCsv: Boolean,
    )

    data class Partial(val key: Key, val slot: Int, val totalSamples: Int, val committed: Int) {
        val page get() = committed / SAMPLES_PER_PAGE
    }

    init { dir.mkdirs() }

    private fun file(k: Key, ext: String) = File(dir, "${k.name}.$ext")

    // ---- finished records --------------------------------------------------

    fun finished(): List<Finished> =
        (dir.listFiles { f -> f.name.endsWith(".meta") } ?: emptyArray())
            .mapNotNull { readMeta(it) }
            .sortedByDescending { it.key.recordTs }

    fun finished(locatorId: Long, recordTs: Long): Finished? =
        finished().firstOrNull { it.key.locatorId == locatorId && it.key.recordTs == recordTs }

    fun isFinished(k: Key): Boolean = file(k, "meta").exists() && file(k, "bin").exists()

    fun markUploaded(k: Key, bin: Boolean? = null, csv: Boolean? = null) {
        val f = file(k, "meta")
        val p = load(f) ?: return
        bin?.let { p.setProperty("uploaded_bin", it.toString()) }
        csv?.let { p.setProperty("uploaded_csv", it.toString()) }
        save(f, p)
    }

    private fun readMeta(f: File): Finished? {
        val p = load(f) ?: return null
        val k = Key(
            p.getProperty("locator_id")?.toLongOrNull() ?: return null,
            p.getProperty("record_ts")?.toLongOrNull() ?: return null,
            p.getProperty("crc")?.toLongOrNull() ?: return null,
        )
        val bin = file(k, "bin")
        if (!bin.exists()) return null
        return Finished(
            key = k,
            slot = p.getProperty("slot")?.toIntOrNull() ?: 0,
            samples = p.getProperty("samples")?.toIntOrNull() ?: (bin.length() / SAMPLE_SIZE).toInt(),
            apogeeM = p.getProperty("apogee_m")?.toFloatOrNull() ?: 0f,
            bin = bin,
            csv = file(k, "csv"),
            uploadedBin = p.getProperty("uploaded_bin") == "true",
            uploadedCsv = p.getProperty("uploaded_csv") == "true",
        )
    }

    // ---- records in progress -------------------------------------------------

    /** The partial for this flight, if any (the CRC is learned from it). */
    fun partial(locatorId: Long, recordTs: Long): Partial? {
        val prefix = "L%08X_T%d_C".format(locatorId, recordTs)
        val f = (dir.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".state") } ?: emptyArray())
            .maxByOrNull { it.lastModified() } ?: return null
        val crc = f.name.removePrefix(prefix).removeSuffix(".state").toLongOrNull(16) ?: return null
        val k = Key(locatorId, recordTs, crc)
        val st = readState(k) ?: return null
        return Partial(k, st.slot, st.total, committedSamples(k, st.total))
    }

    /**
     * The assembler for [k], resumed from disk when there is anything to resume.
     * Any other partial for the same flight (a different CRC: the slot was
     * rewritten, or the record was) is deleted.
     */
    fun open(k: Key, slot: Int, totalSamples: Int): FullRecordAssembler {
        val prefix = "L%08X_T%d_C".format(k.locatorId, k.recordTs)
        (dir.listFiles { f -> f.name.startsWith(prefix) && (f.name.endsWith(".part") || f.name.endsWith(".state")) }
            ?: emptyArray()).filter { !it.name.startsWith(k.name + ".") }.forEach { it.delete() }

        val st = readState(k)
        if (st == null || st.total != totalSamples) {
            discard(k)
            writeState(k, State(slot, totalSamples, 0, 0, emptyMap()))
            return FullRecordAssembler(k.crc, totalSamples)
        }
        val committed = committedSamples(k, totalSamples)
        val part = file(k, "part")
        // A kill between an append and its state write can leave a torn tail.
        if (part.exists() && part.length() != committed.toLong() * SAMPLE_SIZE)
            RandomAccessFile(part, "rw").use { it.setLength(committed.toLong() * SAMPLE_SIZE) }
        val prefixBytes = if (part.exists()) part.readBytes() else ByteArray(0)
        val asm = FullRecordAssembler(k.crc, totalSamples, prefixBytes)
        asm.takeNewlyCommitted()                           // already on disk
        if (st.page == asm.page) asm.restoreHeld(st.packetCount, st.held)
        return asm
    }

    /** Persist [asm]: append what it committed, rewrite the page-in-progress state. */
    fun save(k: Key, slot: Int, asm: FullRecordAssembler) {
        val add = asm.takeNewlyCommitted()
        if (add.isNotEmpty()) FileOutputStream(file(k, "part"), true).use { it.write(add); it.fd.sync() }
        writeState(k, State(slot, asm.totalSamples, asm.page, asm.packetCount, asm.heldPackets()))
    }

    /** A verified record: write .bin, .csv and .meta, then drop the partial. */
    fun finish(k: Key, slot: Int, apogeeM: Float, asm: FullRecordAssembler): Finished {
        check(asm.complete && asm.verified) { "finish() on an unverified record" }
        return writeFinished(k, slot, apogeeM, asm.data, uploaded = false)
    }

    /**
     * A record that came back from the web store (ADR decision 6b), kept like a
     * transferred one.  The caller has checked [bytes] against the CRC in [k].
     */
    fun adopt(k: Key, slot: Int, apogeeM: Float, bytes: ByteArray, uploaded: Boolean): Finished {
        check(bytes.size % SAMPLE_SIZE == 0 && FullRecordCodec.crc32(bytes) == k.crc) { "adopt() of a record that fails its CRC" }
        return writeFinished(k, slot, apogeeM, bytes, uploaded)
    }

    private fun writeFinished(k: Key, slot: Int, apogeeM: Float, bytes: ByteArray, uploaded: Boolean): Finished {
        val samples = bytes.size / SAMPLE_SIZE
        writeAtomic(file(k, "bin")) { it.write(bytes) }
        writeAtomic(file(k, "csv")) { os ->
            os.bufferedWriter(Charsets.US_ASCII).let { FullRecordCsv.write(bytes, samples, it) }
        }
        val p = Properties().apply {
            setProperty("locator_id", k.locatorId.toString())
            setProperty("record_ts", k.recordTs.toString())
            setProperty("crc", k.crc.toString())
            setProperty("slot", slot.toString())
            setProperty("samples", samples.toString())
            setProperty("apogee_m", apogeeM.toString())
            setProperty("uploaded_bin", uploaded.toString())
            setProperty("uploaded_csv", uploaded.toString())
        }
        save(file(k, "meta"), p)
        file(k, "part").delete()
        file(k, "state").delete()
        return readMeta(file(k, "meta"))!!
    }

    fun discard(k: Key) {
        file(k, "part").delete()
        file(k, "state").delete()
    }

    /** Raw bytes of a finished record, or the committed prefix of a partial. */
    fun bytes(k: Key): ByteArray? =
        file(k, "bin").takeIf { it.exists() }?.readBytes() ?: file(k, "part").takeIf { it.exists() }?.readBytes()

    // ---- small persistent flags (trigger state that must survive a kill) ------

    fun flag(name: String): String? = load(File(dir, "flags.properties"))?.getProperty(name)

    fun setFlag(name: String, value: String?) {
        val f = File(dir, "flags.properties")
        val p = load(f) ?: Properties()
        if (value == null) p.remove(name) else p.setProperty(name, value)
        save(f, p)
    }

    // ---- state file ------------------------------------------------------------

    private class State(
        val slot: Int, val total: Int, val page: Int, val packetCount: Int,
        val held: Map<Int, FullRecordAssembler.Held>,
    )

    private fun committedSamples(k: Key, total: Int): Int {
        val n = (file(k, "part").let { if (it.exists()) it.length() else 0L } / SAMPLE_SIZE).toInt()
        return if (n >= total) total else n / SAMPLES_PER_PAGE * SAMPLES_PER_PAGE
    }

    private fun writeState(k: Key, s: State) = writeAtomic(file(k, "state")) { os ->
        DataOutputStream(os).apply {
            writeInt(STATE_MAGIC); writeInt(STATE_VERSION)
            writeInt(s.slot); writeInt(s.total); writeInt(s.page); writeInt(s.packetCount)
            writeInt(s.held.size)
            for ((i, h) in s.held) {
                writeInt(i); writeInt(h.firstInPage); writeInt(h.sampleCount)
                writeInt(h.payload.size); write(h.payload)
            }
            flush()
        }
    }

    private fun readState(k: Key): State? {
        val f = file(k, "state")
        if (!f.exists()) return null
        return try {
            DataInputStream(f.inputStream().buffered()).use { d ->
                if (d.readInt() != STATE_MAGIC || d.readInt() != STATE_VERSION) return null
                val slot = d.readInt(); val total = d.readInt(); val page = d.readInt(); val count = d.readInt()
                val n = d.readInt()
                if (n !in 0..FullRecordCodec.MAX_PACKETS_PER_PAGE) return null
                val held = HashMap<Int, FullRecordAssembler.Held>()
                repeat(n) {
                    val i = d.readInt(); val first = d.readInt(); val cnt = d.readInt(); val len = d.readInt()
                    if (len !in 0..FullRecordPacket.PAYLOAD_CAPACITY) return null
                    val buf = ByteArray(len); d.readFully(buf)
                    held[i] = FullRecordAssembler.Held(first, cnt, buf)
                }
                State(slot, total, page, count, held)
            }
        } catch (e: java.io.IOException) {
            null
        }
    }

    private fun writeAtomic(target: File, body: (FileOutputStream) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { body(it); it.flush(); it.fd.sync() }
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "could not replace $target" }
        }
    }

    private fun load(f: File): Properties? =
        if (!f.exists()) null else try { Properties().apply { f.inputStream().use { load(it) } } } catch (e: java.io.IOException) { null }

    private fun save(f: File, p: Properties) = writeAtomic(f) { p.store(it, null) }

    private companion object {
        const val STATE_MAGIC = 0x53504652      // "SPFR"
        const val STATE_VERSION = 1
    }
}
