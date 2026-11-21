package dev.aiauto.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything that syncs between devices. Keys and tokens never sync: each device keeps its own, encrypted.
 * [settings] holds plain settings as strings, so new ones sync without a format change.
 */
@Serializable
data class SyncSnapshot(
    val version: Int = 1,
    val repos: List<RepoRef> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val settings: Map<String, String> = emptyMap(),
    /** When [settings] last changed on any device. The newer side wins as a whole. */
    val settingsChangedMillis: Long = 0,
    val chat: List<ChatExchange> = emptyList(),
)

object SyncMerge {
    /** Messages kept per task in the synced copy, so the gist stays small. Each device keeps its own full history. */
    const val MESSAGES_PER_TASK = 60

    /**
     * Combines two snapshots. Repos are a union. Each task comes from whichever side changed it last, keeping every
     * message either side has. Settings come from whichever side changed them last. The car chat keeps the newest
     * exchanges from both.
     */
    fun merge(local: SyncSnapshot, remote: SyncSnapshot): SyncSnapshot {
        val repos = (local.repos + remote.repos).distinctBy { it.fullName.lowercase() }
        val remoteTasks = remote.tasks.associateBy { it.id }
        val ids = (local.tasks.map { it.id } + remote.tasks.map { it.id }).distinct()
        val localTasks = local.tasks.associateBy { it.id }
        val tasks = ids.map { id ->
            val a = localTasks[id]
            val b = remoteTasks[id]
            when {
                a == null -> b!!
                b == null -> a
                else -> {
                    val newer = if (b.updatedMillis > a.updatedMillis) b else a
                    newer.copy(
                        messages = (a.messages + b.messages).distinctBy { it.id }.sortedBy { it.timeMillis },
                        // Unsent prompts never sync, so keep this device's own unless another device took the task over.
                        pendingPrompt = if (newer.device == a.device) a.pendingPrompt else newer.pendingPrompt,
                    )
                }
            }
        }.sortedByDescending { it.updatedMillis }
        val remoteSettingsWin = remote.settingsChangedMillis > local.settingsChangedMillis
        val chat = (local.chat + remote.chat)
            .distinctBy { it.timeMillis to it.said }
            .sortedBy { it.timeMillis }
            .takeLast(ChatMemory.KEEP)
        return SyncSnapshot(
            repos = repos,
            tasks = tasks,
            settings = if (remoteSettingsWin) remote.settings else local.settings,
            settingsChangedMillis = maxOf(local.settingsChangedMillis, remote.settingsChangedMillis),
            chat = chat,
        )
    }

    /** The copy that goes in the gist: recent messages only, and nothing waiting to be sent. */
    fun forUpload(snapshot: SyncSnapshot): SyncSnapshot = snapshot.copy(
        tasks = snapshot.tasks.map { it.copy(messages = it.messages.takeLast(MESSAGES_PER_TASK), pendingPrompt = null) },
    )
}

/**
 * Keeps a [SyncSnapshot] in one secret gist on the user's GitHub account, found by its file name, so every device
 * signed in with the same GitHub account shares it. The token needs the Gists permission set to read and write.
 */
class GistSync(private val github: GitHub) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    /** Finds the sync gist, or null when no device has made one yet. */
    fun find(): String? = github.myGists().firstOrNull { FILE in it.files }?.id

    fun read(gistId: String): SyncSnapshot? =
        github.gistFile(gistId, FILE)?.takeIf { it.isNotBlank() }?.let { json.decodeFromString(SyncSnapshot.serializer(), it) }

    fun write(gistId: String, snapshot: SyncSnapshot) = github.updateGist(gistId, FILE, encode(snapshot))

    fun create(snapshot: SyncSnapshot): String = github.createGist(DESCRIPTION, FILE, encode(snapshot))

    /**
     * One round: reads the gist (making it if there is none), merges it with [local], and writes the result back
     * when it differs. Returns the gist id and the merged snapshot for the caller to apply locally. Blocking.
     */
    fun sync(knownGistId: String?, local: SyncSnapshot): Pair<String, SyncSnapshot> {
        val id = knownGistId ?: find()
        if (id == null) {
            // Marked as changed, so a device that joins later with untouched defaults takes these settings.
            val first = local.copy(settingsChangedMillis = maxOf(1L, local.settingsChangedMillis))
            return create(SyncMerge.forUpload(first)) to first
        }
        val remote = try {
            read(id)
        } catch (e: GitHubException) {
            // The gist was deleted. Start a new one.
            if (e.code == 404 && knownGistId != null) return sync(null, local)
            throw e
        }
        val merged = if (remote == null) local else SyncMerge.merge(local, remote)
        val upload = SyncMerge.forUpload(merged)
        if (remote == null || upload != SyncMerge.forUpload(remote)) write(id, upload)
        return id to merged
    }

    private fun encode(snapshot: SyncSnapshot) = json.encodeToString(SyncSnapshot.serializer(), snapshot)

    companion object {
        const val FILE = "cruise-control-sync.json"
        const val DESCRIPTION = "Cruise Control sync data (repos, tasks and settings). Keys and tokens are not stored here."
    }
}
