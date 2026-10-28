package dev.earshot.ui.sleep

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.HeatPump
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.Water
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Waves
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.earshot.R
import dev.earshot.core.CustomSound
import dev.earshot.core.SleepAlarm
import dev.earshot.dsp.sleep.AlarmTone
import dev.earshot.dsp.sleep.SleepSound
import dev.earshot.dsp.sleep.SleepSoundGroup

/*
 * Names and icons for the sound engine's catalogue. `:dsp` has no Android resources, so the
 * words live here. The `when`s are exhaustive on purpose: a sound added to the engine fails the
 * build until it has a name, instead of reaching the screen as its stored id.
 */

@StringRes
fun SleepSound.labelRes(): Int = when (this) {
    SleepSound.WHITE_NOISE -> R.string.sleep_sound_white
    SleepSound.PINK_NOISE -> R.string.sleep_sound_pink
    SleepSound.BROWN_NOISE -> R.string.sleep_sound_brown
    SleepSound.RAIN -> R.string.sleep_sound_rain
    SleepSound.HEAVY_RAIN -> R.string.sleep_sound_heavy_rain
    SleepSound.OCEAN -> R.string.sleep_sound_ocean
    SleepSound.STREAM -> R.string.sleep_sound_stream
    SleepSound.WIND -> R.string.sleep_sound_wind
    SleepSound.FIRE -> R.string.sleep_sound_fire
    SleepSound.FAN -> R.string.sleep_sound_fan
}

fun SleepSound.icon(): ImageVector = when (this) {
    SleepSound.WHITE_NOISE, SleepSound.PINK_NOISE, SleepSound.BROWN_NOISE -> Icons.Filled.GraphicEq
    SleepSound.RAIN -> Icons.Filled.WaterDrop
    SleepSound.HEAVY_RAIN -> Icons.Filled.Umbrella
    SleepSound.OCEAN -> Icons.Filled.Waves
    SleepSound.STREAM -> Icons.Filled.Water
    SleepSound.WIND -> Icons.Filled.Air
    SleepSound.FIRE -> Icons.Filled.LocalFireDepartment
    // A fan in a box: Material has no plain fan, and "Toys" is a toy car.
    SleepSound.FAN -> Icons.Filled.HeatPump
}

@StringRes
fun SleepSoundGroup.labelRes(): Int = when (this) {
    SleepSoundGroup.NOISE -> R.string.sleep_group_noise
    SleepSoundGroup.WATER -> R.string.sleep_group_water
    SleepSoundGroup.NATURE -> R.string.sleep_group_nature
    SleepSoundGroup.HOME -> R.string.sleep_group_home
}

@StringRes
fun AlarmTone.labelRes(): Int = when (this) {
    AlarmTone.CHIME -> R.string.sleep_tone_chime
    AlarmTone.BIRDS -> R.string.sleep_tone_birds
    AlarmTone.CLASSIC -> R.string.sleep_tone_classic
}

/**
 * A layer's name: a built-in sound's, or the user's own sound's ([LocalCustomSounds]). An own
 * sound that was deleted reads "Deleted sound"; an id this build doesn't know shows as itself.
 */
@Composable
fun soundName(soundId: String): String =
    SleepSound.fromStoredId(soundId)?.let { stringResource(it.labelRes()) }
        ?: LocalCustomSounds.current.firstOrNull { it.id == soundId }?.name
        ?: if (CustomSound.isCustom(soundId)) stringResource(R.string.sleep_custom_missing) else soundId

/** An alarm's sound: a tone, the phone's own alarm sound, or a sleep sound. */
@Composable
fun alarmToneName(toneId: String): String = when {
    toneId == SleepAlarm.TONE_SYSTEM -> stringResource(R.string.sleep_tone_system)
    toneId.startsWith(SleepAlarm.TONE_SOUND_PREFIX) -> soundName(toneId.removePrefix(SleepAlarm.TONE_SOUND_PREFIX))
    else -> AlarmTone.fromStoredId(toneId)?.let { stringResource(it.labelRes()) } ?: toneId
}
