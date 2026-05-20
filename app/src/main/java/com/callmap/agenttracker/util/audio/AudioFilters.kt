package com.callmap.agenttracker.util.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
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
 * Improved Voice Enhancer with Dynamic AGC and Soft Limiter.
 * Designed to boost faint remote voices while keeping local voice clear.
 */
class VoiceEnhancer(
    private val sampleRate: Int
) : AudioFilter {
    private var smoothedRms = 0.0001f
    private val targetRms = 0.6f
    private val maxGain = 500.0f
    
    // Timing constants for gain adjustment
    private val attackTime = 0.005f  // Even faster attack to clamp loud sounds
    private val releaseTime = 0.5f   // Slower release to keep remote voice audible
    
    private val attackCoeff = exp(-1.0 / (sampleRate * attackTime)).toFloat()
    private val releaseCoeff = exp(-1.0 / (sampleRate * releaseTime)).toFloat()
    private var sampleCounter = 0

    override fun process(samples: ShortArray, numSamples: Int) {
        for (i in 0 until numSamples) {
            val sample = samples[i] / 32768.0f
            val absSample = abs(sample)
            
            // Envelope follower with asymmetric attack/release
            val coeff = if (absSample > smoothedRms) attackCoeff else releaseCoeff
            smoothedRms = coeff * smoothedRms + (1.0f - coeff) * absSample
            
            // Calculate dynamic gain (Ultra-aggressive for Samsung earpiece leakage)
            var gain = targetRms / (smoothedRms + 1e-7f)
            gain = gain.coerceIn(1.0f, maxGain)
            
            // Debug logging to check signal level
            if (++sampleCounter >= sampleRate * 2) { // Log approx every 2 seconds
                android.util.Log.d("VoiceEnhancer", "RMS: $smoothedRms, Applied Gain: $gain")
                sampleCounter = 0
            }
            
            var processed = sample * gain
            
            // Soft Limiter: Prevents harsh digital clipping by rounding off peaks
            if (abs(processed) > 0.8f) {
                val excess = abs(processed) - 0.8f
                processed = sign(processed) * (0.8f + excess / (1.0f + excess * 4.0f))
            }

            samples[i] = (processed * 32768.0f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
