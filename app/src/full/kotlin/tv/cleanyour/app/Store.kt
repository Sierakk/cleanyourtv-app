package tv.cleanyour.app

import tv.cleanyour.core.flows.StoreRules
import tv.cleanyour.core.flows.UpdateChecker
import tv.cleanyour.core.registry.ArtifactFetcher
import tv.cleanyour.core.registry.HttpFetcher
import tv.cleanyour.core.trust.DownloadHosts

// The full build (GitHub and F-Droid, STORES-PLAN 1): everything the app did before the store
// flavours, unchanged.
object Store : StorePorts {
    override val flavour = "full"
    override val hosts: Set<String> = DownloadHosts.ALL
    override val rules = StoreRules.FULL
    override val updatesMayGoOff = rules.updatesOff
    override val releasePage: String = RELEASE_PAGE

    // D12: one GET on user action (Settings, "Check for updates"); "couldn't check" when GitHub
    // does not answer or the repository has no published release yet.
    override fun updateChecker(http: HttpFetcher) = UpdateChecker { current ->
        val r = http.get(RELEASES_URL, null, null)
        if (r.code != 200) { r.body?.close(); null } else {
            val body = r.body?.use { it.readBytes().decodeToString() } ?: return@UpdateChecker null
            val tag = Regex("\"tag_name\"\\s*:\\s*\"v?([0-9][0-9A-Za-z.]*)\"").find(body)?.groupValues?.get(1) ?: return@UpdateChecker null
            if (offered(tag, current)) tag else ""
        }
    }

    // Whether the latest release `tag` is worth offering to `current`: any other version, as before,
    // except on a test build ("0.3.5-test1", a GitHub pre-release that /releases/latest never
    // returns), which is offered only a higher version or its own release, never the one before it.
    internal fun offered(tag: String, current: String): Boolean {
        if (tag == current) return false
        if ('-' !in current) return true
        fun numbers(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = numbers(tag)
        val b = numbers(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return '-' !in tag
    }

    override fun addOnFetcher(make: () -> ArtifactFetcher): ArtifactFetcher = make()

    const val RELEASES_URL = "https://api.github.com/repos/udhaykumarbala/cleanyourtv-app/releases/latest"
    const val RELEASE_PAGE = "https://github.com/udhaykumarbala/cleanyourtv-app/releases"
}
