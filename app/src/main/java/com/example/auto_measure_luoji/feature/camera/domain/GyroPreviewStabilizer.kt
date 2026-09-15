package com.example.auto_measure_luoji.feature.camera.domain

import java.util.ArrayDeque
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

private const val RE_AIM_EDGE_CONFIRM_FRAMES = 2

/**
 * App 层低延迟预览防抖实验算法。
 *
 * 思路参考 Gyroflow 的 gyro-driven stabilization：用陀螺仪角速度按帧间隔积分出
 * 画面角位移，再把它换算成 TextureView 显示层的反向平移。这里不接管 Camera HAL，
 * 不改变系统 OIS/EIS，只输出用于预览矩阵的轻量 transform。
 */
class GyroPreviewStabilizer(
    private var config: Config = Config(),
) {
    data class Config(
        val strength: Float = 0.75f,
        val pathFollowPerSecond: Float = 5.0f,
        val outputLowPassAlpha: Float = 0.85f,
        val outputRateLimitPxPerSecond: Float = 720f,
        val outputAccelerationLimitPxPerSecond2: Float = 0f,
        val cropScale: Float = 1.18f,
        val maxShiftRatio: Float = 0.08f,
        val deadbandPx: Float = 0.0f,
        val horizontalGain: Float = 1.30f,
        val verticalGain: Float = 1.25f,
        val aimStabilizationEnabled: Boolean = true,
        val aimRpsThreshold: Float = 0.04f,
        val aimStrengthMultiplier: Float = 2.0f,
        val aimPathFollowPerSecond: Float = 0.8f,
        val aimOutputLowPassAlpha: Float = 0.70f,
        val aimAdaptiveLowPassEnabled: Boolean = false,
        val aimAdaptiveLowPassMinAlpha: Float = 0.62f,
        val aimDeadbandPx: Float = 0.0f,
        val aimOutputRateLimitPxPerSecond: Float = 720f,
        val aimOutputAccelerationLimitPxPerSecond2: Float = 0f,
        val residualReleaseEnabled: Boolean = false,
        val residualReleaseEnterRatio: Float = 0.90f,
        val residualReleaseExitRatio: Float = 0.25f,
        val residualReleaseStillMs: Float = 300f,
        val residualReleaseRpsThreshold: Float = 0.015f,
        val residualReleasePathFollowPerSecond: Float = 4.5f,
        val residualReleaseOutputRateLimitPxPerSecond: Float = 240f,
        val residualReleaseOutputAccelerationLimitPxPerSecond2: Float = 0f,
        val residualReleaseBlendInPerSecond: Float = 2.0f,
        val residualReleaseBlendOutPerSecond: Float = 2.0f,
        val residualAntiWindupEnabled: Boolean = true,
        val softBoundaryEnabled: Boolean = true,
        val softBoundaryStartRatio: Float = 0.80f,
        val softBoundaryStrength: Float = 1.00f,
        val reAimEnabled: Boolean = true,
        val reAimRpsThreshold: Float = 0.012f,
        val reAimResidualEnterRatio: Float = 0.85f,
        val reAimResidualExitRatio: Float = 0.25f,
        val reAimStillMs: Float = 250f,
        val reAimPathFollowPerSecond: Float = 8.0f,
        val reAimBlendInPerSecond: Float = 3.0f,
        val reAimBlendOutPerSecond: Float = 3.0f,
        val zoomCompensationEnabled: Boolean = true,
        val zoomCompensationStrength: Float = 1.0f,
        val maxCompensationZoom: Float = 20.0f,
        val frameSyncEnabled: Boolean = true,
        val frameTimestampOffsetMs: Float = 5f,
        val visualStillGateEnabled: Boolean = false,
        val rollCompensationEnabled: Boolean = false,
        val rollStrength: Float = 0.35f,
        val maxRollDegrees: Float = 1.5f,
        val invertRoll: Boolean = false,
        val maxDeltaTimeSec: Float = 0.025f,
        val swapPitchYawAxes: Boolean = false,
        val invertHorizontal: Boolean = false,
        val invertVertical: Boolean = true,
    )

    data class VisualMotionFeedback(
        val timestampNs: Long,
        val valid: Boolean,
        val hitSearchBoundary: Boolean,
        val rawDx: Float,
        val rawDy: Float,
        val score: Float,
    )

    data class Transform(
        val scale: Float = 1f,
        val translationX: Float = 0f,
        val translationY: Float = 0f,
        val rotationDegrees: Float = 0f,
        val rps: Float = 0f,
        val aimBlend: Float = 0f,
        val baseAimBlend: Float = 0f,
        val reAimActive: Boolean = false,
        val reAimBlend: Float = 0f,
        val reAimStillMs: Float = 0f,
        val reAimTrigger: String = "",
        val gyroXRadPerSec: Float = 0f,
        val gyroYRadPerSec: Float = 0f,
        val gyroZRadPerSec: Float = 0f,
        val sourcePitchRate: Float = 0f,
        val sourceYawRate: Float = 0f,
        val sourceRollRate: Float = 0f,
        val opticalZoomBase: Float = 1f,
        val digitalZoomRatio: Float = 1f,
        val displayZoomRatio: Float = 1f,
        val renderScale: Float = 1f,
        val effectiveZoom: Float = 1f,
        val outputLowPassAlpha: Float = 0f,
        val aimAdaptiveLowPassBlend: Float = 0f,
        val aimAdaptiveLowPassJitterPx: Float = 0f,
        val aimMicroJitterClampActive: Boolean = false,
        val aimMicroJitterStepPx: Float = 0f,
        val aimMicroJitterStepChangePx: Float = 0f,
        val aimMicroJitterMaxStepChangePx: Float = 0f,
        val aimMicroJitterReverseAlignment: Float = 0f,
        val aimMicroJitterStrongStepReversal: Boolean = false,
        val outputRateLimitPxPerSecond: Float = 0f,
        val outputAccelerationLimitPxPerSecond2: Float = 0f,
        val outputVelocityX: Float = 0f,
        val outputVelocityY: Float = 0f,
        val residualReleaseActive: Boolean = false,
        val residualReleaseBlend: Float = 0f,
        val residualLevelRatio: Float = 0f,
        val residualStillMs: Float = 0f,
        val residualReleaseRpsThreshold: Float = 0f,
        val residualAntiWindupX: Boolean = false,
        val residualAntiWindupY: Boolean = false,
        val softBoundaryActiveX: Boolean = false,
        val softBoundaryActiveY: Boolean = false,
        val softBoundaryRatioX: Float = 0f,
        val softBoundaryRatioY: Float = 0f,
        val unclampedRawTranslationX: Float = 0f,
        val unclampedRawTranslationY: Float = 0f,
        val rawTranslationX: Float = 0f,
        val rawTranslationY: Float = 0f,
        val targetTranslationX: Float = 0f,
        val targetTranslationY: Float = 0f,
        val unclampedTargetTranslationX: Float = 0f,
        val unclampedTargetTranslationY: Float = 0f,
        val maxTranslationX: Float = 0f,
        val maxTranslationY: Float = 0f,
        val remainingCropMarginX: Float = 0f,
        val remainingCropMarginY: Float = 0f,
        val maxOutputStepPx: Float = 0f,
        val outputStepDeltaMs: Float = 0f,
        val outputStepDeltaLimited: Boolean = false,
        val aimStillHoldActive: Boolean = false,
        val aimStillHoldBlend: Float = 0f,
        val aimStillHoldStepPx: Float = 0f,
        val aimStillHoldRawStepPx: Float = 0f,
        val aimStillHoldMaxStepPx: Float = 0f,
        val visualMotionValid: Boolean = false,
        val visualMotionHitSearchBoundary: Boolean = false,
        val visualMotionAgeMs: Float = 0f,
        val visualMotionRawDx: Float = 0f,
        val visualMotionRawDy: Float = 0f,
        val visualMotionRawMag: Float = 0f,
        val visualMotionScore: Float = 0f,
        val visualStillGateActive: Boolean = false,
        val visualStillGateBlend: Float = 0f,
        val visualStillGateTargetStepPx: Float = 0f,
        val outputLimitedX: Boolean = false,
        val outputLimitedY: Boolean = false,
        val outputAccelerationLimitedX: Boolean = false,
        val outputAccelerationLimitedY: Boolean = false,
        val translationClampedX: Boolean = false,
        val translationClampedY: Boolean = false,
        val modelFocalPxX: Float = 0f,
        val modelFocalPxY: Float = 0f,
        val lensFocalPxX: Float = 0f,
        val lensFocalPxY: Float = 0f,
        val exposureTimeMs: Float = 0f,
        val rollingShutterSkewMs: Float = 0f,
        val frameTargetOffsetMs: Float = 0f,
        val frameSyncEnabled: Boolean = false,
        val frameSamples: Int = 0,
        val frameDeltaMs: Float = 0f,
        val frameLagMs: Float = 0f,
        val poseQueryTimestampNs: Long = 0L,
        val gyroWindowStartTimestampNs: Long = 0L,
        val gyroWindowEndTimestampNs: Long = 0L,
        val gyroFirstSampleTimestampNs: Long = 0L,
        val gyroLastSampleTimestampNs: Long = 0L,
        val gyroMaxGapMs: Float = 0f,
        val gyroExtrapolationMs: Float = 0f,
        val stateChanged: Boolean = false,
    ) {
        companion object {
            val Identity = Transform()
        }
    }

    private data class GyroSample(
        val timestampNs: Long,
        val xRadPerSec: Float,
        val yRadPerSec: Float,
        val zRadPerSec: Float,
    )

    private data class IntegratedGyro(
        val xRad: Float,
        val yRad: Float,
        val zRad: Float,
        val samples: Int,
        val windowStartTimestampNs: Long = 0L,
        val windowEndTimestampNs: Long = 0L,
        val firstSampleTimestampNs: Long = 0L,
        val lastSampleTimestampNs: Long = 0L,
        val maxGapNs: Long = 0L,
        val extrapolationNs: Long = 0L,
    )

    private data class OutputAxisState(
        val value: Float,
        val velocity: Float,
        val velocityLimited: Boolean,
        val accelerationLimited: Boolean,
    )

    private data class SoftBoundaryAxisState(
        val value: Float,
        val active: Boolean,
        val ratio: Float,
    )

    private data class AimAdaptiveLowPassState(
        val blend: Float,
        val jitterPx: Float,
    )

    private data class AimMicroJitterClampState(
        val targetX: Float,
        val targetY: Float,
        val active: Boolean,
        val stepPx: Float,
        val stepChangePx: Float,
        val maxStepChangePx: Float,
        val reverseAlignment: Float,
        val strongStepReversal: Boolean,
    )

    private data class AimStillHoldState(
        val targetX: Float,
        val targetY: Float,
        val active: Boolean,
        val blend: Float,
        val stepPx: Float,
        val rawStepPx: Float,
        val maxStepPx: Float,
    )

    private data class VisualStillGateState(
        val targetX: Float,
        val targetY: Float,
        val active: Boolean,
        val blend: Float,
        val targetStepPx: Float,
        val visualValid: Boolean,
        val visualHitSearchBoundary: Boolean,
        val visualAgeMs: Float,
        val visualRawDx: Float,
        val visualRawDy: Float,
        val visualRawMag: Float,
        val visualScore: Float,
    )

    private val gyroSamples = ArrayDeque<GyroSample>()
    private var lastTimestampNs = 0L
    private var latestGyroSample: GyroSample? = null
    private var orientationPitchRad = 0f
    private var orientationYawRad = 0f
    private var orientationRollRad = 0f
    private var smoothPitchRad = 0f
    private var smoothYawRad = 0f
    private var smoothRollRad = 0f
    private var outputTranslationX = 0f
    private var outputTranslationY = 0f
    private var outputVelocityX = 0f
    private var outputVelocityY = 0f
    private var outputRotationDegrees = 0f
    private var residualStillDurationSec = 0f
    private var residualReleaseActive = false
    private var residualReleaseBlend = 0f
    private var reAimStillDurationSec = 0f
    private var reAimActive = false
    private var reAimBlend = 0f
    private var lastReAimTrigger = ""
    private var reAimEdgeFrames = 0
    private var aimAdaptiveLowPassBlend = 0f
    private var aimAdaptiveLowPassJitterPx = 0f
    private var hasAimRawTranslation = false
    private var aimLastRawTranslationX = 0f
    private var aimLastRawTranslationY = 0f
    private var hasAimRawDelta = false
    private var aimLastRawDeltaX = 0f
    private var aimLastRawDeltaY = 0f
    private var hasAimMicroJitterStep = false
    private var aimLastMicroJitterStepX = 0f
    private var aimLastMicroJitterStepY = 0f
    private var aimStillHoldActive = false
    private var aimStillHoldBlend = 0f
    private var latestVisualMotionFeedback: VisualMotionFeedback? = null
    private var visualStillGateActive = false
    private var visualStillGateBlend = 0f
    private var initialized = false
    private var lastTransform = Transform.Identity

    fun updateConfig(newConfig: Config) {
        if (config != newConfig) {
            config = newConfig
            reset()
        }
    }

    fun reset() {
        lastTimestampNs = 0L
        orientationPitchRad = 0f
        orientationYawRad = 0f
        orientationRollRad = 0f
        smoothPitchRad = 0f
        smoothYawRad = 0f
        smoothRollRad = 0f
        outputTranslationX = 0f
        outputTranslationY = 0f
        outputVelocityX = 0f
        outputVelocityY = 0f
        outputRotationDegrees = 0f
        residualStillDurationSec = 0f
        residualReleaseActive = false
        residualReleaseBlend = 0f
        reAimStillDurationSec = 0f
        reAimActive = false
        reAimBlend = 0f
        lastReAimTrigger = ""
        reAimEdgeFrames = 0
        aimAdaptiveLowPassBlend = 0f
        aimAdaptiveLowPassJitterPx = 0f
        hasAimRawTranslation = false
        aimLastRawTranslationX = 0f
        aimLastRawTranslationY = 0f
        hasAimRawDelta = false
        aimLastRawDeltaX = 0f
        aimLastRawDeltaY = 0f
        hasAimMicroJitterStep = false
        aimLastMicroJitterStepX = 0f
        aimLastMicroJitterStepY = 0f
        aimStillHoldActive = false
        aimStillHoldBlend = 0f
        latestVisualMotionFeedback = null
        visualStillGateActive = false
        visualStillGateBlend = 0f
        initialized = false
        lastTransform = Transform.Identity
        gyroSamples.clear()
        latestGyroSample = null
    }

    @Synchronized
    fun recordVisualMotionFeedback(feedback: VisualMotionFeedback) {
        if (feedback.timestampNs <= 0L) return
        val previous = latestVisualMotionFeedback
        if (previous != null && feedback.timestampNs < previous.timestampNs) return
        latestVisualMotionFeedback = feedback
    }

    @Synchronized
    fun resetVisualMotionFeedback() {
        latestVisualMotionFeedback = null
        visualStillGateActive = false
        visualStillGateBlend = 0f
    }

    @Synchronized
    fun recordGyroSample(
        timestampNs: Long,
        gyroXRadPerSec: Float,
        gyroYRadPerSec: Float,
        gyroZRadPerSec: Float,
    ) {
        if (timestampNs <= 0L) return
        val previous = latestGyroSample
        if (previous != null && timestampNs <= previous.timestampNs) return

        val sample = GyroSample(
            timestampNs = timestampNs,
            xRadPerSec = gyroXRadPerSec,
            yRadPerSec = gyroYRadPerSec,
            zRadPerSec = gyroZRadPerSec,
        )
        gyroSamples.addLast(sample)
        latestGyroSample = sample
        pruneGyroSamples(timestampNs)
    }

    @Synchronized
    fun update(
        timestampNs: Long,
        gyroXRadPerSec: Float,
        gyroYRadPerSec: Float,
        gyroZRadPerSec: Float,
        viewWidth: Float,
        viewHeight: Float,
        opticalZoomBase: Float,
        digitalZoomRatio: Float,
        displayZoomRatio: Float = 1f,
        renderScale: Float = 1f,
        lensFocalPxX: Float = 0f,
        lensFocalPxY: Float = 0f,
    ): Transform {
        if (viewWidth <= 0f || viewHeight <= 0f) return Transform.Identity
        if (timestampNs <= 0L) return lastTransform

        val rps = calculateRps(gyroXRadPerSec, gyroYRadPerSec, gyroZRadPerSec)
        val sourcePitchRate = if (config.swapPitchYawAxes) gyroYRadPerSec else gyroXRadPerSec
        val sourceYawRate = if (config.swapPitchYawAxes) gyroXRadPerSec else gyroYRadPerSec
        val sourceRollRate = gyroZRadPerSec
        val effectiveZoom = resolveEffectiveZoom(opticalZoomBase, digitalZoomRatio, displayZoomRatio)
        val modelFocalPx = calculateModelFocalPx(viewWidth, viewHeight, effectiveZoom)
        if (!initialized || lastTimestampNs <= 0L) {
            lastTimestampNs = timestampNs
            initialized = true
            val initialAimBlend = calculateAimBlend(rps)
            lastTransform = Transform(
                scale = config.cropScale,
                rps = rps,
                aimBlend = initialAimBlend,
                baseAimBlend = initialAimBlend,
                gyroXRadPerSec = gyroXRadPerSec,
                gyroYRadPerSec = gyroYRadPerSec,
                gyroZRadPerSec = gyroZRadPerSec,
                sourcePitchRate = sourcePitchRate,
                sourceYawRate = sourceYawRate,
                sourceRollRate = sourceRollRate,
                opticalZoomBase = opticalZoomBase.coerceAtLeast(1f),
                digitalZoomRatio = digitalZoomRatio.coerceAtLeast(1f),
                displayZoomRatio = displayZoomRatio.coerceAtLeast(1f),
                renderScale = renderScale.coerceAtLeast(config.cropScale.coerceAtLeast(1f)),
                effectiveZoom = effectiveZoom,
                outputRateLimitPxPerSecond = resolveOutputRateLimit(initialAimBlend),
                outputAccelerationLimitPxPerSecond2 = resolveOutputAccelerationLimit(initialAimBlend),
                residualReleaseRpsThreshold = residualReleaseRpsThreshold(),
                modelFocalPxX = modelFocalPx,
                modelFocalPxY = modelFocalPx,
                lensFocalPxX = lensFocalPxX.coerceAtLeast(0f),
                lensFocalPxY = lensFocalPxY.coerceAtLeast(0f),
                frameSyncEnabled = false,
                poseQueryTimestampNs = timestampNs,
            )
            return lastTransform
        }
        if (timestampNs <= lastTimestampNs) {
            return lastTransform
        }

        val startTimestampNs = lastTimestampNs
        val dtSec = ((timestampNs - startTimestampNs) / 1_000_000_000f)
            .coerceIn(0.001f, config.maxDeltaTimeSec)
        lastTimestampNs = timestampNs

        val pitchRate = if (config.invertVertical) -sourcePitchRate else sourcePitchRate
        val yawRate = if (config.invertHorizontal) -sourceYawRate else sourceYawRate
        val rollRate = if (config.invertRoll) -sourceRollRate else sourceRollRate

        return applyIntegratedMotion(
            pitchDeltaRad = pitchRate * dtSec,
            yawDeltaRad = yawRate * dtSec,
            rollDeltaRad = rollRate * dtSec,
            sourcePitchRate = sourcePitchRate,
            sourceYawRate = sourceYawRate,
            sourceRollRate = sourceRollRate,
            gyroXRadPerSec = gyroXRadPerSec,
            gyroYRadPerSec = gyroYRadPerSec,
            gyroZRadPerSec = gyroZRadPerSec,
            rps = rps,
            dtSec = dtSec,
            motionTimestampNs = timestampNs,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            opticalZoomBase = opticalZoomBase,
            digitalZoomRatio = digitalZoomRatio,
            displayZoomRatio = displayZoomRatio,
            renderScale = renderScale,
            effectiveZoom = effectiveZoom,
            lensFocalPxX = lensFocalPxX,
            lensFocalPxY = lensFocalPxY,
            exposureTimeMs = 0f,
            rollingShutterSkewMs = 0f,
            frameTargetOffsetMs = 0f,
            frameSyncEnabled = false,
            frameSamples = 0,
            frameLagMs = 0f,
            poseQueryTimestampNs = timestampNs,
            gyroWindowStartTimestampNs = startTimestampNs,
            gyroWindowEndTimestampNs = timestampNs,
            gyroFirstSampleTimestampNs = timestampNs,
            gyroLastSampleTimestampNs = timestampNs,
            gyroMaxGapMs = 0f,
            gyroExtrapolationMs = 0f,
        )
    }

    @Synchronized
    fun updateForFrame(
        frameTimestampNs: Long,
        exposureTimeNs: Long?,
        rollingShutterSkewNs: Long? = null,
        nowElapsedRealtimeNs: Long,
        viewWidth: Float,
        viewHeight: Float,
        opticalZoomBase: Float,
        digitalZoomRatio: Float,
        displayZoomRatio: Float = 1f,
        renderScale: Float = 1f,
        lensFocalPxX: Float = 0f,
        lensFocalPxY: Float = 0f,
    ): Transform {
        if (viewWidth <= 0f || viewHeight <= 0f || frameTimestampNs <= 0L) return Transform.Identity

        val safeExposureTimeNs = (exposureTimeNs ?: 0L).coerceAtLeast(0L)
        val safeRollingShutterSkewNs = (rollingShutterSkewNs ?: 0L).coerceAtLeast(0L)
        val exposureMidpointNs = safeExposureTimeNs / 2L
        val rollingCenterNs = safeRollingShutterSkewNs / 2L
        val timestampOffsetNs = (config.frameTimestampOffsetMs * 1_000_000f).roundToLong()
        val frameTargetOffsetNs = exposureMidpointNs + rollingCenterNs + timestampOffsetNs
        val targetTimestampNs = frameTimestampNs + frameTargetOffsetNs
        val latestSample = latestGyroSample
        val latestLagMs = latestSample?.let {
            (it.timestampNs - targetTimestampNs) / 1_000_000f
        } ?: ((nowElapsedRealtimeNs - targetTimestampNs) / 1_000_000f)
        val effectiveZoom = resolveEffectiveZoom(opticalZoomBase, digitalZoomRatio, displayZoomRatio)
        val modelFocalPx = calculateModelFocalPx(viewWidth, viewHeight, effectiveZoom)
        val exposureTimeMs = safeExposureTimeNs / 1_000_000f
        val rollingShutterSkewMs = safeRollingShutterSkewNs / 1_000_000f
        val frameTargetOffsetMs = frameTargetOffsetNs / 1_000_000f

        if (!initialized || lastTimestampNs <= 0L) {
            lastTimestampNs = targetTimestampNs
            initialized = true
            val gyroX = latestSample?.xRadPerSec ?: 0f
            val gyroY = latestSample?.yRadPerSec ?: 0f
            val gyroZ = latestSample?.zRadPerSec ?: 0f
            val sourcePitchRate = if (config.swapPitchYawAxes) gyroY else gyroX
            val sourceYawRate = if (config.swapPitchYawAxes) gyroX else gyroY
            val sourceRollRate = gyroZ
            val rps = calculateRps(gyroX, gyroY, gyroZ)
            val initialAimBlend = calculateAimBlend(rps)
            lastTransform = Transform(
                scale = config.cropScale,
                rps = rps,
                aimBlend = initialAimBlend,
                baseAimBlend = initialAimBlend,
                gyroXRadPerSec = gyroX,
                gyroYRadPerSec = gyroY,
                gyroZRadPerSec = gyroZ,
                sourcePitchRate = sourcePitchRate,
                sourceYawRate = sourceYawRate,
                sourceRollRate = sourceRollRate,
                opticalZoomBase = opticalZoomBase.coerceAtLeast(1f),
                digitalZoomRatio = digitalZoomRatio.coerceAtLeast(1f),
                displayZoomRatio = displayZoomRatio.coerceAtLeast(1f),
                renderScale = renderScale.coerceAtLeast(config.cropScale.coerceAtLeast(1f)),
                effectiveZoom = effectiveZoom,
                outputRateLimitPxPerSecond = resolveOutputRateLimit(initialAimBlend),
                outputAccelerationLimitPxPerSecond2 = resolveOutputAccelerationLimit(initialAimBlend),
                residualReleaseRpsThreshold = residualReleaseRpsThreshold(),
                modelFocalPxX = modelFocalPx,
                modelFocalPxY = modelFocalPx,
                lensFocalPxX = lensFocalPxX.coerceAtLeast(0f),
                lensFocalPxY = lensFocalPxY.coerceAtLeast(0f),
                exposureTimeMs = exposureTimeMs,
                rollingShutterSkewMs = rollingShutterSkewMs,
                frameTargetOffsetMs = frameTargetOffsetMs,
                frameSyncEnabled = true,
                frameSamples = 0,
                frameDeltaMs = 0f,
                frameLagMs = latestLagMs,
                poseQueryTimestampNs = targetTimestampNs,
            )
            return lastTransform
        }
        if (targetTimestampNs <= lastTimestampNs) {
            return lastTransform
        }

        val startTimestampNs = lastTimestampNs
        val rawDtSec = (targetTimestampNs - startTimestampNs) / 1_000_000_000f

        val integrated = integrateGyroWindow(startTimestampNs, targetTimestampNs)
        val dtSec = rawDtSec.coerceIn(0.001f, MAX_FRAME_SYNC_DELTA_SEC)
        val motionDtSec = rawDtSec.coerceAtLeast(0.001f)
        val hasUsableGyroWindow = integrated.lastSampleTimestampNs > 0L &&
            integrated.extrapolationNs <= MAX_FRAME_SYNC_GYRO_EXTRAPOLATION_NS &&
            (integrated.maxGapNs <= 0L || integrated.maxGapNs <= MAX_FRAME_SYNC_GYRO_GAP_NS)
        if (!hasUsableGyroWindow) {
            lastTransform = lastTransform.copy(
                scale = config.cropScale,
                frameSyncEnabled = true,
                frameSamples = 0,
                frameDeltaMs = rawDtSec * 1000f,
                frameLagMs = latestLagMs,
                opticalZoomBase = opticalZoomBase.coerceAtLeast(1f),
                digitalZoomRatio = digitalZoomRatio.coerceAtLeast(1f),
                displayZoomRatio = displayZoomRatio.coerceAtLeast(1f),
                renderScale = renderScale.coerceAtLeast(config.cropScale.coerceAtLeast(1f)),
                effectiveZoom = effectiveZoom,
                modelFocalPxX = modelFocalPx,
                modelFocalPxY = modelFocalPx,
                lensFocalPxX = lensFocalPxX.coerceAtLeast(0f),
                lensFocalPxY = lensFocalPxY.coerceAtLeast(0f),
                exposureTimeMs = exposureTimeMs,
                rollingShutterSkewMs = rollingShutterSkewMs,
                frameTargetOffsetMs = frameTargetOffsetMs,
                poseQueryTimestampNs = targetTimestampNs,
                gyroWindowStartTimestampNs = integrated.windowStartTimestampNs,
                gyroWindowEndTimestampNs = integrated.windowEndTimestampNs,
                gyroFirstSampleTimestampNs = integrated.firstSampleTimestampNs,
                gyroLastSampleTimestampNs = integrated.lastSampleTimestampNs,
                gyroMaxGapMs = integrated.maxGapNs / 1_000_000f,
                gyroExtrapolationMs = integrated.extrapolationNs / 1_000_000f,
                stateChanged = true,
            )
            return lastTransform
        }
        lastTimestampNs = targetTimestampNs

        val gyroXRadPerSec = integrated.xRad / motionDtSec
        val gyroYRadPerSec = integrated.yRad / motionDtSec
        val gyroZRadPerSec = integrated.zRad / motionDtSec
        val sourcePitchDelta = if (config.swapPitchYawAxes) integrated.yRad else integrated.xRad
        val sourceYawDelta = if (config.swapPitchYawAxes) integrated.xRad else integrated.yRad
        val sourceRollDelta = integrated.zRad
        val sourcePitchRate = sourcePitchDelta / motionDtSec
        val sourceYawRate = sourceYawDelta / motionDtSec
        val sourceRollRate = sourceRollDelta / motionDtSec
        val pitchDelta = if (config.invertVertical) -sourcePitchDelta else sourcePitchDelta
        val yawDelta = if (config.invertHorizontal) -sourceYawDelta else sourceYawDelta
        val rollDelta = if (config.invertRoll) -sourceRollDelta else sourceRollDelta

        return applyIntegratedMotion(
            pitchDeltaRad = pitchDelta,
            yawDeltaRad = yawDelta,
            rollDeltaRad = rollDelta,
            sourcePitchRate = sourcePitchRate,
            sourceYawRate = sourceYawRate,
            sourceRollRate = sourceRollRate,
            gyroXRadPerSec = gyroXRadPerSec,
            gyroYRadPerSec = gyroYRadPerSec,
            gyroZRadPerSec = gyroZRadPerSec,
            rps = calculateRps(gyroXRadPerSec, gyroYRadPerSec, gyroZRadPerSec),
            dtSec = dtSec,
            frameDeltaMs = rawDtSec * 1000f,
            motionTimestampNs = frameTimestampNs,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            opticalZoomBase = opticalZoomBase,
            digitalZoomRatio = digitalZoomRatio,
            displayZoomRatio = displayZoomRatio,
            renderScale = renderScale,
            effectiveZoom = effectiveZoom,
            lensFocalPxX = lensFocalPxX,
            lensFocalPxY = lensFocalPxY,
            exposureTimeMs = exposureTimeMs,
            rollingShutterSkewMs = rollingShutterSkewMs,
            frameTargetOffsetMs = frameTargetOffsetMs,
            frameSyncEnabled = true,
            frameSamples = integrated.samples,
            frameLagMs = latestLagMs,
            poseQueryTimestampNs = targetTimestampNs,
            gyroWindowStartTimestampNs = integrated.windowStartTimestampNs,
            gyroWindowEndTimestampNs = integrated.windowEndTimestampNs,
            gyroFirstSampleTimestampNs = integrated.firstSampleTimestampNs,
            gyroLastSampleTimestampNs = integrated.lastSampleTimestampNs,
            gyroMaxGapMs = integrated.maxGapNs / 1_000_000f,
            gyroExtrapolationMs = integrated.extrapolationNs / 1_000_000f,
        )
    }

    private fun applyIntegratedMotion(
        pitchDeltaRad: Float,
        yawDeltaRad: Float,
        rollDeltaRad: Float,
        sourcePitchRate: Float,
        sourceYawRate: Float,
        sourceRollRate: Float,
        gyroXRadPerSec: Float,
        gyroYRadPerSec: Float,
        gyroZRadPerSec: Float,
        rps: Float,
        dtSec: Float,
        frameDeltaMs: Float = dtSec * 1000f,
        motionTimestampNs: Long,
        viewWidth: Float,
        viewHeight: Float,
        opticalZoomBase: Float,
        digitalZoomRatio: Float,
        displayZoomRatio: Float,
        renderScale: Float,
        effectiveZoom: Float,
        lensFocalPxX: Float,
        lensFocalPxY: Float,
        exposureTimeMs: Float,
        rollingShutterSkewMs: Float,
        frameTargetOffsetMs: Float,
        frameSyncEnabled: Boolean,
        frameSamples: Int,
        frameLagMs: Float,
        poseQueryTimestampNs: Long,
        gyroWindowStartTimestampNs: Long,
        gyroWindowEndTimestampNs: Long,
        gyroFirstSampleTimestampNs: Long,
        gyroLastSampleTimestampNs: Long,
        gyroMaxGapMs: Float,
        gyroExtrapolationMs: Float,
    ): Transform {
        val baseAimBlend = calculateAimBlend(rps)
        val focalPx = calculateModelFocalPx(viewWidth, viewHeight, effectiveZoom)
        val scale = config.cropScale.coerceAtLeast(1f)
        val finalRenderScale = renderScale.coerceAtLeast(scale)
        val cropMarginX = viewWidth * (finalRenderScale - 1f) * 0.46f
        val cropMarginY = viewHeight * (finalRenderScale - 1f) * 0.46f
        val maxDx = min(viewWidth * config.maxShiftRatio, cropMarginX)
        val maxDy = min(viewHeight * config.maxShiftRatio, cropMarginY)
        val previousResidualLevelRatio = calculateResidualLevelRatio(
            x1 = outputTranslationX,
            y1 = outputTranslationY,
            x2 = lastTransform.rawTranslationX,
            y2 = lastTransform.rawTranslationY,
            x3 = lastTransform.targetTranslationX,
            y3 = lastTransform.targetTranslationY,
            maxX = maxDx,
            maxY = maxDy,
        )
        val reAimTrigger = updateReAimState(
            rps = rps,
            dtSec = dtSec,
            residualLevelRatio = previousResidualLevelRatio,
        )
        val activeReAimBlend = updateReAimBlend(dtSec)
        val aimBlend = baseAimBlend * (1f - activeReAimBlend)
        val strength = config.strength.coerceIn(0f, 2f) * lerp(
            start = 1f,
            end = config.aimStrengthMultiplier.coerceIn(1f, 3f),
            t = aimBlend,
        )
        val horizontalStrength = strength * config.horizontalGain.coerceIn(0f, 4f)
        val verticalStrength = strength * config.verticalGain.coerceIn(0f, 4f)
        val deadband = lerp(
            start = config.deadbandPx,
            end = config.aimDeadbandPx,
            t = aimBlend,
        )

        orientationPitchRad += pitchDeltaRad
        orientationYawRad += yawDeltaRad
        orientationRollRad += rollDeltaRad

        updateResidualReleaseState(
            rps = rps,
            dtSec = dtSec,
            residualLevelRatio = previousResidualLevelRatio,
        )
        val releaseBlend = updateResidualReleaseBlend(dtSec)

        // 实时防抖使用“平滑路径 - 原始路径”的差值：
        // 1. 原始路径快速跟随手抖。
        // 2. 平滑路径只跟随慢速移动。
        // 3. 二者差值只保留快抖，转换成反向平移后不会把慢速转动一直拉住。
        //
        // 瞄准时的运动幅度很小，但长焦会把细小角速度放大成明显像素漂移。
        // 因此低转速区间使用更慢的路径跟随、更高的补偿强度和更小的像素死区；
        // 转速升高后平滑释放，避免主动扫动时画面被补偿拖住。
        val basePathFollow = lerp(
            start = config.pathFollowPerSecond,
            end = config.aimPathFollowPerSecond,
            t = aimBlend,
        ).coerceAtLeast(0.1f)
        val releasePathFollow = max(
            basePathFollow,
            config.residualReleasePathFollowPerSecond.coerceAtLeast(0.1f),
        )
        val releaseBlendedPathFollow = lerp(basePathFollow, releasePathFollow, releaseBlend)
        val reAimPathFollow = max(
            releaseBlendedPathFollow,
            config.reAimPathFollowPerSecond.coerceAtLeast(0.1f),
        )
        val activePathFollow = lerp(releaseBlendedPathFollow, reAimPathFollow, activeReAimBlend)
        val followAlpha = (1f - exp(-activePathFollow * dtSec))
            .toFloat()
            .coerceIn(0f, 1f)
        smoothPitchRad += (orientationPitchRad - smoothPitchRad) * followAlpha
        smoothYawRad += (orientationYawRad - smoothYawRad) * followAlpha
        smoothRollRad += (orientationRollRad - smoothRollRad) * followAlpha

        var correctionPitchRad = smoothPitchRad - orientationPitchRad
        var correctionYawRad = smoothYawRad - orientationYawRad
        val correctionRollRad = smoothRollRad - orientationRollRad

        val yawLimitResult = limitCorrectionRad(
            correctionRad = correctionYawRad,
            maxTranslationPx = maxDx,
            focalPx = focalPx,
            strength = horizontalStrength,
        )
        val pitchLimitResult = limitCorrectionRad(
            correctionRad = correctionPitchRad,
            maxTranslationPx = maxDy,
            focalPx = focalPx,
            strength = verticalStrength,
        )
        val residualAntiWindupX = config.residualAntiWindupEnabled && yawLimitResult.second
        val residualAntiWindupY = config.residualAntiWindupEnabled && pitchLimitResult.second
        if (residualAntiWindupX) {
            correctionYawRad = yawLimitResult.first
            smoothYawRad = orientationYawRad + correctionYawRad
        }
        if (residualAntiWindupY) {
            correctionPitchRad = pitchLimitResult.first
            smoothPitchRad = orientationPitchRad + correctionPitchRad
        }

        val unclampedRawTx = correctionYawRad * focalPx * horizontalStrength
        val unclampedRawTy = -correctionPitchRad * focalPx * verticalStrength
        val softRawX = applySoftBoundary(unclampedRawTx, maxDx)
        val softRawY = applySoftBoundary(unclampedRawTy, maxDy)
        val rawTx = applyDeadband(
            value = softRawX.value,
            deadband = deadband,
        )
        val rawTy = applyDeadband(
            value = softRawY.value,
            deadband = deadband,
        )
        val baseOutputAlpha = config.outputLowPassAlpha.coerceIn(0.05f, 1f)
        val aimOutputAlpha = config.aimOutputLowPassAlpha.coerceIn(0.05f, 1f)
        val softBoundaryOutputFade = calculateSoftBoundaryOutputFade(softRawX.ratio, softRawY.ratio)
        val aimOutputAlphaBlend = aimBlend * (1f - releaseBlend) * softBoundaryOutputFade
        val aimAdaptiveLowPass = updateAimAdaptiveLowPass(
            rawTx = rawTx,
            rawTy = rawTy,
            aimOutputAlphaBlend = aimOutputAlphaBlend,
            dtSec = dtSec,
        )
        val aimAdaptiveMinAlpha = min(
            aimOutputAlpha,
            config.aimAdaptiveLowPassMinAlpha.coerceIn(0.05f, 1f),
        )
        val adaptiveAimOutputAlpha = if (config.aimAdaptiveLowPassEnabled) {
            lerp(
                start = aimOutputAlpha,
                end = aimAdaptiveMinAlpha,
                t = aimAdaptiveLowPass.blend,
            )
        } else {
            aimOutputAlpha
        }
        val outputAlpha = lerp(
            start = baseOutputAlpha,
            end = adaptiveAimOutputAlpha,
            t = aimOutputAlphaBlend,
        )
        val outputRateLimitPxPerSecond = resolveOutputRateLimit(aimBlend, releaseBlend)
        val outputAccelerationLimitPxPerSecond2 = resolveOutputAccelerationLimit(aimBlend, releaseBlend)
        val outputStepDtSec = resolveOutputStepDeltaSec(dtSec)
        val outputStepDeltaLimited = outputStepDtSec < dtSec - 0.0005f
        val maxOutputStepPx = outputRateLimitPxPerSecond * outputStepDtSec
        val unclampedTargetTranslationX = outputTranslationX + (rawTx - outputTranslationX) * outputAlpha
        val unclampedTargetTranslationY = outputTranslationY + (rawTy - outputTranslationY) * outputAlpha
        val aimMicroJitterClamp = applyAimMicroJitterClamp(
            targetX = unclampedTargetTranslationX,
            targetY = unclampedTargetTranslationY,
            currentX = outputTranslationX,
            currentY = outputTranslationY,
            aimBlend = aimBlend,
            releaseBlend = releaseBlend,
            reAimBlend = activeReAimBlend,
            softBoundaryActive = softRawX.active || softRawY.active,
        )
        val aimStillHold = applyAimStillHold(
            targetX = aimMicroJitterClamp.targetX,
            targetY = aimMicroJitterClamp.targetY,
            currentX = outputTranslationX,
            currentY = outputTranslationY,
            rawStepX = rawTx - lastTransform.rawTranslationX,
            rawStepY = rawTy - lastTransform.rawTranslationY,
            rps = rps,
            aimBlend = aimBlend,
            releaseBlend = releaseBlend,
            reAimBlend = activeReAimBlend,
            softBoundaryActive = softRawX.active || softRawY.active,
            dtSec = dtSec,
        )
        val visualStillGate = applyVisualStillGate(
            targetX = aimStillHold.targetX,
            targetY = aimStillHold.targetY,
            currentX = outputTranslationX,
            currentY = outputTranslationY,
            rps = rps,
            aimBlend = aimBlend,
            releaseBlend = releaseBlend,
            reAimBlend = activeReAimBlend,
            softBoundaryActive = softRawX.active || softRawY.active,
            motionTimestampNs = motionTimestampNs,
            dtSec = dtSec,
        )
        val targetTranslationX = visualStillGate.targetX
        val targetTranslationY = visualStillGate.targetY
        val outputX = updateOutputAxis(
            current = outputTranslationX,
            currentVelocity = outputVelocityX,
            target = targetTranslationX,
            dtSec = outputStepDtSec,
            maxVelocityPxPerSecond = outputRateLimitPxPerSecond,
            maxAccelerationPxPerSecond2 = outputAccelerationLimitPxPerSecond2,
        )
        val outputY = updateOutputAxis(
            current = outputTranslationY,
            currentVelocity = outputVelocityY,
            target = targetTranslationY,
            dtSec = outputStepDtSec,
            maxVelocityPxPerSecond = outputRateLimitPxPerSecond,
            maxAccelerationPxPerSecond2 = outputAccelerationLimitPxPerSecond2,
        )
        outputTranslationX = outputX.value
        outputTranslationY = outputY.value
        outputVelocityX = outputX.velocity
        outputVelocityY = outputY.velocity
        val finalTranslationX = outputTranslationX.coerceIn(-maxDx, maxDx)
        val finalTranslationY = outputTranslationY.coerceIn(-maxDy, maxDy)
        if (finalTranslationX != outputTranslationX) {
            outputVelocityX = 0f
        }
        if (finalTranslationY != outputTranslationY) {
            outputVelocityY = 0f
        }
        outputTranslationX = finalTranslationX
        outputTranslationY = finalTranslationY
        val translationClampedX = maxDx > 0f &&
            (residualAntiWindupX || abs(unclampedRawTx) > maxDx || abs(finalTranslationX) >= maxDx - 0.001f)
        val translationClampedY = maxDy > 0f &&
            (residualAntiWindupY || abs(unclampedRawTy) > maxDy || abs(finalTranslationY) >= maxDy - 0.001f)
        val residualLevelRatio = calculateResidualLevelRatio(
            x1 = finalTranslationX,
            y1 = finalTranslationY,
            x2 = rawTx,
            y2 = rawTy,
            x3 = targetTranslationX,
            y3 = targetTranslationY,
            maxX = maxDx,
            maxY = maxDy,
        )
        val maxRollDegrees = config.maxRollDegrees.coerceAtLeast(0f)
        val rawRotationDegrees = if (config.rollCompensationEnabled && maxRollDegrees > 0f) {
            (correctionRollRad * RAD_TO_DEGREES * config.rollStrength.coerceIn(0f, 2f))
                .coerceIn(-maxRollDegrees, maxRollDegrees)
        } else {
            0f
        }
        outputRotationDegrees += (rawRotationDegrees - outputRotationDegrees) * outputAlpha
        val residualReleaseActive = releaseBlend > 0.001f
        val remainingCropMarginX = (maxDx - abs(finalTranslationX)).coerceAtLeast(0f)
        val remainingCropMarginY = (maxDy - abs(finalTranslationY)).coerceAtLeast(0f)
        val stateChanged =
            this.reAimActive != lastTransform.reAimActive ||
                activeReAimBlend > 0.001f && lastTransform.reAimBlend <= 0.001f ||
                activeReAimBlend <= 0.001f && lastTransform.reAimBlend > 0.001f ||
                residualReleaseActive != lastTransform.residualReleaseActive ||
                aimMicroJitterClamp.active != lastTransform.aimMicroJitterClampActive ||
                aimStillHold.active != lastTransform.aimStillHoldActive ||
                visualStillGate.active != lastTransform.visualStillGateActive ||
                softRawX.active != lastTransform.softBoundaryActiveX ||
                softRawY.active != lastTransform.softBoundaryActiveY ||
                outputX.velocityLimited != lastTransform.outputLimitedX ||
                outputY.velocityLimited != lastTransform.outputLimitedY ||
                outputX.accelerationLimited != lastTransform.outputAccelerationLimitedX ||
                outputY.accelerationLimited != lastTransform.outputAccelerationLimitedY ||
                translationClampedX != lastTransform.translationClampedX ||
                translationClampedY != lastTransform.translationClampedY ||
                reAimTrigger != lastTransform.reAimTrigger

        lastTransform = Transform(
            scale = scale,
            translationX = finalTranslationX,
            translationY = finalTranslationY,
            rotationDegrees = outputRotationDegrees.coerceIn(-maxRollDegrees, maxRollDegrees),
            rps = rps,
            aimBlend = aimBlend,
            baseAimBlend = baseAimBlend,
            reAimActive = this.reAimActive,
            reAimBlend = activeReAimBlend,
            reAimStillMs = reAimStillDurationSec * 1000f,
            reAimTrigger = reAimTrigger,
            gyroXRadPerSec = gyroXRadPerSec,
            gyroYRadPerSec = gyroYRadPerSec,
            gyroZRadPerSec = gyroZRadPerSec,
            sourcePitchRate = sourcePitchRate,
            sourceYawRate = sourceYawRate,
            sourceRollRate = sourceRollRate,
            opticalZoomBase = opticalZoomBase.coerceAtLeast(1f),
            digitalZoomRatio = digitalZoomRatio.coerceAtLeast(1f),
            displayZoomRatio = displayZoomRatio.coerceAtLeast(1f),
            renderScale = finalRenderScale,
            effectiveZoom = effectiveZoom,
            outputLowPassAlpha = outputAlpha,
            aimAdaptiveLowPassBlend = aimAdaptiveLowPass.blend,
            aimAdaptiveLowPassJitterPx = aimAdaptiveLowPass.jitterPx,
            aimMicroJitterClampActive = aimMicroJitterClamp.active,
            aimMicroJitterStepPx = aimMicroJitterClamp.stepPx,
            aimMicroJitterStepChangePx = aimMicroJitterClamp.stepChangePx,
            aimMicroJitterMaxStepChangePx = aimMicroJitterClamp.maxStepChangePx,
            aimMicroJitterReverseAlignment = aimMicroJitterClamp.reverseAlignment,
            aimMicroJitterStrongStepReversal = aimMicroJitterClamp.strongStepReversal,
            outputRateLimitPxPerSecond = outputRateLimitPxPerSecond,
            outputAccelerationLimitPxPerSecond2 = outputAccelerationLimitPxPerSecond2,
            outputVelocityX = outputVelocityX,
            outputVelocityY = outputVelocityY,
            residualReleaseActive = residualReleaseActive,
            residualReleaseBlend = releaseBlend,
            residualLevelRatio = residualLevelRatio,
            residualStillMs = residualStillDurationSec * 1000f,
            residualReleaseRpsThreshold = residualReleaseRpsThreshold(),
            residualAntiWindupX = residualAntiWindupX,
            residualAntiWindupY = residualAntiWindupY,
            softBoundaryActiveX = softRawX.active,
            softBoundaryActiveY = softRawY.active,
            softBoundaryRatioX = softRawX.ratio,
            softBoundaryRatioY = softRawY.ratio,
            unclampedRawTranslationX = unclampedRawTx,
            unclampedRawTranslationY = unclampedRawTy,
            rawTranslationX = rawTx,
            rawTranslationY = rawTy,
            targetTranslationX = targetTranslationX,
            targetTranslationY = targetTranslationY,
            unclampedTargetTranslationX = unclampedTargetTranslationX,
            unclampedTargetTranslationY = unclampedTargetTranslationY,
            maxTranslationX = maxDx,
            maxTranslationY = maxDy,
            remainingCropMarginX = remainingCropMarginX,
            remainingCropMarginY = remainingCropMarginY,
            maxOutputStepPx = maxOutputStepPx,
            outputStepDeltaMs = outputStepDtSec * 1000f,
            outputStepDeltaLimited = outputStepDeltaLimited,
            aimStillHoldActive = aimStillHold.active,
            aimStillHoldBlend = aimStillHold.blend,
            aimStillHoldStepPx = aimStillHold.stepPx,
            aimStillHoldRawStepPx = aimStillHold.rawStepPx,
            aimStillHoldMaxStepPx = aimStillHold.maxStepPx,
            visualMotionValid = visualStillGate.visualValid,
            visualMotionHitSearchBoundary = visualStillGate.visualHitSearchBoundary,
            visualMotionAgeMs = visualStillGate.visualAgeMs,
            visualMotionRawDx = visualStillGate.visualRawDx,
            visualMotionRawDy = visualStillGate.visualRawDy,
            visualMotionRawMag = visualStillGate.visualRawMag,
            visualMotionScore = visualStillGate.visualScore,
            visualStillGateActive = visualStillGate.active,
            visualStillGateBlend = visualStillGate.blend,
            visualStillGateTargetStepPx = visualStillGate.targetStepPx,
            outputLimitedX = outputX.velocityLimited,
            outputLimitedY = outputY.velocityLimited,
            outputAccelerationLimitedX = outputX.accelerationLimited,
            outputAccelerationLimitedY = outputY.accelerationLimited,
            translationClampedX = translationClampedX,
            translationClampedY = translationClampedY,
            modelFocalPxX = focalPx,
            modelFocalPxY = focalPx,
            lensFocalPxX = lensFocalPxX.coerceAtLeast(0f),
            lensFocalPxY = lensFocalPxY.coerceAtLeast(0f),
            exposureTimeMs = exposureTimeMs,
            rollingShutterSkewMs = rollingShutterSkewMs,
            frameTargetOffsetMs = frameTargetOffsetMs,
            frameSyncEnabled = frameSyncEnabled,
            frameSamples = frameSamples,
            frameDeltaMs = frameDeltaMs,
            frameLagMs = frameLagMs,
            poseQueryTimestampNs = poseQueryTimestampNs,
            gyroWindowStartTimestampNs = gyroWindowStartTimestampNs,
            gyroWindowEndTimestampNs = gyroWindowEndTimestampNs,
            gyroFirstSampleTimestampNs = gyroFirstSampleTimestampNs,
            gyroLastSampleTimestampNs = gyroLastSampleTimestampNs,
            gyroMaxGapMs = gyroMaxGapMs,
            gyroExtrapolationMs = gyroExtrapolationMs,
            stateChanged = stateChanged,
        )
        return lastTransform
    }

    private fun integrateGyroWindow(startTimestampNs: Long, endTimestampNs: Long): IntegratedGyro {
        if (endTimestampNs <= startTimestampNs || gyroSamples.isEmpty()) {
            return IntegratedGyro(
                xRad = 0f,
                yRad = 0f,
                zRad = 0f,
                samples = 0,
                windowStartTimestampNs = startTimestampNs,
                windowEndTimestampNs = endTimestampNs,
            )
        }

        var previous: GyroSample? = null
        var cursorNs = startTimestampNs
        var samples = 0
        var xRad = 0f
        var yRad = 0f
        var zRad = 0f
        var firstSampleTimestampNs = 0L
        var lastSampleTimestampNs = 0L
        var maxGapNs = 0L

        fun observeSampleTimestamp(timestampNs: Long) {
            if (timestampNs <= 0L) return
            if (firstSampleTimestampNs == 0L) {
                firstSampleTimestampNs = timestampNs
            }
            if (lastSampleTimestampNs > 0L) {
                maxGapNs = max(maxGapNs, timestampNs - lastSampleTimestampNs)
            }
            lastSampleTimestampNs = timestampNs
        }

        val iterator = gyroSamples.iterator()
        while (iterator.hasNext()) {
            val sample = iterator.next()
            if (sample.timestampNs <= startTimestampNs) {
                previous = sample
                continue
            }
            if (sample.timestampNs > endTimestampNs) break

            val prev = previous
            if (prev == null) {
                observeSampleTimestamp(sample.timestampNs)
                previous = sample
                cursorNs = sample.timestampNs.coerceAtLeast(startTimestampNs)
                samples++
                continue
            }

            observeSampleTimestamp(prev.timestampNs)
            observeSampleTimestamp(sample.timestampNs)
            val dtSec = ((sample.timestampNs - cursorNs).coerceAtLeast(0L)) / 1_000_000_000f
            xRad += prev.xRadPerSec * dtSec
            yRad += prev.yRadPerSec * dtSec
            zRad += prev.zRadPerSec * dtSec
            cursorNs = sample.timestampNs
            previous = sample
            samples++
        }

        previous?.let { prev ->
            observeSampleTimestamp(prev.timestampNs)
            if (cursorNs < endTimestampNs) {
                val dtSec = (endTimestampNs - cursorNs) / 1_000_000_000f
                xRad += prev.xRadPerSec * dtSec
                yRad += prev.yRadPerSec * dtSec
                zRad += prev.zRadPerSec * dtSec
            }
        }

        return IntegratedGyro(
            xRad = xRad,
            yRad = yRad,
            zRad = zRad,
            samples = samples,
            windowStartTimestampNs = startTimestampNs,
            windowEndTimestampNs = endTimestampNs,
            firstSampleTimestampNs = firstSampleTimestampNs,
            lastSampleTimestampNs = lastSampleTimestampNs,
            maxGapNs = maxGapNs,
            extrapolationNs = (endTimestampNs - lastSampleTimestampNs).coerceAtLeast(0L),
        )
    }

    private fun pruneGyroSamples(currentTimestampNs: Long) {
        val minTimestampNs = currentTimestampNs - GYRO_BUFFER_DURATION_NS
        while (gyroSamples.isNotEmpty() && gyroSamples.first.timestampNs < minTimestampNs) {
            gyroSamples.removeFirst()
        }
    }

    private fun applyDeadband(value: Float, deadband: Float): Float {
        return if (abs(value) < deadband.coerceAtLeast(0f)) 0f else value
    }

    private fun applySoftBoundary(value: Float, maxTranslationPx: Float): SoftBoundaryAxisState {
        if (maxTranslationPx <= 0f) {
            return SoftBoundaryAxisState(value = 0f, active = false, ratio = 0f)
        }

        val hardLimitedValue = value.coerceIn(-maxTranslationPx, maxTranslationPx)
        val ratio = (abs(value) / maxTranslationPx).coerceIn(0f, 9.999f)
        if (!config.softBoundaryEnabled) {
            return SoftBoundaryAxisState(
                value = hardLimitedValue,
                active = abs(value) > maxTranslationPx,
                ratio = ratio,
            )
        }

        val startRatio = config.softBoundaryStartRatio.coerceIn(0.05f, 0.98f)
        val strength = config.softBoundaryStrength.coerceIn(0f, 8f)
        val startPx = maxTranslationPx * startRatio
        val edgePx = (maxTranslationPx - startPx).coerceAtLeast(0.001f)
        val absHardLimited = abs(hardLimitedValue)
        if (strength <= 0f || absHardLimited <= startPx) {
            return SoftBoundaryAxisState(
                value = hardLimitedValue,
                active = false,
                ratio = ratio,
            )
        }

        val edgeRatio = ((absHardLimited - startPx) / edgePx).coerceAtLeast(0f)
        val softenedEdgeRatio = edgeRatio / (1f + strength * edgeRatio)
        val softenedAbs = startPx + edgePx * softenedEdgeRatio.coerceIn(0f, 1f)
        val softenedValue = if (hardLimitedValue < 0f) -softenedAbs else softenedAbs
        return SoftBoundaryAxisState(
            value = softenedValue.coerceIn(-maxTranslationPx, maxTranslationPx),
            active = true,
            ratio = ratio,
        )
    }

    private fun calculateSoftBoundaryOutputFade(ratioX: Float, ratioY: Float): Float {
        val edgeRatio = max(ratioX, ratioY)
        if (!config.softBoundaryEnabled) {
            return if (edgeRatio > 1f) 0f else 1f
        }
        val startRatio = config.softBoundaryStartRatio.coerceIn(0.05f, 0.98f)
        val t = ((edgeRatio - startRatio) / (1f - startRatio).coerceAtLeast(0.001f))
            .coerceIn(0f, 1f)
        return 1f - smoothStep(t)
    }

    private fun updateAimAdaptiveLowPass(
        rawTx: Float,
        rawTy: Float,
        aimOutputAlphaBlend: Float,
        dtSec: Float,
    ): AimAdaptiveLowPassState {
        if (!config.aimAdaptiveLowPassEnabled) {
            hasAimRawTranslation = false
            hasAimRawDelta = false
            aimAdaptiveLowPassJitterPx = 0f
            aimAdaptiveLowPassBlend = 0f
            return AimAdaptiveLowPassState(
                blend = aimAdaptiveLowPassBlend,
                jitterPx = aimAdaptiveLowPassJitterPx,
            )
        }

        val eligible = aimOutputAlphaBlend > 0.5f
        if (!eligible) {
            hasAimRawTranslation = false
            hasAimRawDelta = false
            aimAdaptiveLowPassJitterPx = 0f
            aimAdaptiveLowPassBlend = moveToward(
                current = aimAdaptiveLowPassBlend,
                target = 0f,
                step = AIM_ADAPTIVE_LOW_PASS_BLEND_OUT_PER_SECOND * dtSec.coerceAtLeast(0f),
            )
            return AimAdaptiveLowPassState(
                blend = aimAdaptiveLowPassBlend,
                jitterPx = aimAdaptiveLowPassJitterPx,
            )
        }

        if (!hasAimRawTranslation) {
            aimLastRawTranslationX = rawTx
            aimLastRawTranslationY = rawTy
            hasAimRawTranslation = true
            return AimAdaptiveLowPassState(
                blend = aimAdaptiveLowPassBlend,
                jitterPx = 0f,
            )
        }

        val rawDeltaX = rawTx - aimLastRawTranslationX
        val rawDeltaY = rawTy - aimLastRawTranslationY
        aimLastRawTranslationX = rawTx
        aimLastRawTranslationY = rawTy

        if (!hasAimRawDelta) {
            aimLastRawDeltaX = rawDeltaX
            aimLastRawDeltaY = rawDeltaY
            hasAimRawDelta = true
            return AimAdaptiveLowPassState(
                blend = aimAdaptiveLowPassBlend,
                jitterPx = 0f,
            )
        }

        aimAdaptiveLowPassJitterPx = calculateAimAdaptiveJitterPx(
            rawDeltaX = rawDeltaX,
            rawDeltaY = rawDeltaY,
            previousRawDeltaX = aimLastRawDeltaX,
            previousRawDeltaY = aimLastRawDeltaY,
        )
        aimLastRawDeltaX = rawDeltaX
        aimLastRawDeltaY = rawDeltaY

        val jitterRange = (AIM_ADAPTIVE_LOW_PASS_FULL_JITTER_PX - AIM_ADAPTIVE_LOW_PASS_START_JITTER_PX)
            .coerceAtLeast(0.001f)
        val targetBlend = smoothStep(
            ((aimAdaptiveLowPassJitterPx - AIM_ADAPTIVE_LOW_PASS_START_JITTER_PX) / jitterRange)
                .coerceIn(0f, 1f),
        ) * aimOutputAlphaBlend.coerceIn(0f, 1f)
        val ratePerSecond = if (targetBlend > aimAdaptiveLowPassBlend) {
            AIM_ADAPTIVE_LOW_PASS_BLEND_IN_PER_SECOND
        } else {
            AIM_ADAPTIVE_LOW_PASS_BLEND_OUT_PER_SECOND
        }
        aimAdaptiveLowPassBlend = moveToward(
            current = aimAdaptiveLowPassBlend,
            target = targetBlend,
            step = ratePerSecond * dtSec.coerceAtLeast(0f),
        )
        return AimAdaptiveLowPassState(
            blend = aimAdaptiveLowPassBlend,
            jitterPx = aimAdaptiveLowPassJitterPx,
        )
    }

    private fun calculateAimAdaptiveJitterPx(
        rawDeltaX: Float,
        rawDeltaY: Float,
        previousRawDeltaX: Float,
        previousRawDeltaY: Float,
    ): Float {
        val deltaMag = sqrt(rawDeltaX * rawDeltaX + rawDeltaY * rawDeltaY)
        val previousDeltaMag = sqrt(previousRawDeltaX * previousRawDeltaX + previousRawDeltaY * previousRawDeltaY)
        if (deltaMag < AIM_ADAPTIVE_LOW_PASS_MIN_DELTA_PX ||
            previousDeltaMag < AIM_ADAPTIVE_LOW_PASS_MIN_DELTA_PX
        ) {
            return 0f
        }

        val dot = rawDeltaX * previousRawDeltaX + rawDeltaY * previousRawDeltaY
        val reverseAlignment = (-dot / (deltaMag * previousDeltaMag)).coerceIn(0f, 1f)
        if (reverseAlignment <= 0f) return 0f

        return min(deltaMag, previousDeltaMag) * reverseAlignment
    }

    private fun applyAimMicroJitterClamp(
        targetX: Float,
        targetY: Float,
        currentX: Float,
        currentY: Float,
        aimBlend: Float,
        releaseBlend: Float,
        reAimBlend: Float,
        softBoundaryActive: Boolean,
    ): AimMicroJitterClampState {
        val requestedStepX = targetX - currentX
        val requestedStepY = targetY - currentY
        val requestedStepPx = calculateMagnitude(requestedStepX, requestedStepY)
        val maxStepChangePx = AIM_MICRO_JITTER_CLAMP_STEP_CHANGE_PX
        val eligible = AIM_MICRO_JITTER_CLAMP_ENABLED &&
            maxStepChangePx > 0f &&
            aimBlend >= AIM_MICRO_JITTER_CLAMP_MIN_AIM_BLEND &&
            releaseBlend <= AIM_MICRO_JITTER_CLAMP_MAX_RELEASE_BLEND &&
            reAimBlend <= AIM_MICRO_JITTER_CLAMP_MAX_RE_AIM_BLEND &&
            !softBoundaryActive

        if (!eligible) {
            resetAimMicroJitterClampHistory()
            return AimMicroJitterClampState(
                targetX = targetX,
                targetY = targetY,
                active = false,
                stepPx = requestedStepPx,
                stepChangePx = 0f,
                maxStepChangePx = maxStepChangePx,
                reverseAlignment = 0f,
                strongStepReversal = false,
            )
        }

        if (!hasAimMicroJitterStep) {
            aimLastMicroJitterStepX = requestedStepX
            aimLastMicroJitterStepY = requestedStepY
            hasAimMicroJitterStep = true
            return AimMicroJitterClampState(
                targetX = targetX,
                targetY = targetY,
                active = false,
                stepPx = requestedStepPx,
                stepChangePx = 0f,
                maxStepChangePx = maxStepChangePx,
                reverseAlignment = 0f,
                strongStepReversal = false,
            )
        }

        val stepChangeX = requestedStepX - aimLastMicroJitterStepX
        val stepChangeY = requestedStepY - aimLastMicroJitterStepY
        val stepChangePx = calculateMagnitude(stepChangeX, stepChangeY)
        val reverseAlignment = calculateReverseAlignment(
            x1 = requestedStepX,
            y1 = requestedStepY,
            x2 = aimLastMicroJitterStepX,
            y2 = aimLastMicroJitterStepY,
        )
        val strongStepReversal = reverseAlignment >= AIM_MICRO_JITTER_CLAMP_REVERSAL_BYPASS_ALIGNMENT &&
            requestedStepPx >= AIM_MICRO_JITTER_CLAMP_REVERSAL_MIN_STEP_PX &&
            calculateMagnitude(aimLastMicroJitterStepX, aimLastMicroJitterStepY) >=
                AIM_MICRO_JITTER_CLAMP_REVERSAL_MIN_STEP_PX
        if (strongStepReversal) {
            // A strong rebound should follow the new direction; slew-limiting it would keep old-direction output.
            aimLastMicroJitterStepX = requestedStepX
            aimLastMicroJitterStepY = requestedStepY
            return AimMicroJitterClampState(
                targetX = targetX,
                targetY = targetY,
                active = false,
                stepPx = requestedStepPx,
                stepChangePx = stepChangePx,
                maxStepChangePx = maxStepChangePx,
                reverseAlignment = reverseAlignment,
                strongStepReversal = true,
            )
        }
        if (stepChangePx <= maxStepChangePx) {
            aimLastMicroJitterStepX = requestedStepX
            aimLastMicroJitterStepY = requestedStepY
            return AimMicroJitterClampState(
                targetX = targetX,
                targetY = targetY,
                active = false,
                stepPx = requestedStepPx,
                stepChangePx = stepChangePx,
                maxStepChangePx = maxStepChangePx,
                reverseAlignment = reverseAlignment,
                strongStepReversal = false,
            )
        }

        val changeScale = maxStepChangePx / stepChangePx
        val limitedStepX = aimLastMicroJitterStepX + stepChangeX * changeScale
        val limitedStepY = aimLastMicroJitterStepY + stepChangeY * changeScale
        aimLastMicroJitterStepX = limitedStepX
        aimLastMicroJitterStepY = limitedStepY
        return AimMicroJitterClampState(
            targetX = currentX + limitedStepX,
            targetY = currentY + limitedStepY,
            active = true,
            stepPx = calculateMagnitude(limitedStepX, limitedStepY),
            stepChangePx = stepChangePx,
            maxStepChangePx = maxStepChangePx,
            reverseAlignment = reverseAlignment,
            strongStepReversal = false,
        )
    }

    private fun resetAimMicroJitterClampHistory() {
        hasAimMicroJitterStep = false
        aimLastMicroJitterStepX = 0f
        aimLastMicroJitterStepY = 0f
    }

    private fun applyAimStillHold(
        targetX: Float,
        targetY: Float,
        currentX: Float,
        currentY: Float,
        rawStepX: Float,
        rawStepY: Float,
        rps: Float,
        aimBlend: Float,
        releaseBlend: Float,
        reAimBlend: Float,
        softBoundaryActive: Boolean,
        dtSec: Float,
    ): AimStillHoldState {
        val requestedStepX = targetX - currentX
        val requestedStepY = targetY - currentY
        val requestedStepPx = calculateMagnitude(requestedStepX, requestedStepY)
        val rawStepPx = calculateMagnitude(rawStepX, rawStepY)
        val maxStepPx = AIM_STILL_HOLD_MAX_STEP_PX
        val maxRawStepPx = AIM_STILL_HOLD_MAX_RAW_STEP_PX
        val canHold = AIM_STILL_HOLD_ENABLED &&
            aimBlend >= AIM_STILL_HOLD_MIN_AIM_BLEND &&
            releaseBlend <= AIM_MICRO_JITTER_CLAMP_MAX_RELEASE_BLEND &&
            reAimBlend <= AIM_MICRO_JITTER_CLAMP_MAX_RE_AIM_BLEND &&
            !softBoundaryActive

        if (!canHold) {
            aimStillHoldActive = false
            aimStillHoldBlend = 0f
            return AimStillHoldState(
                targetX = targetX,
                targetY = targetY,
                active = false,
                blend = 0f,
                stepPx = requestedStepPx,
                rawStepPx = rawStepPx,
                maxStepPx = maxStepPx,
            )
        }

        val requestedStepRatio = requestedStepPx / maxStepPx.coerceAtLeast(0.001f)
        val rawStepRatio = rawStepPx / maxRawStepPx.coerceAtLeast(0.001f)
        val stepWindowRatio = max(requestedStepRatio, rawStepRatio)
        val withinStillRps = if (aimStillHoldActive) {
            rps <= AIM_STILL_HOLD_EXIT_RPS
        } else {
            rps <= AIM_STILL_HOLD_ENTER_RPS
        }
        val withinStepWindow = if (aimStillHoldActive) {
            stepWindowRatio <= AIM_STILL_HOLD_SOFT_EXIT_STEP_RATIO
        } else {
            stepWindowRatio <= 1f
        }
        val shouldHold = withinStillRps && withinStepWindow

        aimStillHoldActive = shouldHold
        aimStillHoldBlend = moveToward(
            current = aimStillHoldBlend,
            target = if (shouldHold) 1f else 0f,
            step = if (shouldHold) {
                AIM_STILL_HOLD_BLEND_IN_PER_SECOND
            } else {
                AIM_STILL_HOLD_BLEND_OUT_PER_SECOND
            } * dtSec.coerceAtLeast(0f),
        )

        val stepFade = 1f - smoothStep(
            (stepWindowRatio - AIM_STILL_HOLD_FULL_STEP_RATIO) /
                (AIM_STILL_HOLD_SOFT_EXIT_STEP_RATIO - AIM_STILL_HOLD_FULL_STEP_RATIO)
                    .coerceAtLeast(0.001f),
        )
        val effectiveBlend = aimStillHoldBlend * stepFade

        if (effectiveBlend <= 0.001f || requestedStepPx <= 0.001f) {
            return AimStillHoldState(
                targetX = targetX,
                targetY = targetY,
                active = false,
                blend = effectiveBlend,
                stepPx = requestedStepPx,
                rawStepPx = rawStepPx,
                maxStepPx = maxStepPx,
            )
        }

        val stepScale = lerp(1f, AIM_STILL_HOLD_STEP_SCALE, effectiveBlend)
        return AimStillHoldState(
            targetX = currentX + requestedStepX * stepScale,
            targetY = currentY + requestedStepY * stepScale,
            active = true,
            blend = effectiveBlend,
            stepPx = requestedStepPx * stepScale,
            rawStepPx = rawStepPx,
            maxStepPx = maxStepPx,
        )
    }

    private fun applyVisualStillGate(
        targetX: Float,
        targetY: Float,
        currentX: Float,
        currentY: Float,
        rps: Float,
        aimBlend: Float,
        releaseBlend: Float,
        reAimBlend: Float,
        softBoundaryActive: Boolean,
        motionTimestampNs: Long,
        dtSec: Float,
    ): VisualStillGateState {
        val requestedStepX = targetX - currentX
        val requestedStepY = targetY - currentY
        val requestedStepPx = calculateMagnitude(requestedStepX, requestedStepY)
        val feedback = latestVisualMotionFeedback
        val visualAgeMs = if (feedback != null && motionTimestampNs > 0L) {
            abs(motionTimestampNs - feedback.timestampNs) / 1_000_000f
        } else {
            -1f
        }
        val visualRawDx = feedback?.rawDx ?: 0f
        val visualRawDy = feedback?.rawDy ?: 0f
        val visualRawMag = calculateMagnitude(visualRawDx, visualRawDy)
        val visualValid = feedback?.valid == true
        val visualHitSearchBoundary = feedback?.hitSearchBoundary == true
        val visualScore = feedback?.score ?: 0f
        val feedbackUsable = VISUAL_STILL_GATE_ENABLED &&
            config.visualStillGateEnabled &&
            visualValid &&
            !visualHitSearchBoundary &&
            visualAgeMs >= 0f &&
            visualAgeMs <= VISUAL_STILL_GATE_MAX_FEEDBACK_AGE_MS
        val eligible = feedbackUsable &&
            aimBlend >= VISUAL_STILL_GATE_MIN_AIM_BLEND &&
            releaseBlend <= AIM_MICRO_JITTER_CLAMP_MAX_RELEASE_BLEND &&
            reAimBlend <= AIM_MICRO_JITTER_CLAMP_MAX_RE_AIM_BLEND &&
            rps <= VISUAL_STILL_GATE_MAX_RPS &&
            !softBoundaryActive

        if (!feedbackUsable || !eligible || visualRawMag >= VISUAL_STILL_GATE_MOVING_RAW_BYPASS_PX) {
            visualStillGateActive = false
            visualStillGateBlend = 0f
            return VisualStillGateState(
                targetX = targetX,
                targetY = targetY,
                active = false,
                blend = 0f,
                targetStepPx = requestedStepPx,
                visualValid = visualValid,
                visualHitSearchBoundary = visualHitSearchBoundary,
                visualAgeMs = visualAgeMs,
                visualRawDx = visualRawDx,
                visualRawDy = visualRawDy,
                visualRawMag = visualRawMag,
                visualScore = visualScore,
            )
        }

        val largeStep = if (visualStillGateActive) {
            requestedStepPx >= VISUAL_STILL_GATE_TARGET_STEP_EXIT_PX
        } else {
            requestedStepPx >= VISUAL_STILL_GATE_TARGET_STEP_ENTER_PX
        }
        visualStillGateActive = visualRawMag <= VISUAL_STILL_GATE_STILL_RAW_PX && largeStep
        val targetBlend = if (visualStillGateActive) 1f else 0f
        val blendRate = if (targetBlend > visualStillGateBlend) {
            VISUAL_STILL_GATE_BLEND_IN_PER_SECOND
        } else {
            VISUAL_STILL_GATE_BLEND_OUT_PER_SECOND
        }
        visualStillGateBlend = moveToward(
            current = visualStillGateBlend,
            target = targetBlend,
            step = blendRate * dtSec.coerceAtLeast(0f),
        )

        if (visualStillGateBlend <= 0.001f || requestedStepPx <= 0.001f) {
            return VisualStillGateState(
                targetX = targetX,
                targetY = targetY,
                active = false,
                blend = visualStillGateBlend,
                targetStepPx = requestedStepPx,
                visualValid = visualValid,
                visualHitSearchBoundary = visualHitSearchBoundary,
                visualAgeMs = visualAgeMs,
                visualRawDx = visualRawDx,
                visualRawDy = visualRawDy,
                visualRawMag = visualRawMag,
                visualScore = visualScore,
            )
        }

        val stepScale = lerp(1f, VISUAL_STILL_GATE_STEP_SCALE, visualStillGateBlend)
        return VisualStillGateState(
            targetX = currentX + requestedStepX * stepScale,
            targetY = currentY + requestedStepY * stepScale,
            active = true,
            blend = visualStillGateBlend,
            targetStepPx = requestedStepPx,
            visualValid = visualValid,
            visualHitSearchBoundary = visualHitSearchBoundary,
            visualAgeMs = visualAgeMs,
            visualRawDx = visualRawDx,
            visualRawDy = visualRawDy,
            visualRawMag = visualRawMag,
            visualScore = visualScore,
        )
    }

    private fun calculateMagnitude(x: Float, y: Float): Float {
        return sqrt(x * x + y * y)
    }

    private fun calculateReverseAlignment(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val mag1 = calculateMagnitude(x1, y1)
        val mag2 = calculateMagnitude(x2, y2)
        if (mag1 <= 0.001f || mag2 <= 0.001f) return 0f
        return (-(x1 * x2 + y1 * y2) / (mag1 * mag2)).coerceIn(0f, 1f)
    }

    private fun updateOutputAxis(
        current: Float,
        currentVelocity: Float,
        target: Float,
        dtSec: Float,
        maxVelocityPxPerSecond: Float,
        maxAccelerationPxPerSecond2: Float,
    ): OutputAxisState {
        val dt = dtSec.coerceAtLeast(0.001f)
        val delta = target - current
        if (abs(delta) <= 0.001f) {
            return OutputAxisState(
                value = target,
                velocity = 0f,
                velocityLimited = false,
                accelerationLimited = false,
            )
        }

        val requestedVelocity = delta / dt
        val velocityLimit = maxVelocityPxPerSecond.coerceAtLeast(0f)
        val targetVelocity = if (velocityLimit > 0f) {
            requestedVelocity.coerceIn(-velocityLimit, velocityLimit)
        } else {
            requestedVelocity
        }
        val velocityLimited = velocityLimit > 0f && abs(requestedVelocity) > velocityLimit

        val accelerationLimit = maxAccelerationPxPerSecond2.coerceAtLeast(0f)
        val velocityDelta = targetVelocity - currentVelocity
        val maxVelocityDelta = accelerationLimit * dt
        val resolvedVelocity = if (accelerationLimit > 0f && abs(velocityDelta) > maxVelocityDelta) {
            currentVelocity + if (velocityDelta > 0f) maxVelocityDelta else -maxVelocityDelta
        } else {
            targetVelocity
        }
        val accelerationLimited = accelerationLimit > 0f && abs(velocityDelta) > maxVelocityDelta

        val directionalVelocity = if (delta * resolvedVelocity < 0f) {
            0f
        } else {
            resolvedVelocity
        }
        val rawValue = current + directionalVelocity * dt
        val resolvedValue = if (delta > 0f) {
            min(rawValue, target)
        } else {
            max(rawValue, target)
        }
        val resolvedVelocityForNextFrame = if (resolvedValue == target && !accelerationLimited) {
            0f
        } else {
            directionalVelocity
        }
        return OutputAxisState(
            value = resolvedValue,
            velocity = resolvedVelocityForNextFrame,
            velocityLimited = velocityLimited,
            accelerationLimited = accelerationLimited,
        )
    }

    private fun resolveOutputStepDeltaSec(dtSec: Float): Float {
        return min(
            dtSec.coerceAtLeast(0.001f),
            OUTPUT_RATE_LIMIT_MAX_DT_MS / 1000f,
        )
    }

    private fun limitCorrectionRad(
        correctionRad: Float,
        maxTranslationPx: Float,
        focalPx: Float,
        strength: Float,
    ): Pair<Float, Boolean> {
        if (maxTranslationPx <= 0f || focalPx <= 0f || strength <= 0f) {
            return Pair(correctionRad, false)
        }

        val maxCorrectionRad = maxTranslationPx / (focalPx * strength)
        if (maxCorrectionRad <= 0f || abs(correctionRad) <= maxCorrectionRad) {
            return Pair(correctionRad, false)
        }

        return Pair(correctionRad.coerceIn(-maxCorrectionRad, maxCorrectionRad), true)
    }

    private fun updateReAimState(
        rps: Float,
        dtSec: Float,
        residualLevelRatio: Float,
    ): String {
        if (!config.reAimEnabled) {
            reAimStillDurationSec = 0f
            reAimActive = false
            lastReAimTrigger = ""
            reAimEdgeFrames = 0
            return ""
        }

        val rpsThreshold = reAimRpsThreshold()
        val enterRatio = config.reAimResidualEnterRatio.coerceIn(0.05f, 0.98f)
        val exitRatio = min(config.reAimResidualExitRatio.coerceIn(0f, 0.95f), enterRatio - 0.01f)
            .coerceAtLeast(0f)
        val boundaryActive = lastTransform.softBoundaryActiveX ||
            lastTransform.softBoundaryActiveY ||
            lastTransform.translationClampedX ||
            lastTransform.translationClampedY
        reAimEdgeFrames = if (boundaryActive) {
            min(reAimEdgeFrames + 1, RE_AIM_EDGE_CONFIRM_FRAMES)
        } else {
            0
        }
        val edgeActive = reAimEdgeFrames >= RE_AIM_EDGE_CONFIRM_FRAMES
        val rpsActive = rps >= rpsThreshold
        val residualActive = residualLevelRatio >= enterRatio
        if (rpsActive || residualActive || edgeActive) {
            reAimActive = true
            reAimStillDurationSec = 0f
            lastReAimTrigger = buildReAimTrigger(rpsActive, residualActive, edgeActive)
            return lastReAimTrigger
        }

        if (reAimActive) {
            val isStill = rps <= rpsThreshold && residualLevelRatio <= exitRatio
            if (isStill) {
                reAimStillDurationSec += dtSec.coerceAtLeast(0f)
            } else {
                reAimStillDurationSec = 0f
            }
            val requiredStillSec = config.reAimStillMs.coerceIn(0f, 2_000f) / 1000f
            if (reAimStillDurationSec >= requiredStillSec) {
                reAimActive = false
                lastReAimTrigger = ""
            }
        }

        return if (reAimActive) lastReAimTrigger else ""
    }

    private fun buildReAimTrigger(
        rpsActive: Boolean,
        residualActive: Boolean,
        edgeActive: Boolean,
    ): String {
        val triggers = mutableListOf<String>()
        if (rpsActive) triggers.add("rps")
        if (residualActive) triggers.add("residual")
        if (edgeActive) triggers.add("edge")
        return triggers.joinToString("+")
    }

    private fun updateReAimBlend(dtSec: Float): Float {
        val targetBlend = if (config.reAimEnabled && reAimActive) 1f else 0f
        val ratePerSecond = if (targetBlend > reAimBlend) {
            config.reAimBlendInPerSecond
        } else {
            config.reAimBlendOutPerSecond
        }.coerceAtLeast(0f)
        val step = ratePerSecond * dtSec.coerceAtLeast(0f)
        reAimBlend = when {
            targetBlend > reAimBlend -> min(targetBlend, reAimBlend + step)
            targetBlend < reAimBlend -> max(targetBlend, reAimBlend - step)
            else -> reAimBlend
        }.coerceIn(0f, 1f)
        return reAimBlend
    }

    private fun reAimRpsThreshold(): Float {
        return config.reAimRpsThreshold.coerceIn(0.001f, 0.300f)
    }

    private fun updateResidualReleaseState(
        rps: Float,
        dtSec: Float,
        residualLevelRatio: Float,
    ) {
        if (!config.residualReleaseEnabled) {
            residualStillDurationSec = 0f
            residualReleaseActive = false
            return
        }

        val stillThreshold = residualReleaseRpsThreshold()
        val isStill = rps <= stillThreshold
        if (isStill) {
            residualStillDurationSec += dtSec.coerceAtLeast(0f)
        } else {
            residualStillDurationSec = 0f
            residualReleaseActive = false
            return
        }

        val enterRatio = config.residualReleaseEnterRatio.coerceIn(0.05f, 1f)
        val exitRatio = min(config.residualReleaseExitRatio.coerceIn(0f, 1f), enterRatio)
        if (residualReleaseActive) {
            if (residualLevelRatio <= exitRatio) {
                residualReleaseActive = false
            }
            return
        }

        val requiredStillSec = config.residualReleaseStillMs.coerceAtLeast(0f) / 1000f
        if (residualStillDurationSec >= requiredStillSec && residualLevelRatio >= enterRatio) {
            residualReleaseActive = true
        }
    }

    private fun updateResidualReleaseBlend(dtSec: Float): Float {
        val targetBlend = if (config.residualReleaseEnabled && residualReleaseActive) 1f else 0f
        val ratePerSecond = if (targetBlend > residualReleaseBlend) {
            config.residualReleaseBlendInPerSecond
        } else {
            config.residualReleaseBlendOutPerSecond
        }.coerceAtLeast(0f)
        val step = ratePerSecond * dtSec.coerceAtLeast(0f)
        residualReleaseBlend = when {
            targetBlend > residualReleaseBlend -> min(targetBlend, residualReleaseBlend + step)
            targetBlend < residualReleaseBlend -> max(targetBlend, residualReleaseBlend - step)
            else -> residualReleaseBlend
        }.coerceIn(0f, 1f)
        return residualReleaseBlend
    }

    private fun residualReleaseRpsThreshold(): Float {
        return config.residualReleaseRpsThreshold.coerceIn(0.001f, 0.300f)
    }

    private fun calculateResidualLevelRatio(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        x3: Float,
        y3: Float,
        maxX: Float,
        maxY: Float,
    ): Float {
        val xRatio = maxAbsRatio(maxX, x1, x2, x3)
        val yRatio = maxAbsRatio(maxY, y1, y2, y3)
        return max(xRatio, yRatio).coerceIn(0f, 1f)
    }

    private fun maxAbsRatio(maxValue: Float, vararg values: Float): Float {
        if (maxValue <= 0f) return 0f

        var result = 0f
        for (value in values) {
            result = max(result, abs(value) / maxValue)
        }
        return result
    }

    private fun calculateRps(xRadPerSec: Float, yRadPerSec: Float, zRadPerSec: Float): Float {
        return (
            sqrt(
                xRadPerSec * xRadPerSec +
                    yRadPerSec * yRadPerSec +
                    zRadPerSec * zRadPerSec,
            ) / (2f * PI.toFloat())
            )
    }

    private fun calculateModelFocalPx(viewWidth: Float, viewHeight: Float, effectiveZoom: Float): Float {
        return min(viewWidth, viewHeight) * 0.72f * effectiveZoom
    }

    private fun calculateAimBlend(rps: Float): Float {
        if (!config.aimStabilizationEnabled) return 0f
        val threshold = config.aimRpsThreshold.coerceIn(0.001f, 1f)
        val release = (threshold * 3f).coerceAtLeast(threshold + 0.001f)
        if (rps <= threshold) return 1f
        if (rps >= release) return 0f

        val t = ((rps - threshold) / (release - threshold)).coerceIn(0f, 1f)
        return 1f - smoothStep(t)
    }

    private fun resolveEffectiveZoom(
        opticalZoomBase: Float,
        digitalZoomRatio: Float,
        displayZoomRatio: Float,
    ): Float {
        if (!config.zoomCompensationEnabled) return 1f
        val opticalBase = opticalZoomBase.coerceAtLeast(1f)
        val digitalRatio = digitalZoomRatio.coerceAtLeast(1f)
        val displayRatio = displayZoomRatio.coerceAtLeast(1f)
        val maxZoom = config.maxCompensationZoom.coerceAtLeast(1f)
        val hardwareZoom = (opticalBase * digitalRatio * displayRatio).coerceIn(1f, maxZoom)
        val zoomStrength = config.zoomCompensationStrength.coerceIn(0f, 2f)
        return (1f + (hardwareZoom - 1f) * zoomStrength).coerceIn(1f, maxZoom)
    }

    private fun resolveOutputRateLimit(aimBlend: Float, releaseBlend: Float = 0f): Float {
        val baseLimit = lerp(
            start = config.outputRateLimitPxPerSecond,
            end = config.aimOutputRateLimitPxPerSecond,
            t = aimBlend,
        ).coerceAtLeast(0f)
        val releaseLimit = config.residualReleaseOutputRateLimitPxPerSecond.coerceAtLeast(0f)
        if (releaseLimit <= 0f) return baseLimit
        return lerp(baseLimit, releaseLimit, releaseBlend)
    }

    private fun resolveOutputAccelerationLimit(aimBlend: Float, releaseBlend: Float = 0f): Float {
        val baseLimit = lerp(
            start = config.outputAccelerationLimitPxPerSecond2,
            end = config.aimOutputAccelerationLimitPxPerSecond2,
            t = aimBlend,
        ).coerceAtLeast(0f)
        if (baseLimit <= 0f) return 0f
        val releaseLimit = if (config.residualReleaseOutputAccelerationLimitPxPerSecond2 > 0f) {
            min(baseLimit, config.residualReleaseOutputAccelerationLimitPxPerSecond2)
        } else {
            baseLimit
        }
        return lerp(baseLimit, releaseLimit, releaseBlend)
    }

    private fun lerp(start: Float, end: Float, t: Float): Float {
        return start + (end - start) * t.coerceIn(0f, 1f)
    }

    private fun smoothStep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private fun moveToward(current: Float, target: Float, step: Float): Float {
        val safeStep = step.coerceAtLeast(0f)
        return when {
            target > current -> min(target, current + safeStep)
            target < current -> max(target, current - safeStep)
            else -> current
        }.coerceIn(0f, 1f)
    }

    companion object {
        const val OUTPUT_RATE_LIMIT_MAX_DT_MS = 33.4f

        const val AIM_MICRO_JITTER_CLAMP_ENABLED = false
        const val AIM_MICRO_JITTER_CLAMP_MIN_AIM_BLEND = 0.95f
        const val AIM_MICRO_JITTER_CLAMP_MAX_RELEASE_BLEND = 0.001f
        const val AIM_MICRO_JITTER_CLAMP_MAX_RE_AIM_BLEND = 0.001f
        const val AIM_MICRO_JITTER_CLAMP_STEP_CHANGE_PX = 14f
        private const val AIM_MICRO_JITTER_CLAMP_REVERSAL_MIN_STEP_PX = 4f
        private const val AIM_MICRO_JITTER_CLAMP_REVERSAL_BYPASS_ALIGNMENT = 0.35f

        const val AIM_STILL_HOLD_ENABLED = true
        const val AIM_STILL_HOLD_MIN_AIM_BLEND = 0.95f
        const val AIM_STILL_HOLD_ENTER_RPS = 0.005f
        const val AIM_STILL_HOLD_EXIT_RPS = 0.0065f
        const val AIM_STILL_HOLD_MAX_STEP_PX = 6f
        const val AIM_STILL_HOLD_MAX_RAW_STEP_PX = 6f
        const val AIM_STILL_HOLD_STEP_SCALE = 0.35f
        const val AIM_STILL_HOLD_FULL_STEP_RATIO = 0.35f
        const val AIM_STILL_HOLD_SOFT_EXIT_STEP_RATIO = 1.15f
        private const val AIM_STILL_HOLD_BLEND_IN_PER_SECOND = 10f
        private const val AIM_STILL_HOLD_BLEND_OUT_PER_SECOND = 14f

        const val VISUAL_STILL_GATE_ENABLED = true
        const val VISUAL_STILL_GATE_MIN_AIM_BLEND = 0.95f
        const val VISUAL_STILL_GATE_MAX_FEEDBACK_AGE_MS = 70f
        const val VISUAL_STILL_GATE_STILL_RAW_PX = 2f
        const val VISUAL_STILL_GATE_MOVING_RAW_BYPASS_PX = 5f
        const val VISUAL_STILL_GATE_TARGET_STEP_ENTER_PX = 4f
        const val VISUAL_STILL_GATE_TARGET_STEP_EXIT_PX = 2.5f
        const val VISUAL_STILL_GATE_MAX_RPS = 0.009f
        const val VISUAL_STILL_GATE_STEP_SCALE = 0.20f
        private const val VISUAL_STILL_GATE_BLEND_IN_PER_SECOND = 12f
        private const val VISUAL_STILL_GATE_BLEND_OUT_PER_SECOND = 16f

        private val RAD_TO_DEGREES = 180f / PI.toFloat()
        private const val GYRO_BUFFER_DURATION_NS = 1_500_000_000L
        private const val MAX_FRAME_SYNC_DELTA_SEC = 0.080f
        private const val MAX_FRAME_SYNC_GYRO_EXTRAPOLATION_NS = 50_000_000L
        private const val MAX_FRAME_SYNC_GYRO_GAP_NS = 50_000_000L
        private const val AIM_ADAPTIVE_LOW_PASS_MIN_DELTA_PX = 3f
        private const val AIM_ADAPTIVE_LOW_PASS_START_JITTER_PX = 10f
        private const val AIM_ADAPTIVE_LOW_PASS_FULL_JITTER_PX = 28f
        private const val AIM_ADAPTIVE_LOW_PASS_BLEND_IN_PER_SECOND = 10f
        private const val AIM_ADAPTIVE_LOW_PASS_BLEND_OUT_PER_SECOND = 4f
    }
}
