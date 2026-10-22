package dev.earshot.service.lab

import dev.earshot.bt.SoundcoreConnection
import dev.earshot.core.LabGroup
import dev.earshot.core.LabProfile
import dev.earshot.core.LabReading
import dev.earshot.core.RawFrame
import dev.earshot.protocol.soundcore.SoundcoreModel
import dev.earshot.protocol.soundcore.SoundcorePacket
import dev.earshot.protocol.soundcore.SoundcoreRead
import dev.earshot.protocol.soundcore.SoundcoreReads
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The Lab on Soundcore: only [SoundcoreReads.forModel], the documented reads the model's allow-list
 * lets out. Soundcore reuses command ids across models and an unknown write can unpair the earbuds,
 * power them off or play a loud tone, so nothing is ever guessed. Each read gets one try
 * ([TIMEOUT_MS]): an unknown command is simply not answered, and three tries would only repeat it.
 */
object SoundcoreLab {
    const val STACK = "Soundcore"
    const val TIMEOUT_MS = 1_500L

    fun profile(model: SoundcoreModel?): LabProfile {
        val reads = SoundcoreReads.forModel(model)
        val name = (model ?: SoundcoreModel.SLEEP_A30).displayName
        return LabProfile(
            stack = STACK,
            groupNoun = "Category",
            groups = reads.groupBy { it.category }.map { (category, list) ->
                LabGroup(category, SoundcoreReads.CATEGORY_NAMES[category] ?: "Category %02x".format(category), list.size, code = "%02x".format(category))
            },
            scanDescription = "The documented reads of the selected categories, one try each",
            deepReadCount = reads.size,
            deepScanDescription = "Every read Earshot documents for the $name (${reads.size}). Soundcore reuses command ids across models, so no other id is tried.",
            safetyNote = "Only read commands (code below 0x80) with an empty body, each checked against the $name's allow-list; " +
                "unpair, pairing mode, power off and the find tone are refused on the same path.",
        )
    }

    fun scan(conn: SoundcoreConnection, groupIds: List<Int>, deep: Boolean): Flow<LabReading> = flow {
        val model = conn.model
        for (read in SoundcoreReads.forModel(model).filter { deep || it.category in groupIds }) {
            val frame = if (!SoundcoreReads.isRead(model, read.command)) {
                LabFrames.refused(read.category, read.command)
            } else {
                LabFrames.attempt({ conn.isOpen }, read.category, read.command) {
                    val reply = conn.request(read.command, ByteArray(0), timeoutsMs = listOf(TIMEOUT_MS))
                    LabFrames.response(read.category, read.command, reply.body)
                }
            }
            emit(reading(read, frame))
        }
    }

    private fun reading(read: SoundcoreRead, frame: RawFrame) = LabReading(
        block = read.category,
        function = read.command,
        knownName = read.name,
        frames = listOf(frame),
        address = SoundcorePacket.commandName(read.command),
        groupName = SoundcoreReads.CATEGORY_NAMES[read.category],
    )
}
