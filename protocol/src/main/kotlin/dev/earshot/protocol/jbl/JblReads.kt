package dev.earshot.protocol.jbl

import dev.earshot.protocol.jbl.JblCommands as C

/** One documented H-AA read for the Lab, and how to tell its answer from other frames (H-AA has no sequence numbers). */
class JblRead(val frame: JblFrame, val name: String, val group: Int, val answers: (JblFrame) -> Boolean)

/**
 * The Lab's read list for JBL H-AA: the reads jbl-control probes on a Wave Buds 2 and the app's
 * device-info and battery reads, nothing else. The command ids around them include factory reset
 * (`95`), power off (`97`) and firmware transfer (`F0`), so the Lab never walks ids; each read is
 * checked against [JblWriteGuard.isRead] before it is sent.
 */
object JblReads {
    const val GROUP_DEVICE = 1
    const val GROUP_SOUND = 2
    const val GROUP_SETTINGS = 3

    val ALL: List<JblRead> = listOf(
        JblRead(C.deviceInfoGet(), "Device info", GROUP_DEVICE) { it.command == C.DEVICE_INFO },
        JblRead(C.infoGet(), "Device info (TLV)", GROUP_DEVICE) { it.command == C.INFO && it.sub == 0x02 },
        JblRead(C.batteryGet(), "Battery (older earbuds)", GROUP_DEVICE) { it.command == C.BATTERY },
        JblRead(C.serialGet(), "Serial number", GROUP_DEVICE) { it.command == C.SERIAL },
        JblRead(C.modeGet(), "Listening mode", GROUP_SOUND) { it.command == C.LISTENING && it.sub == C.MODE_STATUS },
        JblRead(C.levelGet(), "Noise-cancelling level", GROUP_SOUND) { it.command == C.LISTENING && it.sub == C.LEVEL_STATUS },
        JblRead(C.eqGet(), "EQ", GROUP_SOUND) { it.command == C.EQ && it.size >= C.EQ_HEADER_SIZE },
        JblRead(C.savGet(), "Smart Audio & Video", GROUP_SOUND) { it.command == C.SAV },
        JblRead(C.voicePromptsGet(), "Voice prompts", GROUP_SETTINGS) { it.command == C.VOICE_PROMPTS && it.sub == 0x05 },
        JblRead(C.voiceAwareGet(), "VoiceAware", GROUP_SETTINGS) { it.command == C.VOICE_AWARE },
        JblRead(C.findGet(), "Find my buds (state)", GROUP_SETTINGS) { it.command == C.FIND },
    )

    fun groupName(group: Int): String = when (group) {
        GROUP_DEVICE -> "Device"
        GROUP_SOUND -> "Sound"
        GROUP_SETTINGS -> "Settings"
        else -> "Group $group"
    }
}
