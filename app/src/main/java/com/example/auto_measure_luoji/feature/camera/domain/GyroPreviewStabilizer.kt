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
        val outputLowPassAlpha: Float = 0.75f,
        val outputRateLimitPxPerSecond: Float = 720f,
        val cropScale: Float = 1.16f,
        val maxShiftRatio: Float = 0.075f,
        val deadbandPx: Float = 0.0f,
        val horizontalGain: Float = 2.7f,
        val verticalGain: Float = 0.35f,
        val aimStabilizationEnabled: Boolean = true,
        val aimRpsThreshold: Float = 0.04f,
        val aimStrengthMultiplier: Float = 2.2f,
        val aimPathFollowPerSecond: Float = 0.8f,
        val aimDeadbandPx: Float = 0.0f,
        val aimOutputRateLimitPxPerSecond: Float = 260f,
        val residualReleaseEnabled: Boolean = true,
        val residualReleaseEnterRatio: Float = 0.75f,
        val residualReleaseExitRatio: Float = 0.25f,
        val residualReleaseStillMs: Float = 180f,
        val residualReleasePathFollowPerSecond: Float = 8.0f,
        val residualReleaseOutputRateLimitPxPerSecond: Float = 900f,
        val residualAntiWindupEnabled: Boolean = true,
        val zoomCompensationEnabled: Boolean = true,
        val zoomCompensationStrength: Float = 1.5f,
        val maxCompensationZoom: Float = 20.0f,
        val frameSyncEnabled: Boolean = true,
        val frameTimestampOffsetMs: Float = 0f,
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
        val residualReleaseActive: Boolean = false,
        val residualLevelRatio: Float = 0f,
        val residualStillMs: Float = 0f,
        val residualAntiWindupX: Boolean = false,
        val residualAntiWindupY: Boolean = false,
        val rawTranslationX: Float = 0f,
        val rawTranslationY: Float = 0f,
        val targetTranslationX: Float = 0f,
        val targetTranslationY: Float = 0f,
        val maxTranslationX: Float = 0f,
        val maxTranslationY: Float = 0f,
        val maxOutputStepPx: Float = 0f,
        val outputLimitedX: Boolean = false,
        val outputLimitedY: Boolean = false,
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
    private var outputRotationDegrees = 0f
    private var residualStillDurationSec = 0f
    private var residualReleaseActive = false
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
        outputRotationDegrees = 0f
        residualStillDurationSec = 0f
        residualReleaseActive = false
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
        val aimBlend = calculateAimBlend(rps)
        val focalPx = calculateModelFocalPx(viewWidth, viewHeight, effectiveZoom)
        val scale = config.cropScale.coerceAtLeast(1f)
        val finalRenderScale = renderScale.coerceAtLeast(scale)
        val cropMarginX = viewWidth * (finalRenderScale - 1f) * 0.46f
        val cropMarginY = viewHeight * (finalRenderScale - 1f) * 0.46f
        val maxDx = min(viewWidth * config.maxShiftRatio, cropMarginX)
        val maxDy = min(viewHeight * config.maxShiftRatio, cropMarginY)
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
        updateResidualReleaseState(
            rps = rps,
            dtSec = dtSec,
            residualLevelRatio = previousResidualLevelRatio,
        )

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
        val activePathFollow = if (residualReleaseActive) {
            max(basePathFollow, config.residualReleasePathFollowPerSecond.coerceAtLeast(0.1f))
        } else {
            basePathFollow
        }
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
        val rawTx = applyDeadband(
            value = unclampedRawTx.coerceIn(-maxDx, maxDx),
            deadband = deadband,
        )
        val rawTy = applyDeadband(
            value = unclampedRawTy.coerceIn(-maxDy, maxDy),
            deadband = deadband,
        )
        val outputAlpha = config.outputLowPassAlpha.coerceIn(0.05f, 1f)
        val outputRateLimitPxPerSecond = resolveOutputRateLimit(aimBlend, residualReleaseActive)
        val maxOutputStepPx = outputRateLimitPxPerSecond * dtSec
        val previousOutputX = outputTranslationX
        val previousOutputY = outputTranslationY
        val targetTranslationX = outputTranslationX + (rawTx - outputTranslationX) * outputAlpha
        val targetTranslationY = outputTranslationY + (rawTy - outputTranslationY) * outputAlpha
        val outputLimitedX = maxOutputStepPx > 0f && abs(targetTranslationX - previousOutputX) > maxOutputStepPx
        val outputLimitedY = maxOutputStepPx > 0f && abs(targetTranslationY - previousOutputY) > maxOutputStepPx
        outputTranslationX = limitStep(outputTranslationX, targetTranslationX, maxOutputStepPx)
        outputTranslationY = limitStep(outputTranslationY, targetTranslationY, maxOutputStepPx)
        val finalTranslationX = outputTranslationX.coerceIn(-maxDx, maxDx)
        val finalTranslationY = outputTranslationY.coerceIn(-maxDy, maxDy)
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
            residualReleaseActive = residualReleaseActive,
            residualLevelRatio = residualLevelRatio,
            residualStillMs = residualStillDurationSec * 1000f,
            residualAntiWindupX = residualAntiWindupX,
            residualAntiWindupY = residualAntiWindupY,
            rawTranslationX = rawTx,
            rawTranslationY = rawTy,
            targetTranslationX = targetTranslationX,
            targetTranslationY = targetTranslationY,
            maxTranslationX = maxDx,
            maxTranslationY = maxDy,
            maxOutputStepPx = maxOutputStepPx,
            outputLimitedX = outputLimitedX,
            outputLimitedY = outputLimitedY,
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

    private fun limitStep(current: Float, target: Float, maxStep: Float): Float {
        val limit = maxStep.coerceAtLeast(0f)
        val delta = target - current
        if (limit <= 0f || abs(delta) <= limit) return target
        return current + if (delta > 0f) limit else -limit
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

        val stillThreshold = (config.aimRpsThreshold.coerceIn(0.001f, 1f) * 1.25f)
            .coerceAtLeast(0.002f)
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

    private fun resolveOutputRateLimit(aimBlend: Float, releaseActive: Boolean = false): Float {
        val baseLimit = lerp(
            start = config.outputRateLimitPxPerSecond,
            end = config.aimOutputRateLimitPxPerSecond,
            t = aimBlend,
        ).coerceAtLeast(0f)
        return if (releaseActive) {
            max(baseLimit, config.residualReleaseOutputRateLimitPxPerSecond.coerceAtLeast(0f))
        } else {
            baseLimit
        }
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
