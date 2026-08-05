package com.example.auto_measure.feature.camera.domain

import com.example.auto_measure.feature.camera.data.LensCandidate
import com.example.auto_measure.feature.camera.data.LensCategory

/**
 * 选择镜头：锁定最大长焦（eq35mm 最大的镜头）
 */
class SelectLensUseCase {

    operator fun invoke(candidates: List<LensCandidate>): LensCandidate? {
        if (candidates.isEmpty()) return null
        return candidates.maxByOrNull { it.eq35mm }
    }
}
