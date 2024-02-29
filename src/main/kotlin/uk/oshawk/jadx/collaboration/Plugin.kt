package uk.oshawk.jadx.collaboration

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.github.oshai.kotlinlogging.KotlinLogging
import jadx.api.data.impl.JadxCodeData
import jadx.api.plugins.JadxPlugin
import jadx.api.plugins.JadxPluginContext
import jadx.api.plugins.JadxPluginInfo
import jadx.api.plugins.events.types.ReloadProject
import java.io.File
import java.io.FileNotFoundException
import java.util.*
import kotlin.math.max

class Plugin(
    // Use remote? Pluggable for testing. Currently, always use local. TODO: Actual conflict resolution (would need GUI).
    val conflictResolver: (context: JadxPluginContext, remote: RepositoryRename, local: RepositoryRename) -> Boolean? = ::dialogConflictResolver,
) : JadxPlugin {
    companion object {
        const val ID = "jadx-collaboration"
        val LOG = KotlinLogging.logger(ID)
        val GSON: Gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()
    }

    private val options = Options()
    private var context: JadxPluginContext? = null

    override fun getPluginInfo() = JadxPluginInfo(ID, "JADX Collaboration", "Collaboration support for JADX")

    override fun init(context: JadxPluginContext?) {
        this.context = context

        this.context?.registerOptions(options)

        this.context?.guiContext?.addMenuAction("Pull") { this.context?.guiContext?.uiRun(this::pull) }
        this.context?.guiContext?.addMenuAction("Push") { this.context?.guiContext?.uiRun(this::push) }

        this.context?.guiContext?.registerGlobalKeyBinding("$ID.pull", "ctrl BACK_SLASH") { this.context?.guiContext?.uiRun(this::pull) }
        this.context?.guiContext?.registerGlobalKeyBinding("$ID.push", "ctrl shift BACK_SLASH") { this.context?.guiContext?.uiRun(this::push) }
    }

    private fun <R> readRepository(suffix: String, default: R): R? {
        if (options.repository.isEmpty()) {
            LOG.error { "No repository file is set. Configure it in settings." }
            return null
        }

        val repositoryFile = File("${options.repository}$suffix")
        return try {
            val repository = repositoryFile.reader().use {
                GSON.fromJson(it, default!!::class.java) ?: default
            }

            LOG.info { "readRepository: read ${options.repository}$suffix" }

            repository
        } catch (_: FileNotFoundException) {
            LOG.info { "readRepository: using empty ${options.repository}$suffix" }
            default
        } catch (_: Exception) {
            LOG.error { "Repository file (${options.repository}$suffix) corrupt. Requires manual fixing." }
            null
        }
    }

    private fun readLocalRepository() = readRepository(".local", LocalRepository())
    private fun readRemoteRepository() = readRepository("", RemoteRepository())

    private fun <R> writeRepository(suffix: String, repository: R): Unit? {
        if (options.repository.isEmpty()) {
            LOG.error { "No repository file is set. Configure it in settings." }
            return null
        }

        val repositoryFile = File("${options.repository}$suffix")
        return try {
            repositoryFile.writer().use {
                GSON.toJson(repository, it)
            }

            LOG.info { "writeRepository: written ${options.repository}$suffix" }
        } catch (_: FileNotFoundException) {
            LOG.error { "Repository file (${options.repository}$suffix) invalid path." }
            null
        } catch (_: Exception) {
            LOG.error { "Repository file (${options.repository}$suffix) corrupt. Requires manual fixing." }
            null
        }
    }

    private fun writeLocalRepository(repository: LocalRepository) = writeRepository(".local", repository)
    private fun writeRemoteRepository(repository: RemoteRepository) = writeRepository("", repository)

    private fun projectToLocalRepository(localRepository: LocalRepository) {
        val projectRenames = this.context!!.args.codeData.renames.sorted()
        var projectRenamesIndex = 0
        LOG.info { "projectToLocalRepository: ${projectRenames.size} project renames" }

        val oldLocalRepositoryRenames = localRepository.renames
        var oldLocalRepositoryRenamesIndex = 0
        localRepository.renames = mutableListOf()
        LOG.info { "projectToLocalRepository: ${oldLocalRepositoryRenames.size} old local repository renames" }

        while (projectRenamesIndex != projectRenames.size || oldLocalRepositoryRenamesIndex != oldLocalRepositoryRenames.size) {
            val projectRename = projectRenames.getOrNull(projectRenamesIndex)
            val oldLocalRepositoryRename = oldLocalRepositoryRenames.getOrNull(oldLocalRepositoryRenamesIndex)

            // We will need this in all but one case. Not the most efficient (especially with the clone), but this is not going to be the bottleneck.
            val updatedVersionVector = oldLocalRepositoryRename?.versionVector?.toMutableMap() ?: mutableMapOf()
            updatedVersionVector.merge(localRepository.uuid, 1L, Long::plus)

            localRepository.renames.add(when {
                projectRename == null || (oldLocalRepositoryRename != null && projectRename.nodeRef > oldLocalRepositoryRename.nodeRef) -> {
                    // Local repository rename not present in project. Must have been deleted.
                    oldLocalRepositoryRenamesIndex++
                    RepositoryRename(oldLocalRepositoryRename!!.nodeRef, null, updatedVersionVector)
                }

                oldLocalRepositoryRename == null || (projectRename != null && projectRename.nodeRef < oldLocalRepositoryRename.nodeRef) -> {
                    // Project rename not present in local repository. Add it.
                    projectRenamesIndex++
                    RepositoryRename(NodeRef(projectRename.nodeRef), projectRename.newName, updatedVersionVector)
                }

                else -> {
                    projectRenamesIndex++
                    oldLocalRepositoryRenamesIndex++

                    if (projectRename.newName == oldLocalRepositoryRename.newName) {
                        // No change. Keep the local repository rename (no version vector change).
                        oldLocalRepositoryRename
                    } else {
                        // Change. Replace with the project rename.
                        RepositoryRename(NodeRef(projectRename.nodeRef), projectRename.newName, updatedVersionVector)
                    }
                }
            })
        }

        LOG.info { "projectToLocalRepository: ${localRepository.renames.size} new local repository renames" }
    }

    private fun remoteRepositoryToLocalRepository(remoteRepository: RemoteRepository, localRepository: LocalRepository): Boolean? {
        var remoteRepositoryRenamesIndex = 0
        LOG.info { "remoteRepositoryToLocalRepository: ${remoteRepository.renames.size} remote repository renames" }

        val oldLocalRepositoryRenames = localRepository.renames
        var oldLocalRepositoryRenamesIndex = 0
        localRepository.renames = mutableListOf()
        LOG.info { "remoteRepositoryToLocalRepository: ${oldLocalRepositoryRenames.size} old local repository renames" }

        var conflict = false

        while (remoteRepositoryRenamesIndex != remoteRepository.renames.size || oldLocalRepositoryRenamesIndex != oldLocalRepositoryRenames.size) {
            val remoteRepositoryRename = remoteRepository.renames.getOrNull(remoteRepositoryRenamesIndex)
            val oldLocalRepositoryRename = oldLocalRepositoryRenames.getOrNull(oldLocalRepositoryRenamesIndex)

            localRepository.renames.add(when {
                remoteRepositoryRename == null || (oldLocalRepositoryRename != null && remoteRepositoryRename.nodeRef > oldLocalRepositoryRename.nodeRef) -> {
                    // Local repository rename not present in remote repository. Keep it as is.
                    oldLocalRepositoryRenamesIndex++
                    assert(oldLocalRepositoryRename!!.versionVector.size == 1) // If a rename was deleted on remote, an entry should still be present, but with a null new name.
                    oldLocalRepositoryRename
                }

                oldLocalRepositoryRename == null || (remoteRepositoryRename != null && remoteRepositoryRename.nodeRef < oldLocalRepositoryRename.nodeRef) -> {
                    // Remote repository rename not present in local repository. Add it (again, deletions would be explicit),
                    remoteRepositoryRenamesIndex++
                    remoteRepositoryRename
                }

                else -> {
                    remoteRepositoryRenamesIndex++
                    oldLocalRepositoryRenamesIndex++

                    // Compare version vectors and calculate the new one.
                    var remoteRepositoryGreater = 0
                    var oldLocalRepositoryGreater = 0
                    val updatedVersionVector = mutableMapOf<UUID, Long>()
                    for (key in remoteRepositoryRename.versionVector.keys.union(oldLocalRepositoryRename.versionVector.keys)) {
                        val remoteRepositoryValue = remoteRepositoryRename.versionVector.getOrDefault(key, 0L)
                        val oldLocalRepositoryValue = oldLocalRepositoryRename.versionVector.getOrDefault(key, 0L)

                        if (remoteRepositoryValue > oldLocalRepositoryValue) {
                            remoteRepositoryGreater++
                        }

                        if (oldLocalRepositoryValue > remoteRepositoryValue) {
                            oldLocalRepositoryGreater++
                        }

                        updatedVersionVector[key] = max(remoteRepositoryValue, oldLocalRepositoryValue)
                    }

                    when {
                        (remoteRepositoryGreater == 0 && oldLocalRepositoryGreater == 0)
                        || remoteRepositoryRename.newName == oldLocalRepositoryRename.newName -> {
                            // Equal in version vector or value. Use remote (including vector) since our version effectively hasn't updated.
                            assert(remoteRepositoryRename.newName == oldLocalRepositoryRename.newName)
                            remoteRepositoryRename
                        }
                        remoteRepositoryGreater == 0 -> {
                            // Local supersedes remote. Use local. Local vector equals updated vector.
                            oldLocalRepositoryRename
                        }
                        oldLocalRepositoryGreater == 0 -> {
                            // Remote supersedes local. Use remote. Remote vector equals updated vector.
                            remoteRepositoryRename
                        }
                        else -> {
                            // Conflict. Try and resolve
                            conflict = true
                            when (conflictResolver(context!!, remoteRepositoryRename, oldLocalRepositoryRename)) {
                                true -> remoteRepositoryRename  // Use remote (including vector) since our version effectively hasn't updated.
                                false -> RepositoryRename(oldLocalRepositoryRename.nodeRef, oldLocalRepositoryRename.newName, updatedVersionVector)  // Use local with updated vector.
                                null -> {
                                    LOG.error { "Conflict resolution failed." }
                                    return null
                                }
                            }
                        }
                    }
                }
            })
        }

        LOG.info { "remoteRepositoryToLocalRepository: ${localRepository.renames.size} new local repository renames" }

        return conflict
    }

    private fun localRepositoryToProject(localRepository: LocalRepository) {
        LOG.info { "localRepositoryToProject: ${localRepository.renames.size} local repository renames" }
        LOG.info { "localRepositoryToProject: ${this.context!!.args.codeData.renames.size} old project renames" }

        (this.context!!.args.codeData as JadxCodeData).renames = localRepository.renames
                .filter { it.newName != null }
                .map { ProjectRename(it.nodeRef, it.newName!!).convert() }  // The convert is needed due to interface replacement. I wish it wasn't.

        LOG.info { "localRepositoryToProject: ${this.context!!.args.codeData.renames.size} new project renames" }

        context!!.events().send(ReloadProject::class.java.declaredFields.first().get(null) as ReloadProject)  // TODO: Change this when the singleton member name is stable.
    }

    private fun localRepositoryToRemoteRepository(localRepository: LocalRepository, remoteRepository: RemoteRepository) {
        // Overwrite the remote repository with the remote repository (remote should have been merged into local beforehand).

        LOG.info { "localRepositoryToRemoteRepository: ${localRepository.renames.size} local repository renames" }
        LOG.info { "localRepositoryToRemoteRepository: ${remoteRepository.renames.size} old remote repository renames" }

        remoteRepository.renames = localRepository.renames

        LOG.info { "localRepositoryToRemoteRepository: ${remoteRepository.renames.size} new remote repository renames" }
    }

    private fun runScript(script: String): Int {
        if (script.isEmpty()) return 0

        val command = mutableListOf<String>()

        if (System.getProperty("os.name").lowercase(Locale.getDefault()).contains("win")) {
            command.add(0, "powershell.exe")
            command.add(1, "-File")
        }

        command.add(script)
        command.add(options.repository)

        val process = ProcessBuilder(command).start()
        process.waitFor()
        return process.exitValue()
    }

    private fun runPrePullScript() = runScript(this.options.prePull)
    private fun runPostPushScript() = runScript(this.options.postPush)

    private fun runPrePullScriptRepeat(): Unit? {
        for (i in 1..5) {
            when (val exitCode = runPrePullScript()) {
                0 -> break
                1 -> {
                    if (i == 5) {
                        LOG.error { "Pre-pull script failed temporarily on try $i. Aborting." }
                        return null
                    } else {
                        LOG.warn { "Pre-pull script failed temporarily on try $i. Retrying." }
                    }
                }
                else -> {
                    LOG.error { "Pre-pull script failed permanently with exit code $exitCode on try number $i. Aborting," }
                    return null
                }
            }
        }

        return Unit
    }

    private fun pull() {
        // Update local repository with project changes.
        // Pull remote repository into local repository.
        // Update project from local repository.

        val localRepository = readLocalRepository() ?: return

        projectToLocalRepository(localRepository)

        runPrePullScriptRepeat() ?: return

        val remoteRepository = readRemoteRepository() ?: return

        remoteRepositoryToLocalRepository(remoteRepository, localRepository) ?: return

        writeLocalRepository(localRepository) ?: return

        localRepositoryToProject(localRepository)
    }

    private fun push() {
        // Update local repository with project changes.
        // Pull remote repository into local repository until there is no conflict.

        var localRepository: LocalRepository? = null
        for (i in 1..5) {
            localRepository = readLocalRepository() ?: return

            projectToLocalRepository(localRepository)

            // Repeat if there is a conflict. Should limit the chance of race conditions, since the user may take time to resolve conflicts.
            var remoteRepository: RemoteRepository
            do {
                runPrePullScriptRepeat() ?: return

                remoteRepository = readRemoteRepository() ?: return

                val conflict = remoteRepositoryToLocalRepository(remoteRepository, localRepository)
            } while (conflict ?: return)

            localRepositoryToRemoteRepository(localRepository, remoteRepository)

            writeRemoteRepository(remoteRepository) ?: return

            when (val exitCode = runPostPushScript()) {
                0 -> break
                1 -> {
                    if (i == 5) {
                        LOG.error { "Post-push script failed temporarily on try $i. Aborting." }
                        return
                    } else {
                        LOG.warn { "Post-push script failed temporarily on try $i. Retrying." }
                    }
                }

                else -> {
                    LOG.error { "Post-push script failed permanently with exit code $exitCode on try number $i. Aborting," }
                    return
                }
            }
        }

        // Think it is a good idea to do this after the script. If something goes wrong, the on-disk local repository should allow us to recover.
        writeLocalRepository(localRepository!!)

        localRepositoryToProject(localRepository)
    }
}
