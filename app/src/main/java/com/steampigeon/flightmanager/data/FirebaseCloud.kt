package com.steampigeon.flightmanager.data

import android.content.Context
import android.net.Uri
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.steampigeon.flightmanager.SpLog
import com.steampigeon.flightmanager.data.fullrecord.FullRecordCodec
import com.steampigeon.flightmanager.data.fullrecord.FullRecordStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private const val TAG = "FirebaseCloud"

/**
 * The web store for full records (locator ADR "The radio download becomes the
 * primary data path", decisions 8 and 9): Firebase Auth for who the user is,
 * Cloud Storage for the files.
 *
 * Each finished record goes up as two files, its raw v8 bytes and the CSV the
 * locator's USB export prints, under
 *     users/{uid}/locators/{locator_id}/{record_ts}_{crc}.{bin,csv}
 * which `firebase/storage.rules` limits to the signed-in owner.  Sharing is the
 * file's download URL and its token (decision 9), so nothing here grants access.
 *
 * Off unless the app was built with a google-services.json: without one the
 * Google Services plugin is not applied, no FirebaseApp exists, and [available]
 * is false.  The Google sign-in web client id is read the same way, as a resource
 * the plugin generates, so its absence is a runtime "unavailable" rather than a
 * build failure.
 */
object FirebaseCloud : FlightUploader {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploadLock = Mutex()
    private var appContext: Context? = null

    private val _account = MutableStateFlow<String?>(null)
    /** The signed-in user's email (or name), null when signed out. */
    val account: StateFlow<String?> = _account.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    var available = false
        private set

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        available = FirebaseApp.getApps(context).isNotEmpty()
        if (!available) { SpLog.d(TAG, "no google-services.json in this build: cloud upload off"); return }
        val auth = FirebaseAuth.getInstance()
        auth.addAuthStateListener { a ->
            _account.value = a.currentUser?.let { it.email ?: it.displayName ?: it.uid }
            if (a.currentUser != null) uploadPending()
        }
        FullRecordHub.uploader = this
    }

    // ---- sign-in -------------------------------------------------------------------

    suspend fun signInWithEmail(email: String, password: String, create: Boolean): Boolean = guarded {
        val auth = FirebaseAuth.getInstance()
        if (create) auth.createUserWithEmailAndPassword(email, password).await()
        else auth.signInWithEmailAndPassword(email, password).await()
    }

    /** Google sign-in through Credential Manager.  [activityContext] must be an Activity. */
    suspend fun signInWithGoogle(activityContext: Context): Boolean = guarded {
        val webClientId = webClientId(activityContext) ?: error("no Google sign-in client id in this build")
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClientId)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val result = CredentialManager.create(activityContext).getCredential(activityContext, request)
        val cred = result.credential
        if (cred !is CustomCredential || cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
            error("unexpected credential type")
        val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
        FirebaseAuth.getInstance().signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
    }

    fun signOut() { if (available) FirebaseAuth.getInstance().signOut() }

    // The Google Services plugin generates this from google-services.json.  Looked
    // up by name so a build without the file still compiles.
    private fun webClientId(ctx: Context): String? {
        val id = ctx.resources.getIdentifier("default_web_client_id", "string", ctx.packageName)
        return if (id != 0) ctx.getString(id) else null
    }

    private suspend fun guarded(block: suspend () -> Unit): Boolean = try {
        _lastError.value = null
        block(); true
    } catch (e: Exception) {
        SpLog.d(TAG, "sign-in failed: ${e.message}")
        _lastError.value = e.message ?: e.javaClass.simpleName
        false
    }

    // ---- upload ------------------------------------------------------------------------

    override fun enqueue(record: FullRecordStore.Finished) {
        if (!available || FirebaseAuth.getInstance().currentUser == null) return
        scope.launch { upload(record) }
    }

    /** Everything finished on the phone and not yet in the store; run on sign-in. */
    fun uploadPending() {
        if (!available) return
        scope.launch {
            for (r in FullRecordHub.finished.value) if (!r.uploadedBin || !r.uploadedCsv) upload(r)
        }
    }

    private fun path(uid: String, k: FullRecordStore.Key, ext: String) =
        "users/$uid/locators/${k.locatorId}/${k.recordTs}_%08x.$ext".format(k.crc)

    private suspend fun upload(r: FullRecordStore.Finished) = uploadLock.withLock {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@withLock
        val root = FirebaseStorage.getInstance().reference
        try {
            // putFile is the SDK's resumable upload: a dropped connection resumes
            // within the session.  A record is ~0.55 MB, so one that is cut off
            // by the app being killed simply starts over at the next app start
            // (the auth listener fires then and runs uploadPending).
            if (!r.uploadedBin) {
                root.child(path(uid, r.key, "bin")).putFile(Uri.fromFile(r.bin), meta(r, "application/octet-stream")).await()
                FullRecordHub.markUploaded(r.key, bin = true)
            }
            if (!r.uploadedCsv && r.csv.exists()) {
                root.child(path(uid, r.key, "csv")).putFile(Uri.fromFile(r.csv), meta(r, "text/csv")).await()
                FullRecordHub.markUploaded(r.key, csv = true)
            }
            SpLog.d(TAG, "uploaded ${r.key.name}")
        } catch (e: Exception) {
            SpLog.d(TAG, "upload of ${r.key.name} failed, kept for the next try: ${e.message}")
        }
    }

    private fun meta(r: FullRecordStore.Finished, type: String) = StorageMetadata.Builder()
        .setContentType(type)
        .setCustomMetadata("locator_id", r.key.locatorId.toString())
        .setCustomMetadata("record_ts", r.key.recordTs.toString())
        .setCustomMetadata("record_crc", "%08x".format(r.key.crc))
        .setCustomMetadata("samples", r.samples.toString())
        .setCustomMetadata("slot", r.slot.toString())
        .build()

    // ---- the history's second copy (ADR decision 6b) ------------------------------------

    /**
     * The record for (locator, flight time) from the web store, saved to the phone
     * like a transferred one.  Null when signed out, absent, or it fails its CRC.
     */
    suspend fun fetch(locatorId: Long, recordTs: Long, slot: Int, apogeeM: Float): FullRecordStore.Finished? {
        if (!available) return null
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val dir = FirebaseStorage.getInstance().reference.child("users/$uid/locators/$locatorId")
                val item = dir.listAll().await().items.firstOrNull {
                    it.name.startsWith("${recordTs}_") && it.name.endsWith(".bin")
                } ?: return@withContext null
                val crc = item.name.removePrefix("${recordTs}_").removeSuffix(".bin").toLongOrNull(16)
                    ?: return@withContext null
                val bytes = item.getBytes(4L * 1024 * 1024).await()
                if (bytes.size % FullRecordCodec.SAMPLE_SIZE != 0 || FullRecordCodec.crc32(bytes) != crc) {
                    SpLog.d(TAG, "${item.name} failed its CRC; ignored")
                    return@withContext null
                }
                FullRecordHub.adopt(FullRecordStore.Key(locatorId, recordTs, crc), slot, apogeeM, bytes,
                    uploaded = true)
            } catch (e: Exception) {
                SpLog.d(TAG, "fetch failed: ${e.message}")
                null
            }
        }
    }
}
