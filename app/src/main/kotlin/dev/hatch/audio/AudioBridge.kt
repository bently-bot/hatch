package dev.hatch.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import dev.hatch.guest.GuestScripts
import dev.hatch.guest.LinuxRuntime
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Guest sound → Android speakers. The guest's shared PulseAudio (hatch-audio, its own proot session) streams its
 * null-sink monitor as raw s16le/48 kHz/stereo on 127.0.0.1:47140; we play it with a low-latency AudioTrack and
 * pause the track during silence so idle apps don't hold audio focus or drain battery.
 */
object AudioBridge {
    private const val TAG = "HatchAudio"
    const val PCM_PORT = 47140
    private const val RATE = 48_000
    @Volatile private var daemon: LinuxRuntime.Session? = null
    @Volatile private var reader: Thread? = null

    fun available(context: Context) = File(LinuxRuntime.rootfsDir(context), "usr/bin/pulseaudio").exists()

    /** Idempotent: start the guest sound server and the reader if PulseAudio is installed in the guest. */
    @Synchronized
    fun ensure(context: Context, waylandHostDir: String) {
        if (!available(context)) return
        if (daemon == null) {
            daemon = LinuxRuntime.spawn(
                context, listOf("/usr/local/bin/hatch-audio"), workingDir = GuestScripts.HOME,
                waylandHostDir = waylandHostDir, asUser = true,
            )
            Log.i(TAG, "sound server pid=${daemon?.pid}")
            daemon?.let { s ->
                Thread({
                    val buf = ByteArray(4096)
                    while (true) { val n = runCatching { dev.hatch.wayland.Native.readSome(s.outFd, buf) }.getOrDefault(-1); if (n <= 0) break }
                    dev.hatch.guest.LinuxRuntime.reap(s.pid)
                    synchronized(this) { if (daemon === s) daemon = null }
                    Log.i(TAG, "sound server exited")
                }, "hatch-audio-daemon").apply { isDaemon = true; start() }
            }
        }
        if (reader?.isAlive != true) reader = Thread(::pump, "hatch-audio-pump").apply { isDaemon = true; start() }
    }

    private fun pump() {
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(minBuf * 2, 16_384))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        val buf = ByteArray(7_680) // 40 ms
        var silentBytes = 0L
        try {
            while (daemon != null || reader === Thread.currentThread()) {
                val sock = runCatching { Socket().apply { connect(InetSocketAddress("127.0.0.1", PCM_PORT), 1000); tcpNoDelay = true } }.getOrNull()
                if (sock == null) { if (daemon == null) break; Thread.sleep(1000); continue }
                Log.i(TAG, "connected to guest sound server")
                sock.use { s ->
                    val inp = s.getInputStream()
                    while (true) {
                        val n = inp.read(buf); if (n <= 0) break
                        val silent = isSilent(buf, n)
                        silentBytes = if (silent) silentBytes + n else 0
                        if (!silent && track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                        if (silentBytes > RATE * 4L && track.playState == AudioTrack.PLAYSTATE_PLAYING) { track.pause(); track.flush() }
                        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.write(buf, 0, n and 3.inv())
                    }
                }
                track.pause(); track.flush()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "pump: ${e.message}")
        } finally {
            runCatching { track.release() }
        }
    }

    internal fun isSilent(b: ByteArray, n: Int): Boolean {
        var i = 0
        while (i + 1 < n) { val v = (b[i].toInt() and 0xff) or (b[i + 1].toInt() shl 8); if (v > 8 || v < -8) return false; i += 2 }
        return true
    }

    fun stop() {
        daemon?.kill(); daemon = null
    }
}
