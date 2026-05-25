package com.callmap.agenttracker.util.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

interface AudioFilter {
    fun process(samples: ShortArray, numSamples: Int)
}

class HighPassFilter(cutoffFrequency: Float, sampleRate: Int) : AudioFilter {
    private var prevX = 0f
    private var prevY = 0f
    private val alpha: Float

    init {
        val rc = 1.0f / (2.0f * PI.toFloat() * cutoffFrequency)
        val dt = 1.0f / sampleRate
        alpha = rc / (rc + dt)
    }

    override fun process(samples: ShortArray, numSamples: Int) {
        for (i in 0 until numSamples) {
            val x = samples[i].toFloat()
            val y = alpha * (prevY + x - prevX)
            prevX = x
            prevY = y
            samples[i] = y.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}

class LowPassFilter(cutoffFrequency: Float, sampleRate: Int) : AudioFilter {
    private var prevY = 0f
    private val alpha: Float

    init {
        val dt = 1.0f / sampleRate
        val rc = 1.0f / (2.0f * PI.toFloat() * cutoffFrequency)
        alpha = dt / (rc + dt)
    }

    override fun process(samples: ShortArray, numSamples: Int) {
        for (i in 0 until numSamples) {
            val x = samples[i].toFloat()
            val y = prevY + alpha * (x - prevY)
            prevY = y
            samples[i] = y.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}

/**
 * Combined Compressor and AGC.
 * It squeezes loud sounds (like the local speaker) so that quiet sounds (like the remote caller)
 * can be amplified without causing distortion (clipping).
 */
class VoiceEnhancer(
    private val targetGain: Float = 25.0f,
    private val compressionThreshold: Float = 0.2f, // 20% of max volume
    private val compressionRatio: Float = 8.0f     // Squeeze loud parts by 8x
) : AudioFilter {

    override fun process(samples: ShortArray, numSamples: Int) {
        for (i in 0 until numSamples) {
            // Normalize to -1.0 to 1.0
            var sample = samples[i] / 32768.0f

            // 1. Compression: Squeeze loud signals
            val absSample = abs(sample)
            if (absSample > compressionThreshold) {
                val excess = absSample - compressionThreshold
                sample = sign(sample) * (compressionThreshold + excess / compressionRatio)
            }

            // 2. Aggressive Gain: Boost everything
            sample *= targetGain

            // 3. Hard Clipping Protection
            samples[i] = (sample * 32768.0f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}