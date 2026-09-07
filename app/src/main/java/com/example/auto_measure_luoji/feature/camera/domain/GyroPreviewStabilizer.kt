package com.example.auto_measure_luoji.feature.camera.domain

import java.util.ArrayDeque
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

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
        val reAimResidualEnterRatio: Float = 0.65f,
        val reAimResidualExitRatio: Float = 0.25f,
        val reAimStillMs: Float = 250f,
        val reAimPathFollowPerSecond: Float = 12.0f,
        val reAimBlendInPerSecond: Float = 8.0f,
        val reAimBlendOutPerSecond: Float = 3.0f,
        val zoomCompensationEnabled: Boolean = true,
        val zoomCompensationStrength: Float = 1.0f,
        val maxCompensationZoom: Float = 20.0f,
        val frameSyncEnabled: Boolean = true,
        val frameTimestampOffsetMs: Float = 5f,
        val rollCompensationEnabled: Boolean = false,
        val rollStrength: Float = 0.35f,
        val maxRollDegrees: Float = 1.5f,
        val invertRoll: Boolean = false,
        val maxDeltaTimeSec: Float = 0.025f,
        val swapPitchYawAxes: Boolean = false,
        val invertHorizontal: Boolean = false,
        val invertVertical: Boolean = true,
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
        val rawTranslationX: Float = 0f,
        val rawTranslationY: Float = 0f,
        val targetTranslationX: Float = 0f,
        val targetTranslationY: Float = 0f,
        val maxTranslationX: Float = 0f,
        val maxTranslationY: Float = 0f,
        val maxOutputStepPx: Float = 0f,
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
        initialized = false
        lastTransform = Transform.Identity
        gyroSamples.clear()
        latestGyroSample = null
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

        val rps = calculateRps(gyroXRadPerSec, gyroYRadPerSec, gyroZRadPerSec)
        val sourcePitchRate = if (config.swapPitchYawAxes) gyroYRadPerSec else gyroXRadPerSec
        val sourceYawRate = if (config.swapPitchYawAxes) gyroXRadPerSec else gyroYRadPerSec
        val sourceRollRate = gyroZRadPerSec
        val effectiveZoom = resolveEffectiveZoom(opticalZoomBase, digitalZoomRatio, displayZoomRatio)
        val modelFocalPx = calculateModelFocalPx(viewWidth, viewHeight, effectiveZoom)
        if (!initialized || lastTimestampNs <= 0L || timestampNs <= lastTimestampNs) {
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
            )
            return lastTransform
        }

        val dtSec = ((timestampNs - lastTimestampNs) / 1_000_000_000f)
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

        if (!initialized || lastTimestampNs <= 0L || targetTimestampNs <= lastTimestampNs) {
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
            )
            return lastTransform
        }

        val rawDtSec = (targetTimestampNs - lastTimestampNs) / 1_000_000_000f
        if (rawDtSec > MAX_FRAME_SYNC_DELTA_SEC) {
            lastTimestampNs = targetTimestampNs
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
            )
            return lastTransform
        }

        val startTimestampNs = lastTimestampNs
        val integrated = integrateGyroWindow(startTimestampNs, targetTimestampNs)
        lastTimestampNs = targetTimestampNs
        val dtSec = rawDtSec.coerceIn(0.001f, MAX_FRAME_SYNC_DELTA_SEC)
        if (integrated.samples <= 0) {
            lastTransform = lastTransform.copy(
                scale = config.cropScale,
                frameSyncEnabled = true,
                frameSamples = 0,
                frameDeltaMs = dtSec * 1000f,
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
            )
            return lastTransform
        }

        val gyroXRadPerSec = integrated.xRad / dtSec
        val gyroYRadPerSec = integrated.yRad / dtSec
        val gyroZRadPerSec = integrated.zRad / dtSec
        val sourcePitchDelta = if (config.swapPitchYawAxes) integrated.yRad else integrated.xRad
        val sourceYawDelta = if (config.swapPitchYawAxes) integrated.xRad else integrated.yRad
        val sourceRollDelta = integrated.zRad
        val sourcePitchRate = sourcePitchDelta / dtSec
        val sourceYawRate = sourceYawDelta / dtSec
        val sourceRollRate = sourceRollDelta / dtSec
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
        val outputAlpha = config.outputLowPassAlpha.coerceIn(0.05f, 1f)
        val outputRateLimitPxPerSecond = resolveOutputRateLimit(aimBlend, releaseBlend)
        val outputAccelerationLimitPxPerSecond2 = resolveOutputAccelerationLimit(aimBlend, releaseBlend)
        val maxOutputStepPx = outputRateLimitPxPerSecond * dtSec
        val targetTranslationX = outputTranslationX + (rawTx - outputTranslationX) * outputAlpha
        val targetTranslationY = outputTranslationY + (rawTy - outputTranslationY) * outputAlpha
        val outputX = updateOutputAxis(
            current = outputTranslationX,
            currentVelocity = outputVelocityX,
            target = targetTranslationX,
            dtSec = dtSec,
            maxVelocityPxPerSecond = outputRateLimitPxPerSecond,
            maxAccelerationPxPerSecond2 = outputAccelerationLimitPxPerSecond2,
        )
        val outputY = updateOutputAxis(
            current = outputTranslationY,
            currentVelocity = outputVelocityY,
            target = targetTranslationY,
            dtSec = dtSec,
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

        lastTransform = Transform(
            scale = scale,
            translationX = finalTranslationX,
            translationY = finalTranslationY,
            rotationDegrees = outputRotationDegrees.coerceIn(-maxRollDegrees, maxRollDegrees),
            rps = rps,
            aimBlend = aimBlend,
            baseAimBlend = baseAimBlend,
            reAimActive = activeReAimBlend > 0.001f,
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
            outputRateLimitPxPerSecond = outputRateLimitPxPerSecond,
            outputAccelerationLimitPxPerSecond2 = outputAccelerationLimitPxPerSecond2,
            outputVelocityX = outputVelocityX,
            outputVelocityY = outputVelocityY,
            residualReleaseActive = releaseBlend > 0.001f,
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
            rawTranslationX = rawTx,
            rawTranslationY = rawTy,
            targetTranslationX = targetTranslationX,
            targetTranslationY = targetTranslationY,
            maxTranslationX = maxDx,
            maxTranslationY = maxDy,
            maxOutputStepPx = maxOutputStepPx,
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
            frameDeltaMs = dtSec * 1000f,
            frameLagMs = frameLagMs,
        )
        return lastTransform
    }

    private fun integrateGyroWindow(startTimestampNs: Long, endTimestampNs: Long): IntegratedGyro {
        if (endTimestampNs <= startTimestampNs || gyroSamples.isEmpty()) {
            return IntegratedGyro(0f, 0f, 0f, 0)
        }

        var previous: GyroSample? = null
        var cursorNs = startTimestampNs
        var samples = 0
        var xRad = 0f
        var yRad = 0f
        var zRad = 0f

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
                previous = sample
                cursorNs = sample.timestampNs.coerceAtLeast(startTimestampNs)
                samples++
                continue
            }

            val dtSec = ((sample.timestampNs - cursorNs).coerceAtLeast(0L)) / 1_000_000_000f
            xRad += prev.xRadPerSec * dtSec
            yRad += prev.yRadPerSec * dtSec
            zRad += prev.zRadPerSec * dtSec
            cursorNs = sample.timestampNs
            previous = sample
            samples++
        }

        previous?.let { prev ->
            if (cursorNs < endTimestampNs) {
                val dtSec = (endTimestampNs - cursorNs) / 1_000_000_000f
                xRad += prev.xRadPerSec * dtSec
                yRad += prev.yRadPerSec * dtSec
                zRad += prev.zRadPerSec * dtSec
            }
        }

        return IntegratedGyro(xRad, yRad, zRad, samples)
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
            return ""
        }

        val rpsThreshold = reAimRpsThreshold()
        val enterRatio = config.reAimResidualEnterRatio.coerceIn(0.05f, 0.98f)
        val exitRatio = min(config.reAimResidualExitRatio.coerceIn(0f, 0.95f), enterRatio - 0.01f)
            .coerceAtLeast(0f)
        val edgeActive = lastTransform.softBoundaryActiveX ||
            lastTransform.softBoundaryActiveY ||
            lastTransform.translationClampedX ||
            lastTransform.translationClampedY ||
            lastTransform.outputLimitedX ||
            lastTransform.outputLimitedY
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
        val smoothT = t * t * (3f - 2f * t)
        return 1f - smoothT
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

    private companion object {
        private val RAD_TO_DEGREES = 180f / PI.toFloat()
        private const val GYRO_BUFFER_DURATION_NS = 1_500_000_000L
        private const val MAX_FRAME_SYNC_DELTA_SEC = 0.080f
    }
}
