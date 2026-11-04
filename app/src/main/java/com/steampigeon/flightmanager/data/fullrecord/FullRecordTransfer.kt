package com.steampigeon.flightmanager.data.fullrecord

/**
 * The app side of the background full-record transfer (ADR "The radio download
 * becomes the primary data path", decisions 5, 6, 6a and 6b).  The locator side
 * is `FullRecordSender.hpp`; read its header comment first.
 *
 * Paced by the locator's periodic frames, because the receiver forwards app
 * frames only in the window after one (50-700 ms).  Each periodic frame from the
 * connected locator allows ONE uplink, chosen by the rules the sender expects:
 *   - a page is complete             -> request the next page
 *   - any packet of the page arrived since the last uplink, even a duplicate
 *                                    -> FullRecordAck (the held bitmap)
 *   - nothing arrived at all         -> repeat the request; that is what
 *                                       restarts a suspended, aborted or
 *                                       never-heard transfer
 *   - every record done              -> request page 0xFFFF (stop)
 *
 * "Even a duplicate" matters.  The sender resends from its first packet not yet
 * ACKed, so when an ACK is lost it sends packets the app already holds.  An app
 * that answered duplicates with a request (which changes nothing for a running
 * sender) would never tell it so, and the two would loop on the same window
 * forever.  JVM-tested under 10 % loss each way.
 *
 * Over the USB cable (decision 6c) the same controller runs unchanged: the
 * locator's periodic frames come up the cable instead, its [Link] sends down the
 * cable, and the locator answers on the path it was last asked on, so a record
 * moves between radio and cable mid-page and keeps every packet it holds.
 *
 * Triggers (decision 6): pickup, sustained signal, the button, or the cable
 * being plugged in ([onCable]).  Every one is
 * held behind the gates the locator applies itself: disarmed, and on the ground
 * (WaitingLaunch, or Landed).  The automatic triggers additionally need a landing
 * seen from this locator since its last finished transfer: a locator disarmed
 * after landing goes straight back to WaitingLaunch (Factory's pad reset), which
 * on the wire looks exactly like one on the pad before a flight, and a transfer
 * started automatically on the pad would slow every command during prep to the
 * ~5.5 s cycle for nothing.  The cable and the button are deliberate acts and
 * need no landing.
 *
 * Pure Kotlin with an injected clock and link, so the JVM tests drive it against
 * a simulated locator.  The ViewModel feeds it frames and hands it a [Link] over
 * BluetoothService.
 */
class FullRecordTransfer(
    private val store: FullRecordStore,
    private val link: Link,
    private val listener: Listener = object : Listener {},
) {
    interface Link {
        fun sendRequest(record: Int, page: Int): Boolean
        fun sendAck(recordCrc: Long, page: Int, packetCount: Int, bitmap: ByteArray): Boolean
        /** FlightMetadataRequest: puts the locator in its listing mode until [exitListing]. */
        fun requestListing(): Boolean
        /** DisarmRequest: takes the locator out of the listing mode, back to its periodic cycle. */
        fun exitListing(): Boolean
    }

    interface Listener {
        fun onStatus(status: Status) {}
        fun onRecordFinished(record: FullRecordStore.Finished) {}
        fun log(msg: String) {}
        /** Records on the connected locator not yet on the phone; null until its list is known. */
        fun onPending(count: Int?) {}
    }

    /** One archive slot, from FlightMetadata. */
    data class Slot(val record: Int, val timestampS: Long, val apogeeM: Float)

    enum class Trigger { Button, Pickup, Signal, Cable }

    sealed class Status {
        object Idle : Status()
        /** Started, waiting for the locator to be disarmed on the ground and in range. */
        data class Waiting(val trigger: Trigger) : Status()
        data class Listing(val trigger: Trigger) : Status()
        data class Transferring(
            val trigger: Trigger,
            val slot: Slot,
            val recordIndex: Int,      // 1-based, of [recordCount]
            val recordCount: Int,
            val decoded: Int,
            val totalSamples: Int,     // 0 until the first packet
            val page: Int,
            val pageCount: Int,
        ) : Status()
        data class Finished(val records: Int, val failed: Int) : Status()
    }

    class Settings(
        @Volatile var signalTrigger: Boolean = true,
        @Volatile var pickupTrigger: Boolean = true,
        @Volatile var signalThresholdDbm: Int = SIGNAL_THRESHOLD_DBM,
    )
    val settings = Settings()

    var status: Status = Status.Idle
        private set(v) { field = v; listener.onStatus(v) }

    /**
     * How many of the connected locator's records are not on the phone yet, or
     * null while its flight list is unknown.  Simulated flights count: the button
     * transfers every record the locator holds (Frank, 2026-11-04).
     */
    var pending: Int? = null
        private set(v) { if (field != v) { field = v; listener.onPending(v) } }

    private fun publishPending() {
        val id = locatorId
        val list = slots
        pending = if (id == null || list == null) null
            // An empty slot (no apogee) is listed but has nothing to transfer.
            else list.count { it.apogeeM > 0f && store.finished(id, it.timestampS) == null }
    }

    val active get() = status is Status.Waiting || status is Status.Listing || status is Status.Transferring

    // ---- per-locator trigger state -----------------------------------------
    private var locatorId: Long? = null
    private var lastPeriodicMs = 0L
    private var signalSinceMs: Long? = null
    private var pickedUpLast = false
    private var pickupFired = false
    private var cableFired = false

    // ---- the run ------------------------------------------------------------
    private var trigger = Trigger.Button
    private var slots: List<Slot>? = null          // the locator's list, newest first
    private var listingAskedMs = 0L
    private var listingAttempts = 0
    private var queue = ArrayDeque<Slot>()
    private var queueSize = 0
    private var done = 0
    private var failed = 0
    private var cur: Slot? = null
    private var curKey: FullRecordStore.Key? = null
    private var asm: FullRecordAssembler? = null
    private var resume: FullRecordStore.Partial? = null
    private var rxSinceUplink = false
    private var pageDone = false
    private var dirty = false
    private var foreignStreak = 0
    private var mismatches = 0
    private var stopPending = false
    private var gateOpen = false

    /** The record being transferred, for the history's partial view (6b). */
    fun partialView(): Pair<Slot, FullRecordAssembler>? {
        val s = cur ?: return null
        val a = asm ?: return null
        return s to a
    }

    // ---- inputs --------------------------------------------------------------

    /**
     * A PreLaunchData or TelemetryData frame from the CONNECTED locator.
     * [flightState] is the FlightStates value (PreLaunchData is WaitingLaunch).
     */
    fun onPeriodic(nowMs: Long, locatorId: Long, armed: Boolean, flightState: Int, rssiDbm: Int, pickedUp: Boolean,
                   simulated: Boolean = false) {
        if (this.locatorId != locatorId) switchLocator(locatorId)
        val onGround = flightState == FLIGHT_STATE_WAITING_LAUNCH || flightState == FLIGHT_STATE_LANDED
        gateOpen = !armed && onGround

        // A simulated landing (locator ADR-0041) is not a rocket to recover: it
        // must not start an automatic transfer.  The button still can.
        if (flightState == FLIGHT_STATE_LANDED && !simulated) setLandingSeen(true)
        if (armed || !onGround) {
            // Armed or flying: the locator forgets its side (the slot may be
            // rewritten), so ours stops too.  Partials stay on disk, keyed by CRC.
            if (active) abort("locator armed or off the ground")
            slots = null
            // A flight is under way, so the last run's "finished" no longer
            // describes the locator (Frank, 2026-11-04: it still read complete
            // after the next simulated flight).
            if (status is Status.Finished) status = Status.Idle
            publishPending()
        }

        // Signal: sustained over the threshold, measured on consecutive frames.
        val gap = lastPeriodicMs != 0L && nowMs - lastPeriodicMs > SIGNAL_MAX_GAP_MS
        lastPeriodicMs = nowMs
        if (rssiDbm < settings.signalThresholdDbm || gap) signalSinceMs = null
        if (rssiDbm >= settings.signalThresholdDbm && signalSinceMs == null) signalSinceMs = nowMs
        val signalReady = signalSinceMs?.let { nowMs - it >= SIGNAL_SUSTAIN_MS } == true

        if (pickedUp && !pickedUpLast) pickupFired = true
        pickedUpLast = pickedUp

        // Evaluated only through the gate, so a trigger seen while the locator is
        // still armed after landing (pickup comes first, then the disarm) is kept
        // until the disarm opens the gate, rather than spent on a run that the
        // armed state would abort at once.
        if (!active && gateOpen) {
            when {
                cableFired -> begin(Trigger.Cable)
                !landingSeen() -> {}
                settings.pickupTrigger && pickupFired -> begin(Trigger.Pickup)
                settings.signalTrigger && signalReady -> begin(Trigger.Signal)
            }
        }
        if (active) cableFired = false     // a run already going carries on over the cable
        if (active && gateOpen) uplink(nowMs)
        else if (stopPending && gateOpen) { link.sendRequest(cur?.record ?: 0, STOP_PAGE); stopPending = false }
    }

    /** The locator's FlightMetadata, however it was asked for. */
    fun onListing(locatorId: Long, list: List<Slot>) {
        if (this.locatorId != null && this.locatorId != locatorId) return
        slots = list.filter { it.timestampS > 0 }.sortedByDescending { it.timestampS }
        publishPending()
        // A record the last run did not cover makes its "finished" stale.
        if (status is Status.Finished && (pending ?: 0) > 0) status = Status.Idle
        if (status is Status.Listing) {
            link.exitListing()
            plan()
        }
    }

    /**
     * The USB cable is in and the locator answered over it: start a run at the
     * next periodic frame the gate allows, or carry on the one running.
     */
    fun onCable() { cableFired = true }

    /** The user tapped "Transfer new flight data". */
    fun start(trigger: Trigger = Trigger.Button) { if (!active) begin(trigger) }

    /**
     * The user stopped it.  What arrived so far stays on disk and resumes next
     * time.  It also stands down the automatic triggers until the next landing,
     * or a strong signal would start it again five seconds later.
     */
    fun stop() {
        if (!active) return
        saveIfDirty()
        setLandingSeen(false)
        stopPending = cur != null
        finishRun()
    }

    /** A FullRecordData / FullRecordParity frame, from any locator. */
    // Per-cycle tallies for the uplink log line (2026-11-04: a radio download
    // stalled for minutes at one packet, and nothing said whether the window
    // was arriving, arriving as duplicates, or not arriving at all).
    private var cyNew = 0; private var cyDup = 0; private var cyOther = 0
    private var cyForeign = 0; private var cyBad = 0; private var cyIgnored = 0

    fun onFrame(frame: ByteArray) {
        val p = FullRecordPacket.parse(frame)
        if (p == null) { cyBad++; return }
        val s = cur ?: run { cyIgnored++; return }
        if (status !is Status.Transferring || p.record != s.record) { cyIgnored++; return }

        if (p.isMarker) {
            if (p.totalSamples == 0) {                     // empty or missing slot
                listener.log("slot ${s.record} is empty")
                nextRecord(failedOne = false)
            }
            return
        }

        var a = asm
        if (a == null) {
            val key = FullRecordStore.Key(locatorId ?: return, s.timestampS, p.recordCrc)
            if (store.isFinished(key)) { done++; nextRecord(false); return }
            curKey = key
            a = store.open(key, s.record, p.totalSamples)
            asm = a
            resume = null
            if (a.complete) { finishRecord(); return }
            if (a.page != p.page) pageDone = true          // ask for the page we need
        }

        when (a.onPacket(p)) {
            FullRecordAssembler.Result.Foreign -> {
                cyForeign++
                // Another CRC for this slot: it was rewritten.  A handful in a row
                // with nothing of ours means ours is gone; start the slot over.
                if (++foreignStreak >= FOREIGN_RESTART) {
                    listener.log("slot ${s.record} changed under the transfer; restarting it")
                    asm = null; curKey = null; foreignStreak = 0; pageDone = true
                }
                return
            }
            FullRecordAssembler.Result.OtherPage -> { cyOther++; foreignStreak = 0; return }
            FullRecordAssembler.Result.Duplicate -> { cyDup++; foreignStreak = 0; rxSinceUplink = true; return }
            FullRecordAssembler.Result.New -> { cyNew++; rxSinceUplink = true; dirty = true }
            FullRecordAssembler.Result.PageComplete -> { cyNew++; pageDone = true; dirty = true; saveIfDirty() }
            FullRecordAssembler.Result.RecordComplete -> { finishRecord(); return }
            FullRecordAssembler.Result.CrcMismatch -> {
                listener.log("slot ${s.record}: CRC mismatch after a full decode; discarding it")
                curKey?.let { store.discard(it) }
                asm = null; curKey = null; pageDone = true
                if (++mismatches >= MAX_MISMATCHES) nextRecord(failedOne = true)
                return
            }
        }
        foreignStreak = 0
        publish()
    }

    // ---- internals -----------------------------------------------------------

    private fun begin(t: Trigger) {
        trigger = t
        pickupFired = false
        cableFired = false
        signalSinceMs = null
        done = 0; failed = 0
        listingAttempts = 0
        listingAskedMs = 0L
        cur = null
        plan()
    }

    private fun plan() {
        val id = locatorId ?: run { status = Status.Waiting(trigger); return }
        val list = slots ?: run { status = Status.Listing(trigger); return }
        queue = ArrayDeque(list.filter { store.finished(id, it.timestampS) == null })
        queueSize = queue.size
        nextRecord(failedOne = false, counted = false)
    }

    private fun nextRecord(failedOne: Boolean, counted: Boolean = true) {
        if (counted && failedOne) failed++
        asm = null; curKey = null; resume = null
        rxSinceUplink = false; pageDone = false; dirty = false; foreignStreak = 0; mismatches = 0
        val next = queue.removeFirstOrNull()
        if (next == null) {
            stopPending = cur != null
            // Everything this locator holds is on the phone: the next automatic
            // start waits for the next landing.
            if (failed == 0) setLandingSeen(false)
            finishRun()
            return
        }
        cur = next
        resume = store.partial(locatorId!!, next.timestampS)
        publish()
    }

    private fun finishRecord() {
        val s = cur!!; val k = curKey!!; val a = asm!!
        val f = store.finish(k, s.record, s.apogeeM, a)
        done++
        listener.log("slot ${s.record} finished: ${a.totalSamples} samples, ${a.recovered} rebuilt from parity")
        listener.onRecordFinished(f)
        publishPending()
        nextRecord(failedOne = false)
    }

    private fun finishRun() {
        status = Status.Finished(done, failed)
    }

    private fun abort(why: String) {
        listener.log("transfer stopped: $why")
        saveIfDirty()
        cur = null; asm = null; curKey = null; stopPending = false
        status = Status.Idle
    }

    private fun switchLocator(id: Long) {
        // A run started before any locator was heard (the button, at once) is
        // for whichever one is connected first; only a CHANGE of locator ends it.
        if (active && locatorId != null) abort("another locator connected")
        locatorId = id
        landingCache = null
        slots = null
        signalSinceMs = null
        pickedUpLast = false
        pickupFired = false
        lastPeriodicMs = 0L
        publishPending()
    }

    private fun uplink(nowMs: Long) {
        when (status) {
            is Status.Waiting -> plan()
            is Status.Listing -> {
                if (listingAskedMs == 0L || nowMs - listingAskedMs >= LISTING_RETRY_MS) {
                    if (++listingAttempts > MAX_LISTING_ATTEMPTS) {
                        listener.log("the locator never sent its flight list")
                        finishRun(); return
                    }
                    link.requestListing()
                    listingAskedMs = nowMs
                }
                return
            }
            else -> {}
        }
        if (stopPending) {
            link.sendRequest(cur?.record ?: 0, STOP_PAGE)
            stopPending = false
            return
        }
        val s = cur ?: return
        val a = asm
        saveIfDirty()
        val sent = when {
            a == null -> "request p${resume?.page ?: 0} ok=" + link.sendRequest(s.record, resume?.page ?: 0)
            pageDone || !rxSinceUplink || a.packetCount == 0 ->
                "request p${a.page} ok=" + link.sendRequest(s.record, a.page)
            else -> "ack p${a.page} ok=" + link.sendAck(a.recordCrc, a.page, a.packetCount, a.ackBitmap())
        }
        listener.log("cycle: new $cyNew dup $cyDup otherPage $cyOther foreign $cyForeign bad $cyBad " +
            "ignored $cyIgnored; have ${a?.let { "${it.heldCount()}/${it.packetCount} of p${it.page}, " +
                "decoded ${it.decoded}, decode failures ${it.decodeFailures} ${it.lastDecodeFailure}" } ?: "-"}; sent $sent")
        cyNew = 0; cyDup = 0; cyOther = 0; cyForeign = 0; cyBad = 0; cyIgnored = 0
        rxSinceUplink = false
        pageDone = false
    }

    private fun saveIfDirty() {
        val k = curKey ?: return
        val a = asm ?: return
        val s = cur ?: return
        if (!dirty) return
        store.save(k, s.record, a)
        dirty = false
    }

    private fun publish() {
        val s = cur ?: return
        val a = asm
        status = Status.Transferring(
            trigger = trigger,
            slot = s,
            recordIndex = (queueSize - queue.size).coerceAtLeast(1),
            recordCount = queueSize,
            decoded = a?.decoded ?: resume?.committed ?: 0,
            totalSamples = a?.totalSamples ?: resume?.totalSamples ?: 0,
            page = a?.page ?: resume?.page ?: 0,
            pageCount = a?.pageCount ?: resume?.let { FullRecordCodec.pageCount(it.totalSamples) } ?: 0,
        )
    }

    // Persisted (a kill between landing and recovery must not lose it), cached
    // so the 1 Hz frames do not read a file each.
    private var landingCache: Boolean? = null
    private fun landingSeen(): Boolean {
        val id = locatorId ?: return false
        return landingCache ?: (store.flag("landing_%08X".format(id)) == "1").also { landingCache = it }
    }
    private fun setLandingSeen(v: Boolean) {
        val id = locatorId ?: return
        if (landingSeen() == v) return
        store.setFlag("landing_%08X".format(id), if (v) "1" else null)
        landingCache = v
    }

    companion object {
        const val STOP_PAGE = 0xFFFF
        const val FLIGHT_STATE_WAITING_LAUNCH = 0
        const val FLIGHT_STATE_LANDED = 8
        /** ADR decision 6: -100 dBm sustained for 5 s (proposed default). */
        const val SIGNAL_THRESHOLD_DBM = -100
        const val SIGNAL_SUSTAIN_MS = 5_000L
        /** Frames further apart than this break "sustained" (the cycle is ~5.5 s, the beacon 1 s). */
        const val SIGNAL_MAX_GAP_MS = 7_000L
        const val LISTING_RETRY_MS = 4_000L
        const val MAX_LISTING_ATTEMPTS = 6
        const val FOREIGN_RESTART = 8
        const val MAX_MISMATCHES = 2
    }
}
