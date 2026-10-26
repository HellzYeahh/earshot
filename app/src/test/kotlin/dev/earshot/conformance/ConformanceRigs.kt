package dev.earshot.conformance

import dev.earshot.protocol.soundcore.SoundcoreModel
import dev.earshot.virtual.DemoDevice
import dev.earshot.virtual.VirtualCrusher
import dev.earshot.virtual.virtualProfile
import kotlinx.coroutines.test.TestScope

/**
 * The rig for each demo device: the test-side driver registry. Exhaustive on purpose: a new
 * [DemoDevice] does not compile until it has a rig here (or a reason for null), so every driver's
 * virtual device runs the same conformance checks. Null only for the generic demo (AirPods): no
 * wire protocol, nothing sent.
 */
fun rigFor(d: DemoDevice, test: TestScope): ConformanceRig? = when (d) {
    DemoDevice.QC_ULTRA, DemoDevice.QC_ULTRA_EARBUDS_2, DemoDevice.QC_EARBUDS_2, DemoDevice.QC_EARBUDS,
    DemoDevice.QC45, DemoDevice.NC700, DemoDevice.ULTRA_OPEN, DemoDevice.QC35_II,
    -> BoseRig(test, d, checkNotNull(d.virtualProfile()) { "$d has no virtual profile" }, seed = d.ordinal + 1)

    DemoDevice.CRUSHER -> BragiRig(test, d, VirtualCrusher(), "Skullcandy Crusher 1080")
    DemoDevice.CRUSHER_SECURE -> BragiRig(test, d, VirtualCrusher(authenticatedAccess = true), "Skullcandy Crusher 1080")
    DemoDevice.METHOD_360 -> BragiRig(test, d, VirtualCrusher.method360(), "Skullcandy Method 360 ANC")
    DemoDevice.METHOD_540 -> BragiRig(test, d, VirtualCrusher.method540(), "Skullcandy Method 540 ANC")

    DemoDevice.SOUNDCORE_SLEEP_A30 -> SleepA30Rig(test, d)
    DemoDevice.SOUNDCORE_LIBERTY_4_NC -> SoundcoreModelRig(test, d, SoundcoreModel.LIBERTY_4_NC)
    DemoDevice.SOUNDCORE_SPACE_ONE_PRO -> SoundcoreModelRig(test, d, SoundcoreModel.SPACE_ONE_PRO)

    DemoDevice.SENNHEISER_MOMENTUM_4 -> SennheiserRig(test, d)
    DemoDevice.SONY_WH1000XM5 -> SonyRig(test, d)
    DemoDevice.JBL_WAVE_BUDS_2 -> JblRig(test, d)

    DemoDevice.AIRPODS_PRO -> null
}
