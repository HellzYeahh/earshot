package dev.earshot

import android.content.Context
import dev.earshot.audio.AudioTuningRepositoryImpl
import dev.earshot.audio.HeadsetEqMemory
import dev.earshot.bt.AppleAdvertScanner
import dev.earshot.bt.AudioLinkMonitor
import dev.earshot.bt.ByteTransport
import dev.earshot.bt.DeviceFamily
import dev.earshot.bt.DeviceFinder
import dev.earshot.call.CallAudioRepositoryImpl
import dev.earshot.call.HeadsetBatteryReader
import dev.earshot.call.HeadsetVolumeControl
import dev.earshot.codec.CodecRepositoryImpl
import dev.earshot.core.AudioTuningRepository
import dev.earshot.core.AutomationRepository
import dev.earshot.core.CallAudioRepository
import dev.earshot.core.CodecRepository
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.MicCheckRepository
import dev.earshot.extras.automation.AutomationRepositoryImpl
import dev.earshot.mic.MicCheckRepositoryImpl
import dev.earshot.service.CrusherHeadphoneRepository
import dev.earshot.service.CrusherProtocol
import dev.earshot.service.GenericHeadphoneRepository
import dev.earshot.service.HeadphoneProtocol
import dev.earshot.service.QcUltra2Protocol
import dev.earshot.service.RoutingHeadphoneRepository
import dev.earshot.service.ServiceHeadphoneRepository
import dev.earshot.service.VendorDriver
import dev.earshot.shortcut.ShortcutEngine
import dev.earshot.shortcut.ShortcutPrefs
import dev.earshot.virtual.DemoDevice
import dev.earshot.virtual.DemoMode
import dev.earshot.virtual.VirtualCrusher
import dev.earshot.virtual.VirtualCrusherTransport
import dev.earshot.virtual.VirtualProfile
import dev.earshot.virtual.VirtualQcUltra2
import dev.earshot.virtual.VirtualTransport
import dev.earshot.virtual.soundcoreModel
import dev.earshot.virtual.virtualProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.ConcurrentHashMap

/**
 * Manual dependency container. One instance per process, owned by [EarshotApp].
 *
 * Everything shares one application-lifetime scope: the repositories register receivers and
 * listeners in their constructors and are never torn down while the process lives.
 */
class AppContainer(private val appContext: Context) {
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Demo mode: talk to an in-process virtual QC Ultra 2 instead of real Bluetooth. */
    val demoMode: DemoMode by lazy { DemoMode(appContext) }

    /** One virtual unit per Bose demo device and process, so its state survives demo reconnects. */
    private val virtualHeadphones = ConcurrentHashMap<DemoDevice, VirtualQcUltra2>()

    private fun virtualUnit(device: DemoDevice, profile: VirtualProfile): VirtualQcUltra2 =
        virtualHeadphones.computeIfAbsent(device) { VirtualQcUltra2(profile = profile) }

    /** One virtual Crusher per process, for the same reason (and one that asks for the pairing). */
    private val virtualCrusher: VirtualCrusher by lazy { VirtualCrusher() }
    private val virtualCrusherSecure: VirtualCrusher by lazy { VirtualCrusher(authenticatedAccess = true) }
    private val virtualMethod360: VirtualCrusher by lazy { VirtualCrusher.method360() }
    private val virtualMethod540: VirtualCrusher by lazy { VirtualCrusher.method540() }

    /** Demo transports are device-specific: only the family the demo device belongs to gets one. */
    private val boseDemoTransport: () -> (() -> ByteTransport)? = {
        if (!demoMode.isEnabledBlocking()) null
        else demoMode.deviceBlocking().let { device ->
            device.virtualProfile()?.let { profile -> VirtualTransport.factory(virtualUnit(device, profile)) }
        }
    }

    private val crusherDemoTransport: () -> (() -> ByteTransport)? = {
        if (!demoMode.isEnabledBlocking()) null
        else when (demoMode.deviceBlocking()) {
            DemoDevice.CRUSHER -> ({ VirtualCrusherTransport(virtualCrusher) })
            DemoDevice.CRUSHER_SECURE -> ({ VirtualCrusherTransport(virtualCrusherSecure) })
            DemoDevice.METHOD_360 -> ({ VirtualCrusherTransport(virtualMethod360) })
            DemoDevice.METHOD_540 -> ({ VirtualCrusherTransport(virtualMethod540) })
            else -> null
        }
    }

    /** One virtual Sleep A30 per process, so its state survives demo reconnects. */
    private val virtualSleepA30: dev.earshot.virtual.VirtualSleepA30 by lazy { dev.earshot.virtual.VirtualSleepA30() }

    /** One virtual unit per other Soundcore demo device and process (Liberty 4 NC, Space One Pro). */
    private val virtualSoundcore = ConcurrentHashMap<DemoDevice, dev.earshot.virtual.VirtualSoundcore>()

    private val soundcoreDemoTransport: () -> (() -> ByteTransport)? = {
        if (demoMode.isEnabledBlocking() && demoMode.deviceBlocking().isSoundcore) {
            val device = demoMode.deviceBlocking()
            val model = device.soundcoreModel()
            if (model == null) {
                { dev.earshot.virtual.VirtualSleepA30Transport(virtualSleepA30) }
            } else {
                val unit = virtualSoundcore.computeIfAbsent(device) { dev.earshot.virtual.VirtualSoundcore(model) }
                ({ dev.earshot.virtual.VirtualSoundcoreTransport(unit) })
            }
        } else null
    }

    /** One virtual Momentum 4 per process, so its state survives demo reconnects. */
    private val virtualSennheiser: dev.earshot.virtual.VirtualSennheiser by lazy { dev.earshot.virtual.VirtualSennheiser() }

    private val sennheiserDemoTransport: () -> (() -> ByteTransport)? = {
        if (demoMode.isEnabledBlocking() && demoMode.deviceBlocking().isSennheiser) {
            { dev.earshot.virtual.VirtualSennheiserTransport(virtualSennheiser) }
        } else null
    }

    /** One virtual Sony WH-1000XM5 per process, so its state survives demo reconnects. */
    private val virtualSony: dev.earshot.virtual.VirtualSony by lazy { dev.earshot.virtual.VirtualSony() }

    private val sonyDemoTransport: () -> (() -> ByteTransport)? = {
        if (demoMode.isEnabledBlocking() && demoMode.deviceBlocking() == DemoDevice.SONY_WH1000XM5) {
            { dev.earshot.virtual.VirtualSonyTransport(virtualSony) }
        } else null
    }

    /** One virtual JBL Wave Buds 2 per process, so its state survives demo reconnects. */
    private val virtualJbl: dev.earshot.virtual.VirtualJbl by lazy { dev.earshot.virtual.VirtualJbl() }

    private val jblDemoTransport: () -> (() -> ByteTransport)? = {
        if (demoMode.isEnabledBlocking() && demoMode.deviceBlocking().isJbl) {
            { dev.earshot.virtual.VirtualJblTransport(virtualJbl) }
        } else null
    }

    /** The QC Ultra 2 command layer (BMAP addresses, payloads and state mapping). */
    private val headphoneProtocol: HeadphoneProtocol by lazy { QcUltra2Protocol() }

    /**
     * Mirrors `AppPreferences.autoConnectOnBluetooth`; read by every connection manager on each
     * Bluetooth event. Before the automation store is up (or if it fails) the answer is yes.
     */
    private val autoConnect: () -> Boolean = { runCatching { automation.preferences.value.autoConnectOnBluetooth }.getOrDefault(true) }

    private val boseHeadphones: ServiceHeadphoneRepository by lazy {
        ServiceHeadphoneRepository(
            appContext, headphoneProtocol, applicationScope, boseDemoTransport,
            demoName = { "Virtual ${runCatching { demoMode.deviceBlocking().displayName }.getOrDefault("QC Ultra")} (demo)" },
            autoConnect = autoConnect,
            prefs = devicePrefs,
            finder = deviceFinder,
        )
    }

    /** The Skullcandy Crusher 1080 ANC (Bragi BOS-RPC): a separate stack behind the same contract. */
    private val crusherHeadphones: CrusherHeadphoneRepository by lazy {
        CrusherHeadphoneRepository(
            appContext, CrusherProtocol(), applicationScope, crusherDemoTransport,
            autoConnect = autoConnect,
            demoName = {
                demoMode.deviceBlocking().let { d ->
                    if (d == DemoDevice.METHOD_360 || d == DemoDevice.METHOD_540) "Virtual ${d.displayName} (demo)" else CrusherHeadphoneRepository.DEMO_NAME
                }
            },
            prefs = devicePrefs,
            finder = deviceFinder,
        )
    }

    /** Soundcore earbuds (Sleep A30 first) over their RFCOMM vendor service. */
    private val soundcoreHeadphones: dev.earshot.service.SoundcoreHeadphoneRepository by lazy {
        dev.earshot.service.SoundcoreHeadphoneRepository(
            appContext, applicationScope, soundcoreDemoTransport,
            autoConnect = autoConnect,
            demoName = {
                demoMode.deviceBlocking().let { d ->
                    if (d == DemoDevice.SOUNDCORE_SLEEP_A30) dev.earshot.service.SoundcoreHeadphoneRepository.DEMO_NAME else "Virtual ${d.displayName} (demo)"
                }
            },
            prefs = devicePrefs,
            finder = deviceFinder,
        )
    }

    /** Sennheiser headsets (Momentum 4, Accentum, Momentum True Wireless …) over GAIA v3. Experimental. */
    private val sennheiserHeadphones: dev.earshot.service.SennheiserHeadphoneRepository by lazy {
        dev.earshot.service.SennheiserHeadphoneRepository(
            appContext, applicationScope, sennheiserDemoTransport,
            autoConnect = autoConnect,
            prefs = devicePrefs,
            finder = deviceFinder,
        )
    }

    /** Sony headphones on MDR v2 (Experimental) over their RFCOMM service by UUID. */
    private val sonyHeadphones: dev.earshot.service.SonyHeadphoneRepository by lazy {
        dev.earshot.service.SonyHeadphoneRepository(
            appContext, applicationScope, sonyDemoTransport,
            autoConnect = autoConnect,
            prefs = devicePrefs,
            finder = deviceFinder,
        )
    }

    /** JBL's 2021–24 models on the Harman "AA" protocol over Bluetooth LE (Experimental). */
    private val jblHeadphones: dev.earshot.service.JblHeadphoneRepository by lazy {
        dev.earshot.service.JblHeadphoneRepository(
            appContext, applicationScope, jblDemoTransport,
            autoConnect = autoConnect,
            prefs = devicePrefs,
            finder = deviceFinder,
        )
    }

    /**
     * Any other headset (AirPods, Galaxy Buds, no-name earbuds): shown by its own name from what Android
     * reports, never sent anything. The same prefs and finder as every other stack, so all of them
     * agree on which device is picked.
     */
    private val genericHeadphones: GenericHeadphoneRepository by lazy {
        GenericHeadphoneRepository(
            appContext, devicePrefs, deviceFinder, applicationScope,
            initialBattery = { address ->
                val device = deviceFinder.deviceFor(address)
                HeadsetBatteryReader.read(privilegedShell, address)
                    ?: device?.let(dev.earshot.bt.DeviceBattery::read)
                    // Standard Battery Service over our own GATT client: LE-capable headsets, no Shizuku.
                    ?: device?.let { dev.earshot.bt.GattBatteryReader.read(appContext, it) }
            },
            appleAdverts = AppleAdvertScanner(appContext, applicationScope),
            demo = { if (demoMode.isEnabledBlocking()) demoMode.deviceBlocking().demoHeadset() else null },
        )
    }

    /** Shizuku shell for the few reads Android keeps from apps (dumpsys); optional everywhere. */
    private val privilegedShell: dev.earshot.call.PrivilegedShell by lazy { dev.earshot.call.ShizukuPrivilegedShell(appContext) }

    /**
     * The saved headphones and what Earshot has learned about how to reach them. One instance for
     * every stack, the router, the timeline and the screens (the store is process-wide anyway).
     */
    val devicePrefs: dev.earshot.bt.DevicePrefs by lazy { dev.earshot.bt.DevicePrefs(appContext) }

    /** What the app talks to: forwards to the Bose, Crusher or generic repository for the headset in use. */
    val headphones: HeadphoneRepository by lazy {
        RoutingHeadphoneRepository(
            drivers = listOf(
                VendorDriver(DeviceFamily.BOSE, boseHeadphones),
                VendorDriver(DeviceFamily.BRAGI, crusherHeadphones),
                VendorDriver(DeviceFamily.SOUNDCORE, soundcoreHeadphones),
                VendorDriver(DeviceFamily.SENNHEISER, sennheiserHeadphones),
                VendorDriver(DeviceFamily.SONY, sonyHeadphones),
                VendorDriver(DeviceFamily.JBL, jblHeadphones),
                // Not on the session base (it has no link): its release also resets what it showed.
                VendorDriver(DeviceFamily.GENERIC, genericHeadphones, genericHeadphones::releaseConnection),
            ),
            prefs = devicePrefs,
            finder = deviceFinder,
            scope = applicationScope,
            audioLink = AudioLinkMonitor(appContext, deviceFinder, applicationScope),
            demoFamily = {
                if (demoMode.isEnabledBlocking()) {
                    val device = demoMode.deviceBlocking()
                    when {
                        device.isCrusher -> DeviceFamily.BRAGI
                        device.isGeneric -> DeviceFamily.GENERIC
                        device.isSoundcore -> DeviceFamily.SOUNDCORE
                        device.isSennheiser -> DeviceFamily.SENNHEISER
                        device == DemoDevice.SONY_WH1000XM5 -> DeviceFamily.SONY
                        device.isJbl -> DeviceFamily.JBL
                        else -> DeviceFamily.BOSE
                    }
                } else null
            },
        )
    }
    val tuning: AudioTuningRepository by lazy { AudioTuningRepositoryImpl(appContext, applicationScope) }

    /** Pro EQ preset per headset, applied when that headset connects. Started by [EarshotApp]. */
    val headsetEq: HeadsetEqMemory by lazy { HeadsetEqMemory(appContext, headphones, tuning, applicationScope) }
    val codec: CodecRepository by lazy {
        CodecRepositoryImpl(appContext, applicationScope, preferredAddress = { headphones.state.value.connection.deviceAddress })
    }
    val micCheck: MicCheckRepository by lazy { MicCheckRepositoryImpl(appContext, applicationScope) }
    val automation: AutomationRepository by lazy { AutomationRepositoryImpl(appContext, applicationScope) }

    /** Bonded-device lookup shared by every stack and the connection code (the Shortcuts remapper lists headsets with it). */
    val deviceFinder: DeviceFinder by lazy { DeviceFinder(appContext, devicePrefs) }

    /** Diagnostics timeline (0.8, item 5): started by [EarshotApp], fed by the manifest receiver too. */
    val timeline: dev.earshot.bt.TimelineRecorder by lazy {
        dev.earshot.bt.TimelineRecorder(
            appContext,
            dev.earshot.bt.TimelineStore(java.io.File(appContext.filesDir, "bt_timeline.tsv")),
            applicationScope,
            devicePrefs,
        )
    }

    /** Find my earbuds (0.8, item 4): a tone into the shown headset, any brand. */
    val find: dev.earshot.find.FindPlayer by lazy { dev.earshot.find.FindPlayer(appContext, headphones, deviceFinder, applicationScope) }

    /** Shortcuts remapper: what a headset's press-and-hold does, and the code that does it. */
    val shortcuts: ShortcutEngine by lazy {
        ShortcutEngine(appContext, ShortcutPrefs(appContext), tuning, headphones, devicePrefs, applicationScope)
    }

    /**
     * In-call volume boost and call-codec readout. Created eagerly by [EarshotApp]: its call
     * listeners only exist once constructed. The headset-volume hook is wired by the integrator
     * once `HeadphoneRepository.setVolume` exists.
     */
    val callAudio: CallAudioRepository by lazy {
        CallAudioRepositoryImpl(
            appContext, applicationScope, headphones,
            headsetVolume = HeadsetVolumeControl { percent ->
                // `[5.5]` absolute volume, 0..volumeMax (31 on firmware 10.12). Only raise, never lower.
                val state = headphones.state.value
                val max = state.volumeMax ?: return@HeadsetVolumeControl false
                val target = (max * percent / 100.0).toInt().coerceIn(0, max)
                if ((state.volume ?: 0) >= target) return@HeadsetVolumeControl true
                headphones.setVolume(target)
                true
            },
        )
    }
}
