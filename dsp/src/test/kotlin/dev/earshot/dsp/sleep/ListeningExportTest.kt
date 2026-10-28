package dev.earshot.dsp.sleep

import java.io.File
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Not a check but a convenience: writes every sound and alarm as a 16-bit stereo WAV to
 * `dsp/build/sleep-sounds/`, so a human can listen to the synthesis on the PC. Ten seconds each
 * (thirty for the ocean, whose waves are up to eleven seconds apart), plus one three-layer mix.
 */
class ListeningExportTest {
    @Test
    fun `write every sound and alarm to WAV files`() {
        val folder = File("build/sleep-sounds")
        folder.mkdirs()
        for (sound in SleepSound.entries) {
            val seconds = if (sound == SleepSound.OCEAN) 30.0 else EXPORT_SECONDS
            export(folder, sound.storedId, Signals.render(SleepSounds.create(sound, SEED), seconds))
        }
        for (tone in AlarmTone.entries) {
            export(folder, "alarm_${tone.storedId}", Signals.render(AlarmTones.create(tone, SEED), EXPORT_SECONDS))
        }
        val mixer = SoundMixer(SEED)
        mixer.setLayers(listOf(SoundLayer(SleepSound.RAIN, 0.8f), SoundLayer(SleepSound.FIRE, 0.7f), SoundLayer(SleepSound.BROWN_NOISE, 0.4f)))
        export(folder, "mix_rain_fire_brown", Signals.render(mixer, EXPORT_SECONDS))
        println("Sleep sounds written to ${folder.absolutePath}")
        assertTrue(File(folder, "rain.wav").length() > 0)
    }

    private fun export(folder: File, name: String, audio: FloatArray) {
        Signals.writeWav(File(folder, "$name.wav"), audio)
        val rmsDb = 20 * log10(Signals.rms(audio))
        val peakDb = 20 * log10(Signals.peak(audio))
        println("%-22s RMS %6.1f dBFS  peak %6.1f dBFS".format(name, rmsDb, peakDb))
    }

    private companion object {
        const val SEED = 20261026L
        const val EXPORT_SECONDS = 10.0
    }
}
