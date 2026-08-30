package com.fsaint.androidagent.capabilities.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidMicrophoneAdapter(context: Context) : MicrophoneAdapter {
    private val appContext = context.applicationContext
    private val lock = Any()
    @Volatile private var session: RecordingSession? = null

    override fun permission(): MicrophonePermission = if (
        appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    ) {
        MicrophonePermission.GRANTED
    } else {
        MicrophonePermission.DENIED
    }

    override fun supported(): Boolean =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)

    override fun recording(): Boolean = session?.running?.get() == true

    override suspend fun start(request: MicrophoneStartRequest): MicrophoneOperationOutcome = withContext(Dispatchers.IO) {
        if (!supported()) return@withContext MicrophoneOperationOutcome.Unsupported
        if (permission() != MicrophonePermission.GRANTED) return@withContext MicrophoneOperationOutcome.PermissionRequired
        synchronized(lock) {
            if (session != null) return@withContext MicrophoneOperationOutcome.DeviceBusy
            val recorder = try {
                createRecorder(request.sampleRateHz)
            } catch (_: SecurityException) {
                return@withContext MicrophoneOperationOutcome.PermissionRequired
            } catch (_: IllegalArgumentException) {
                return@withContext MicrophoneOperationOutcome.Unsupported
            } catch (_: UnsupportedOperationException) {
                return@withContext MicrophoneOperationOutcome.Unsupported
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return@withContext MicrophoneOperationOutcome.DeviceBusy
            }

            try {
                recorder.startRecording()
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.release()
                    return@withContext MicrophoneOperationOutcome.DeviceBusy
                }
            } catch (_: SecurityException) {
                recorder.release()
                return@withContext MicrophoneOperationOutcome.OsRestricted
            } catch (_: IllegalStateException) {
                recorder.release()
                return@withContext MicrophoneOperationOutcome.DeviceBusy
            }

            val active = RecordingSession(recorder, request.sampleRateHz, request.maxBytes)
            session = active
            active.thread = Thread({ captureLoop(active) }, "dark-lord-microphone").apply { start() }
            MicrophoneOperationOutcome.Success
        }
    }

    override suspend fun stop(): MicrophoneStopOutcome = withContext(Dispatchers.IO) {
        val active = synchronized(lock) { session } ?: return@withContext MicrophoneStopOutcome.NotRecording
        active.running.set(false)
        try {
            active.recorder.stop()
        } catch (_: IllegalStateException) {
            // A recorder that reached its byte bound may already have left recording state.
        }
        active.thread?.join(2_000)
        active.recorder.release()
        synchronized(lock) {
            if (session === active) session = null
        }
        val bytes = synchronized(active.bytes) { active.bytes.toByteArray() }
        if (bytes.isEmpty()) {
            MicrophoneStopOutcome.Failed
        } else {
            MicrophoneStopOutcome.Success(
                MicrophoneClip(bytes, active.sampleRateHz, channelCount = 1, encoding = "pcm_s16le"),
            )
        }
    }

    override fun level(): MicrophoneLevelOutcome {
        val active = session ?: return MicrophoneLevelOutcome.NotRecording
        if (!active.running.get()) return MicrophoneLevelOutcome.NotRecording
        return MicrophoneLevelOutcome.Success(MicrophoneLevel(active.rmsDb))
    }

    @SuppressLint("MissingPermission") // start() verifies the revocable permission and catches SecurityException.
    private fun createRecorder(sampleRateHz: Int): AudioRecord {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRateHz)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minimum = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) throw UnsupportedOperationException("Unsupported audio format")
        return AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minimum * 2)
            .build()
    }

    private fun captureLoop(active: RecordingSession) {
        val buffer = ShortArray(2_048)
        while (active.running.get()) {
            val read = active.recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            if (read <= 0) {
                active.running.set(false)
                break
            }
            var squared = 0.0
            val chunk = ByteArray(read * 2)
            for (index in 0 until read) {
                val value = buffer[index].toInt()
                squared += value.toDouble() * value
                chunk[index * 2] = (value and 0xFF).toByte()
                chunk[index * 2 + 1] = ((value ushr 8) and 0xFF).toByte()
            }
            active.rmsDb = rmsDb(squared, read)
            synchronized(active.bytes) {
                val remaining = active.maxBytes - active.bytes.size()
                if (remaining <= 0) {
                    active.running.set(false)
                } else {
                    active.bytes.write(chunk, 0, minOf(chunk.size, remaining))
                    if (chunk.size >= remaining) active.running.set(false)
                }
            }
        }
    }
}

private class RecordingSession(
    val recorder: AudioRecord,
    val sampleRateHz: Int,
    val maxBytes: Int,
) {
    val running = AtomicBoolean(true)
    val bytes = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    @Volatile var rmsDb: Float = -120f
    @Volatile var thread: Thread? = null
}

private fun rmsDb(squared: Double, count: Int): Float {
    if (count == 0 || squared <= 0) return -120f
    val normalized = sqrt(squared / count) / Short.MAX_VALUE
    return (20.0 * log10(normalized.coerceAtLeast(0.000001))).toFloat().coerceIn(-120f, 0f)
}

class AndroidAudioAdapter(context: Context) : AudioAdapter {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val lock = Any()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var preferredOutputId: Int? = null

    override fun supported(): Boolean = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) > 0

    override fun playing(): Boolean = track?.playState == AudioTrack.PLAYSTATE_PLAYING

    override fun volume(): AudioVolumeOutcome = try {
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maximum <= 0) AudioVolumeOutcome.Unsupported else AudioVolumeOutcome.Success(
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maximum,
        )
    } catch (_: RuntimeException) {
        AudioVolumeOutcome.Failed
    }

    override fun setVolume(level: Float): AudioOperationOutcome = try {
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maximum <= 0) {
            AudioOperationOutcome.Unsupported
        } else {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (level * maximum).roundToInt(), 0)
            AudioOperationOutcome.Success
        }
    } catch (_: SecurityException) {
        AudioOperationOutcome.OsRestricted
    } catch (_: RuntimeException) {
        AudioOperationOutcome.Failed
    }

    override fun outputDevices(): AudioDevicesOutcome = try {
        AudioDevicesOutcome.Success(
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map {
                AudioOutputDevice(it.id, it.type.toAudioType(), it.productName.toString())
            },
        )
    } catch (_: RuntimeException) {
        AudioDevicesOutcome.Failed
    }

    override suspend fun play(request: AudioPlayRequest): AudioOperationOutcome = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (playing()) return@withContext AudioOperationOutcome.DeviceBusy
            track?.release()
            val output = try {
                createTrack(request)
            } catch (_: IllegalArgumentException) {
                return@withContext AudioOperationOutcome.Unsupported
            } catch (_: UnsupportedOperationException) {
                return@withContext AudioOperationOutcome.Unsupported
            }
            if (output.state != AudioTrack.STATE_INITIALIZED) {
                output.release()
                return@withContext AudioOperationOutcome.DeviceBusy
            }
            preferredOutputId?.let { selectedId ->
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.id == selectedId }
                    ?.let(output::setPreferredDevice)
            }
            val written = output.write(request.pcmS16Le, 0, request.pcmS16Le.size, AudioTrack.WRITE_BLOCKING)
            if (written != request.pcmS16Le.size) {
                output.release()
                return@withContext AudioOperationOutcome.Failed
            }
            return@withContext try {
                output.play()
                track = output
                AudioOperationOutcome.Success
            } catch (_: IllegalStateException) {
                output.release()
                AudioOperationOutcome.DeviceBusy
            }
        }
    }

    override fun stop(): AudioOperationOutcome = synchronized(lock) {
        val active = track ?: return@synchronized AudioOperationOutcome.Success
        try {
            if (active.playState == AudioTrack.PLAYSTATE_PLAYING) active.stop()
            active.flush()
            active.release()
            track = null
            AudioOperationOutcome.Success
        } catch (_: IllegalStateException) {
            active.release()
            track = null
            AudioOperationOutcome.Failed
        }
    }

    override fun setOutputDevice(deviceId: Int): AudioOperationOutcome {
        val device = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == deviceId }
            ?: return AudioOperationOutcome.NotFound
        preferredOutputId = device.id
        return track?.let {
            if (it.setPreferredDevice(device)) AudioOperationOutcome.Success else AudioOperationOutcome.OsRestricted
        } ?: AudioOperationOutcome.Success
    }

    private fun createTrack(request: AudioPlayRequest): AudioTrack {
        val channelMask = if (request.channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(request.sampleRateHz)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(request.pcmS16Le.size)
            .build()
    }
}

private fun Int.toAudioType(): String = when (this) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "built_in_speaker"
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "built_in_earpiece"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth_a2dp"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth_sco"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headphones"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    -> "usb"
    else -> "type_$this"
}
