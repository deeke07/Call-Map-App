package com.callmap.agenttracker.util.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign
import kotlin.math.tanh

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
    private val sampleRate: Int,
    private val targetRms: Float = 0.75f,
    private val minGain: Float = 1.2f,
    private val maxGain: Float = 900.0f,
    private val extraQuietBoost: Float = 1.8f,
    private val noiseGateRms: Float = 0.018f,
    private val noiseAttenuation: Float = 0.18f,
    private val noiseFloorAdaptRate: Float = 0.0025f
) : AudioFilter {
    private var smoothedRms = 0.0001f
    private var noiseFloor = noiseGateRms

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
            val activeNoiseFloor = noiseFloor.coerceAtLeast(noiseGateRms)

            // Track background noise when the signal is quiet so we can suppress hiss/room noise.
            // Use a wider adaption band (3.0×) so that gaps between words still update the floor.
            if (absSample < activeNoiseFloor * 3.0f) {
                noiseFloor = (noiseFloor * (1.0f - noiseFloorAdaptRate)) + (absSample * noiseFloorAdaptRate)
            }

            // Gate low-level noise before AGC.
            // Threshold at 2.0× floor so brief consonants aren't clipped but steady noise is.
            val gatedSample = if (absSample < activeNoiseFloor * 2.0f) {
                sample * noiseAttenuation
            } else {
                sample
            }

            // Envelope follower with asymmetric attack/release
            val gatedAbs = abs(gatedSample)
            val coeff = if (gatedAbs > smoothedRms) attackCoeff else releaseCoeff
            smoothedRms = coeff * smoothedRms + (1.0f - coeff) * gatedAbs

            // Calculate dynamic gain (Ultra-aggressive for Samsung earpiece leakage)
            var gain = targetRms / (smoothedRms + 1e-7f)

            // Lift very quiet segments (far-end earpiece leakage in receiver mode).
            // Only triggers well below the gating floor to avoid boosting room noise.
            if (smoothedRms < 0.008f) {
                gain *= extraQuietBoost
            } else if (smoothedRms < 0.018f) {
                gain *= (1.0f + (extraQuietBoost - 1.0f) * 0.4f)
            }

            gain = gain.coerceIn(minGain, maxGain)

            // Debug logging to check signal level
            if (++sampleCounter >= sampleRate * 2) { // Log approx every 2 seconds
                android.util.Log.d("VoiceEnhancer", "RMS: $smoothedRms, Applied Gain: $gain")
                sampleCounter = 0
            }
            
            var processed = gatedSample * gain

            // If we're very close to the noise floor, keep a little less energy than the full AGC boost.
            if (gatedAbs < activeNoiseFloor * 1.10f) {
                processed *= 0.92f
            }

            // Smooth tanh-style saturator — replaces the harsh piecewise limiter so
            // peaks roll off cleanly instead of producing audible distortion when
            // AGC pushes gain very high (typical for faint earpiece-leakage capture).
            // Headroom is set so signals below ~0.6 pass essentially untouched.
            val drive = 1.25f
            processed = tanh(processed * drive) / tanh(drive)

            samples[i] = (processed * 32768.0f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
