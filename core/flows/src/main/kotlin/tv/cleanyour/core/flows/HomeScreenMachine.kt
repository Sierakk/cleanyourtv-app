package tv.cleanyour.core.flows

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tv.cleanyour.core.engine.RunStatus
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.probe.PressedKey
import tv.cleanyour.core.spec.DockLayers

// M2 Home screen: the dock (S3b sheet), the look (theme picker), "Use the TV's own home screen"
// with its preview, the drift banner (D15: the overlay digest re-read on every connect; "Restore
// mine" pushes ours again) and "Update the home screen" with its preview (D4, RFC-LAUNCHER-1).
data class AppChoice(val pkg: PackageName, val name: String)

data class HomeScreenState(
    val tvName: String = "",
    val dock: List<AppChoice> = emptyList(),           // the dock, then Row 2 and Row 3 (DockLayers)
    val apps: List<AppChoice> = emptyList(),           // installed apps for the dock sheet
    val themeId: String? = null,
    val themes: List<ThemeOption> = emptyList(),
    val drift: Boolean = false,
    val stock: Component? = null,                       // the profile's stock launcher, when there is one
    val stockPreview: Plan? = null,
    val busy: Busy? = null,
    val homeScreenOurs: Boolean = true,
    val columns: Int = DockLayers.DEFAULT_COLUMNS,     // apps per dock row for the look on the TV
    val updateTo: String? = null,                       // D4: the bundled launcher's version, when the TV has an older build of ours
    val updatePreview: Plan? = null,
    val tvSideSettings: Boolean = false,                // the TV's launcher has Launcher settings of its own (2.4, versionCode 5, and later)
    val buttons: List<RemoteButton> = emptyList(),      // Remote buttons mapped for this TV, in the order they were mapped
    val buttonsSupported: Boolean = false,              // the new home screen is the TV's and reads "scan:" app_keys (2.6, versionCode 7, and later)
    val capture: ButtonCapture? = null,                 // the press being waited for or just read; null with no Remote buttons sheet step open
)

sealed interface HomeScreenIntent {
    data class SetDock(val dock: List<PackageName>) : HomeScreenIntent
    data class SetTheme(val id: String) : HomeScreenIntent
    data object RestoreMine : HomeScreenIntent
    data object PreviewStock : HomeScreenIntent
    data object UseStock : HomeScreenIntent
    data object CancelPreview : HomeScreenIntent
    data object PreviewUpdate : HomeScreenIntent       // D4
    data object UpdateHomeScreen : HomeScreenIntent    // D4
    data object CaptureButton : HomeScreenIntent                                        // Remote buttons: wait for a press
    data object CancelCapture : HomeScreenIntent
    data class MapButton(val scancode: Int, val pkg: PackageName) : HomeScreenIntent    // the pressed button opens this app
    data class UnmapButton(val scancode: Int) : HomeScreenIntent
}

class HomeScreenMachine(
    private val d: ManageDeps,
    scope: CoroutineScope,
    private val tvId: TvId,
) : Machine<HomeScreenState, HomeScreenIntent, ManageEffect>(HomeScreenState(themes = d.themes.bundled().map { ThemeOption(it.id, it.name, it.description) }), scope) {

    private val writer = TvWriter(d)
    private data class Loaded(val s: HomeScreenState)
    private data class BusyNow(val busy: Busy?)
    private data class Preview(val plan: Plan?)
    private data class Drift(val drift: Boolean)
    private data class UpdatePreview(val plan: Plan?)
    private data class Captured(val capture: ButtonCapture?)
    private var capturing: Job? = null
    private var updated = false   // D4: this screen updated the launcher; the stored snapshot does not know yet

    init { scope.launch { load(); checkDrift() } }

    override fun reduce(state: HomeScreenState, event: Any): HomeScreenState = when (event) {
        is Loaded -> event.s.copy(busy = state.busy, stockPreview = state.stockPreview, drift = state.drift, updatePreview = state.updatePreview, capture = state.capture)
        is Captured -> state.copy(capture = event.capture)
        is UpdatePreview -> state.copy(updatePreview = event.plan)
        is BusyNow -> state.copy(busy = event.busy)
        is Preview -> state.copy(stockPreview = event.plan)
        is Drift -> state.copy(drift = event.drift)
        HomeScreenIntent.CancelPreview -> state.copy(stockPreview = null, updatePreview = null)
        HomeScreenIntent.CaptureButton -> if (state.busy == null && state.buttonsSupported) state.copy(capture = ButtonCapture.Waiting) else state
        HomeScreenIntent.CancelCapture, is HomeScreenIntent.MapButton -> state.copy(capture = null)
        else -> state
    }

    override fun handle(event: Any, before: HomeScreenState, after: HomeScreenState) {
        if (before.busy != null && event is HomeScreenIntent) return
        when (event) {
            is HomeScreenIntent.SetDock -> scope.launch { apply(event.dock, after.themeId, savedKeys()) }
            // RF-LAUNCHER-250 F4: a new look and "Restore mine" push the dock as it was saved, as
            // ModulesMachine does, not the list as the TV shows it now (an app since gone or switched
            // off would drop out and change dock_apps, and the TV's own dock edit would stop applying,
            // upstream #340). Only SetDock changes the list.
            is HomeScreenIntent.SetTheme -> scope.launch { apply(savedDock() ?: after.dock.map { it.pkg }, event.id, savedKeys()) }
            HomeScreenIntent.RestoreMine -> scope.launch { apply(savedDock() ?: after.dock.map { it.pkg }, after.themeId, savedKeys()) }
            HomeScreenIntent.PreviewStock -> after.stock?.let { stock -> scope.launch { send(Preview(writer.preview(tvId, PlanRequest.UseStockHome(stock)))) } }
            HomeScreenIntent.UseStock -> after.stock?.let { stock -> scope.launch { useStock(stock) } }
            HomeScreenIntent.PreviewUpdate -> scope.launch { previewUpdate() }
            HomeScreenIntent.UpdateHomeScreen -> scope.launch { update() }
            HomeScreenIntent.CaptureButton -> if (after.capture == ButtonCapture.Waiting) { capturing?.cancel(); capturing = scope.launch { capture() } }
            HomeScreenIntent.CancelCapture -> { capturing?.cancel(); capturing = null }
            is HomeScreenIntent.MapButton -> if (after.buttonsSupported) scope.launch {
                apply(savedDock() ?: after.dock.map { it.pkg }, after.themeId, RemoteButtons.mapped(savedKeys(), event.scancode, event.pkg), keyService = true)
            }
            is HomeScreenIntent.UnmapButton -> scope.launch {
                apply(savedDock() ?: after.dock.map { it.pkg }, after.themeId, RemoteButtons.unmapped(savedKeys(), event.scancode))
            }
            else -> {}
        }
    }

    private suspend fun load() {
        val tv = d.tv(tvId) ?: return
        val profile = d.profileOf(tv)
        val snap = d.snapshots.latest(tvId)
        val packages = snap?.packages.orEmpty()
        val choice = d.homePrefs.load(tvId)
        // The stored snapshot cannot say which apps are launchable, so the rule without that
        // answer applies: never a home screen, a provider or a system-only package.
        val apps = (snap?.let { DockCandidates.of(it, null, profile, d.facts) } ?: emptySet())
            .map { AppChoice(it, d.nameOf(it, packages[it]?.label)) }.sortedBy { it.name.lowercase() }
        // The dock as the TV shows it: an app no longer there (gone, switched off) is skipped and the
        // later ones move up a slot, as the launcher does.
        val columns = DockLayers.columnsOf(theme(choice?.themeId)?.doc)
        val shown = { p: PackageName -> snap == null || packages[p]?.enabled.let { it != null && it != EnabledState.DISABLED && it != EnabledState.DISABLED_USER } }
        val dock = DockLayers.decode(choice?.dock.orEmpty(), shown, columns).map { AppChoice(it, d.nameOf(it, packages[it]?.label)) }
        val buttons = choice?.keys.orEmpty().mapNotNull { RemoteButtons.entry(it) }
            .map { (code, pkg) -> RemoteButton(code, AppChoice(pkg, d.nameOf(pkg, packages[pkg]?.label))) }
        val launcherVersion = packages[Launcher.pkg]?.versionCode ?: 0
        send(Loaded(HomeScreenState(tv.nickname, dock, apps, choice?.themeId, state.value.themes, stock = profile?.stockLaunchers?.firstOrNull(),
            homeScreenOurs = tv.launcher != null, columns = columns, updateTo = updateTo(tv, packages),
            tvSideSettings = launcherVersion >= FIRST_WITH_TV_SETTINGS,
            buttons = buttons, buttonsSupported = tv.launcher != null && launcherVersion >= RemoteButtons.FIRST_WITH_SCANCODES)))
    }

    // D4: the bundled launcher is newer than ours on the TV (same signer, lower versionCode). The
    // stored snapshot says which build is installed and with which key; one from before the app
    // read signers falls back to what Setup recorded for this TV. The planner checks again on a
    // fresh snapshot before anything is installed.
    private fun updateTo(tv: tv.cleanyour.core.model.Tv, packages: Map<PackageName, tv.cleanyour.core.model.PackageState>): String? {
        if (updated) return null
        val pin = runCatching { d.launcherPin.bundled() }.getOrNull() ?: return null
        val installed = packages[pin.pkg] ?: return null
        val signer = installed.signer ?: tv.launcher?.signer ?: return null
        return pin.versionName.takeIf { signer == pin.signer && installed.versionCode < pin.versionCode }
    }

    private suspend fun previewUpdate() {
        val pin = attempt { d.launcherPin.bundled() }.getOrNull() ?: return
        val plan = writer.preview(tvId, PlanRequest.UpdateLauncher(pin), fresh = true)
        if (plan == null || plan.steps.isEmpty()) {
            // Nothing to update after all (a fresher look at the TV), or it cannot be planned.
            if (plan != null) updated = true
            effect(ManageEffect.Snackbar(if (plan != null) SnackKind.HOME_UPDATED else SnackKind.FAILED, pin.versionName, false))
            load()
            return
        }
        send(UpdatePreview(plan))
    }

    // "Update the home screen": the bundled launcher over ours, in place (RFC-LAUNCHER-1). The dock,
    // the look (config.json) and the card answers stay on the TV, since only an uninstall removes
    // them, so nothing is pushed again.
    private suspend fun update() {
        send(UpdatePreview(null))
        val pin = attempt { d.launcherPin.bundled() }.getOrNull() ?: return effect(ManageEffect.Snackbar(SnackKind.FAILED, "", false))
        val r = writer.write(tvId, PlanRequest.UpdateLauncher(pin), fresh = true) { send(BusyNow(it)) }
        send(BusyNow(null))
        when {
            r is WriteResult.Done && r.finished.status == RunStatus.COMPLETED -> {
                updated = true
                d.tv(tvId)?.let { t -> t.launcher?.let { l -> d.tvs.upsert(t.copy(launcher = l.copy(versionCode = pin.versionCode, signer = pin.signer))) } }
                effect(ManageEffect.Snackbar(SnackKind.HOME_UPDATED, pin.versionName, false))
            }
            r is WriteResult.GateCancelled || r is WriteResult.NoTv || r is WriteResult.Paused -> {}   // Paused: the open-run card (O2)
            else -> effect(ManageEffect.Snackbar(SnackKind.FAILED, "", false))
        }
        load()
    }

    // Remote buttons: one press on the TV's remote, read on the TV (ProbeScripts.NEXT_KEY). Read only; nothing
    // is written until the person chooses an app, and then through an ApplyTheme plan like any look change.
    private suspend fun capture() {
        val keys = d.remoteKeys ?: return send(Captured(ButtonCapture.Failed))
        val session = d.access.live(tvId) ?: return send(Captured(ButtonCapture.Failed))
        val key = attempt { keys.nextKey(session) }.getOrElse { return send(Captured(ButtonCapture.Failed)) }
        send(Captured(when (key?.verdict) {
            null -> ButtonCapture.NothingPressed
            PressedKey.Verdict.SHARED -> ButtonCapture.Shared
            PressedKey.Verdict.NEEDED -> ButtonCapture.Needed
            PressedKey.Verdict.MAPPABLE -> ButtonCapture.Pressed(key.scancode)
        }))
    }

    private suspend fun checkDrift() {
        val tv = d.tv(tvId) ?: return
        val ours = tv.launcher?.ourOverlayDigest ?: return
        val session = d.access.live(tvId) ?: return
        val now = attempt { d.configTransport.readDigest(session) }.getOrNull()
        send(Drift(now != null && now != ours))
    }

    companion object {
        // Launcher 2.4 (versionCode 5) added Launcher settings on the TV (RF-LAUNCHER-240 F16).
        const val FIRST_WITH_TV_SETTINGS = 5L
    }

    private fun theme(id: String?) = id?.let { d.themes.bundled().firstOrNull { t -> t.id == it } }

    private suspend fun savedDock(): List<PackageName>? = d.homePrefs.load(tvId)?.dock

    private suspend fun savedKeys(): List<String> = d.homePrefs.load(tvId)?.keys.orEmpty()

    // Dock and look go to the TV as one config.json (LauncherConfigCodec), pushed by an ApplyTheme
    // plan; the digest we wrote is remembered so the next connect can warn on drift. dock_apps is
    // the dock and the rows under it in order, rows_below_dock the rows under the dock that hold an
    // app (DockLayers), so the TV shows the rows the sheet showed. module_cards keeps this TV's cards
    // choice (RFC-CARDS-1): a new dock or theme never turns cards on or off.
    // `keys`: the remote buttons the look carries (HomeChoice.keys). `keyService`: a button was just mapped, so
    // the plan also switches on the launcher's HomeKeyService when the TV has it off (the planner says so in
    // Show commands, and refuses when that would also start another app's accessibility service).
    private suspend fun apply(dock: List<PackageName>, themeId: String?, keys: List<String>, keyService: Boolean = false) {
        val cards = Cards.current(d, tvId, d.homePrefs.load(tvId))
        val look = attempt { Look.encode(d.encoder, d.blobs, theme(themeId), dock, cards, keys) }.getOrNull()
            ?: return effect(ManageEffect.Snackbar(SnackKind.FAILED, "", false))
        val kept = look.doc.dockApps.orEmpty()
        val r = writer.write(tvId, PlanRequest.ApplyTheme(look.doc, look.config, look.wallpaper, keyService)) { send(BusyNow(it)) }
        send(BusyNow(null))
        if (r is WriteResult.Done) {
            d.homePrefs.save(tvId, HomeChoice(kept, themeId, cards, keys))
            rememberPushedLook(d, tvId, look.digest)
            send(Drift(false))
            effect(ManageEffect.Snackbar(SnackKind.HOME_SAVED, "", false))
        } else if (r !is WriteResult.GateCancelled && r !is WriteResult.NoTv && r !is WriteResult.Paused) {   // Paused: the open-run card (O2)
            effect(ManageEffect.Snackbar(SnackKind.FAILED, "", false))
        }
        load()
    }

    // "Use the TV's own home screen": re-enables the stock launcher, sets it as home, keeps ours
    // installed (PRODUCT-SPEC M2).
    private suspend fun useStock(stock: Component) {
        send(Preview(null))
        val r = writer.write(tvId, PlanRequest.UseStockHome(stock)) { send(BusyNow(it)) }
        send(BusyNow(null))
        if (r is WriteResult.Done) {
            d.tv(tvId)?.let { d.tvs.upsert(it.copy(launcher = null)) }
            effect(ManageEffect.Snackbar(SnackKind.HOME_SAVED, d.nameOf(stock.pkg), false))
        }
        load()
    }
}
