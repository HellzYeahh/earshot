package dev.earshot

import android.content.Context
import dev.earshot.audio.AudioTuningRepositoryImpl
import dev.earshot.bt.ByteTransport
import dev.earshot.bt.DeviceFamily
import dev.earshot.bt.DeviceFinder
import dev.earshot.call.CallAudioRepositoryImpl
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
import dev.earshot.service.HeadphoneProtocol
import dev.earshot.service.QcUltra2Protocol
import dev.earshot.service.RoutingHeadphoneRepository
import dev.earshot.service.ServiceHeadphoneRepository
import dev.earshot.shortcut.ShortcutEngine
import dev.earshot.shortcut.ShortcutPrefs
import dev.earshot.virtual.DemoDevice
import dev.earshot.virtual.DemoMode
import dev.earshot.virtual.VirtualCrusher
import dev.earshot.virtual.VirtualCrusherTransport
import dev.earshot.virtual.VirtualProfile
import dev.earshot.virtual.VirtualQcUltra2
import dev.earshot.virtual.VirtualTransport
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
            else -> null
        }
    }

    /** The QC Ultra 2 command layer (BMAP addresses, payloads and state mapping). */
    private val headphoneProtocol: HeadphoneProtocol by lazy { QcUltra2Protocol() }

    private val boseHeadphones: ServiceHeadphoneRepository by lazy {
        ServiceHeadphoneRepository(
            appContext, headphoneProtocol, applicationScope, boseDemoTransport,
            demoName = { "Virtual ${runCatching { demoMode.deviceBlocking().displayName }.getOrDefault("QC Ultra")} (demo)" },
        )
    }

    /** The Skullcandy Crusher 1080 ANC (Bragi BOS-RPC): a separate stack behind the same contract. */
    private val crusherHeadphones: CrusherHeadphoneRepository by lazy {
        CrusherHeadphoneRepository(
            appContext, CrusherProtocol(), applicationScope, crusherDemoTransport,
            autoConnect = { runCatching { automation.preferences.value.autoConnectOnBluetooth }.getOrDefault(true) },
        )
    }

    /** The saved headphones and what Earshot has learned about how to reach them. */
    val devicePrefs: dev.earshot.bt.DevicePrefs get() = boseHeadphones.devicePrefs

    /** What the app talks to: forwards to the Bose or the Crusher repository for the paired headphones. */
    val headphones: HeadphoneRepository by lazy {
        RoutingHeadphoneRepository(
            bose = boseHeadphones,
            crusher = crusherHeadphones,
            prefs = boseHeadphones.devicePrefs,
            finder = boseHeadphones.deviceFinder,
            scope = applicationScope,
            demoFamily = {
                if (demoMode.isEnabledBlocking()) {
                    if (demoMode.deviceBlocking().isCrusher) DeviceFamily.BRAGI else DeviceFamily.BOSE
                } else null
            },
        )
    }
    val tuning: AudioTuningRepository by lazy { AudioTuningRepositoryImpl(appContext, applicationScope) }
    val codec: CodecRepository by lazy { CodecRepositoryImpl(appContext, applicationScope) }
    val micCheck: MicCheckRepository by lazy { MicCheckRepositoryImpl(appContext, applicationScope) }
    val automation: AutomationRepository by lazy { AutomationRepositoryImpl(appContext, applicationScope) }

    /** Bonded-device lookup shared with the connection code (the Shortcuts remapper lists headsets with it). */
    val deviceFinder: DeviceFinder get() = boseHeadphones.deviceFinder

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
