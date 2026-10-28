package dev.earshot.sleep

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import dev.earshot.core.CustomSound
import dev.earshot.dsp.sleep.LoopMaker
import dev.earshot.dsp.sleep.LoopPcm
import dev.earshot.dsp.sleep.RecordingLibrary
import dev.earshot.dsp.sleep.SleepSounds
import java.io.File
import java.nio.ByteOrder

/**
 * Every recording the Sleep tab plays, decoded into memory a few at a time:
 *  - the bundled ones (`assets/sleep/<id>.ogg`: seamless 48 kHz stereo loops made by
 *    `tools/sleep-sounds/make_loops.py`, credits in `tools/sleep-sounds/sources.json` and About);
 *  - the user's own sounds ([CustomSound], private copies in `files/sleep_custom/`), which
 *    [LoopMaker] turns into seamless 48 kHz loops when they are decoded.
 *
 * Decoded with Android's own decoder. [loop] never blocks (the mixer calls it on the audio
 * thread): [preload] decodes first, off the audio thread; a bundled sound that isn't decoded yet
 * plays its synthesised version. The bundled files work before the first unlock too (the APK's
 * assets aren't credential-encrypted); own sounds don't, and nothing needs them then.
 */
class BundledRecordings private constructor(private val context: Context) : RecordingLibrary {
    private val bundled: Set<String> by lazy {
        runCatching { context.assets.list(DIR)?.filter { it.endsWith(EXT) }?.map { it.removeSuffix(EXT) }?.toSet() }
            .getOrNull().orEmpty()
    }

    private val customDir: File get() = File(context.filesDir, CUSTOM_DIR)

    override val ids: Set<String>
        get() = bundled + (customDir.list()?.map { CustomSound.ID_PREFIX + it }?.toSet().orEmpty())

    /** Most recently used last; up to about 17 MB per 90 s loop, so only a few stay. */
    private val cache = object : LinkedHashMap<String, LoopPcm>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LoopPcm>?) = size > MAX_CACHED
    }

    override fun loop(id: String): LoopPcm? = synchronized(cache) { cache[id] }

    /** Decodes every id in [wanted] that has a file and isn't in memory yet. Blocking. */
    fun preload(wanted: Collection<String>) {
        for (id in wanted) {
            if (synchronized(cache) { cache.containsKey(id) }) continue
            val pcm = when {
                CustomSound.isCustom(id) -> customFile(id)?.takeIf { it.isFile }?.let { decodeCustom(it, id) }
                id in bundled -> decodeBundled(id)
                else -> null
            } ?: continue
            synchronized(cache) { cache[id] = pcm }
        }
    }

    /**
     * Copies the document at [uri] into Earshot's own files as [id] and makes sure it plays as a
     * loop (decoded now, so the first preview starts at once). Returns the loop's length in
     * seconds; null (and nothing kept) when it can't be read, decoded or is too short.
     */
    fun importCustom(uri: Uri, id: String): Int? {
        val target = customFile(id) ?: return null
        customDir.mkdirs()
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            } != null
        }.getOrDefault(false)
        if (!copied || target.length() == 0L) {
            target.delete()
            return null
        }
        val pcm = decodeCustom(target, id)
        if (pcm == null) {
            target.delete()
            return null
        }
        synchronized(cache) { cache[id] = pcm }
        return pcm.frames / pcm.sampleRate
    }

    fun deleteCustom(id: String) {
        synchronized(cache) { cache.remove(id) }
        customFile(id)?.delete()
    }

    /** files/sleep_custom/<uuid>: never a path from outside (the id is checked). */
    private fun customFile(id: String): File? {
        val name = id.removePrefix(CustomSound.ID_PREFIX)
        if (!CustomSound.isCustom(id) || name.isEmpty() || !name.all { it.isLetterOrDigit() || it == '-' }) return null
        return File(customDir, name)
    }

    private fun decodeBundled(id: String): LoopPcm? {
        val decoded = context.assets.openFd("$DIR/$id$EXT").use { fd ->
            decode(id, maxFrames = null) { it.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
        } ?: return null
        if (decoded.rate != SleepSounds.SAMPLE_RATE) {
            // The bundled files are all 48 kHz; anything else would play at the wrong pitch.
            Log.w(TAG, "recording $id is ${decoded.rate} Hz, not ${SleepSounds.SAMPLE_RATE}; using the synthesis")
            return null
        }
        return decoded.stereo.takeIf { it.size >= 4 }?.let { LoopPcm(it, decoded.rate) }
    }

    private fun decodeCustom(file: File, id: String): LoopPcm? {
        val decoded = decode(id, maxFrames = { rate -> LoopMaker.framesNeeded(rate) }) { it.setDataSource(file.absolutePath) } ?: return null
        return LoopMaker.make(decoded.stereo, channels = 2, rate = decoded.rate)
    }

    private class Decoded(val stereo: ShortArray, val rate: Int)

    /** Decodes the first audio track to interleaved stereo shorts, stopping after [maxFrames] frames. */
    private fun decode(id: String, maxFrames: ((rate: Int) -> Int)?, source: (MediaExtractor) -> Unit): Decoded? {
        val started = System.nanoTime()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            source(extractor)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val out = ShortBuilder(rate * 2 * 60)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                if (maxFrames != null && out.frames >= maxFrames(rate)) break
                if (!inputDone) {
                    val inIndex = c.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val size = extractor.readSampleData(c.getInputBuffer(inIndex)!!, 0)
                        if (size < 0) {
                            c.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = c.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex >= 0 -> {
                        val buffer = c.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val shorts = buffer.asShortBuffer()
                        val chunk = ShortArray(shorts.remaining())
                        shorts.get(chunk)
                        if (channels == 1) out.addMonoAsStereo(chunk) else out.addStereo(chunk, channels)
                        c.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        channels = c.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        rate = c.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                }
            }
            val stereo = out.build()
            Log.i(TAG, "decoded $id: ${stereo.size / 2 / rate} s at $rate Hz in ${(System.nanoTime() - started) / 1_000_000} ms")
            return Decoded(stereo, rate)
        } catch (e: Exception) {
            Log.w(TAG, "couldn't decode $id: ${e.javaClass.simpleName}: ${e.message}")
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** A growable interleaved-stereo short buffer. */
    private class ShortBuilder(initial: Int) {
        private var data = ShortArray(initial)
        private var size = 0
        val frames: Int get() = size / 2

        private fun ensure(extra: Int) {
            if (size + extra > data.size) data = data.copyOf(maxOf(data.size * 2, size + extra))
        }

        fun addStereo(chunk: ShortArray, channels: Int) {
            if (channels == 2) {
                ensure(chunk.size)
                System.arraycopy(chunk, 0, data, size, chunk.size)
                size += chunk.size
                return
            }
            // More than two channels: keep the first two.
            val frames = chunk.size / channels
            ensure(frames * 2)
            for (f in 0 until frames) {
                data[size++] = chunk[f * channels]
                data[size++] = chunk[f * channels + 1]
            }
        }

        fun addMonoAsStereo(chunk: ShortArray) {
            ensure(chunk.size * 2)
            for (s in chunk) {
                data[size++] = s
                data[size++] = s
            }
        }

        fun build(): ShortArray = data.copyOf(size - size % 2)
    }

    companion object {
        private const val TAG = "Earshot/Sleep"
        private const val DIR = "sleep"
        private const val EXT = ".ogg"
        private const val CUSTOM_DIR = "sleep_custom"
        private const val MAX_CACHED = 5
        private const val TIMEOUT_US = 10_000L

        // Holds only the application context (see get), so no activity can leak through it.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile private var instance: BundledRecordings? = null

        /** The process-wide library, installed into the sound engine on first use. */
        fun get(context: Context): BundledRecordings = instance ?: synchronized(this) {
            instance ?: BundledRecordings(context.applicationContext).also {
                instance = it
                SleepSounds.recordings = it
            }
        }
    }
}
