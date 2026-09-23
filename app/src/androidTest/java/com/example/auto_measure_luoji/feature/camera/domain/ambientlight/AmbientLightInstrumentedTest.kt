package com.example.auto_measure_luoji.feature.camera.domain.ambientlight

import android.Manifest
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.auto_measure_luoji.MainActivity
import com.example.auto_measure_luoji.feature.camera.ui.statuspanel.expandStatusPanel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class AmbientLightInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun absentServiceAndRepeatedLifecycleCallsAreSafe() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val readings = mutableListOf<AmbientLightReading>()
        instrumentation.runOnMainSync {
            val monitor = AmbientLightMonitor(null) { readings.add(it) }
            monitor.start()
            monitor.start()
            assertEquals(AmbientLightReading.Status.UNSUPPORTED, readings.last().status)
            assertEquals(2, readings.size)
            monitor.stop()
            monitor.stop()
            assertEquals(3, readings.size)
            monitor.start()
            assertEquals(AmbientLightReading.Status.UNSUPPORTED, readings.last().status)
            monitor.stop()
        }
    }

    @Test fun actualSensorPublishesLuxAndStopsCallbacks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val manager = instrumentation.targetContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val hasSensor = manager?.getDefaultSensor(Sensor.TYPE_LIGHT) != null
        val readings = CopyOnWriteArrayList<AmbientLightReading>()
        val received = CountDownLatch(1)
        lateinit var monitor: AmbientLightMonitor
        instrumentation.runOnMainSync {
            monitor = AmbientLightMonitor(manager) {
                readings.add(it)
                if (it.status != AmbientLightReading.Status.WAITING) received.countDown()
            }
            monitor.start()
        }
        try {
            assertTrue("未收到光线传感器读数", received.await(15, TimeUnit.SECONDS))
            if (hasSensor) {
                assertEquals(AmbientLightReading.Status.AVAILABLE, readings.last().status)
                assertTrue(readings.last().lux!! >= 0f)
            } else assertEquals(AmbientLightReading.Status.UNSUPPORTED, readings.last().status)
        } finally {
            instrumentation.runOnMainSync { monitor.stop() }
        }
        val countAfterStop = readings.size
        Thread.sleep(750)
        assertEquals(countAfterStop, readings.size)
        assertEquals(AmbientLightReading.Status.PAUSED, readings.last().status)
    }

    @Test fun panelShowsAmbientLightAfterResume() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 31) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.BLUETOOTH_SCAN)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.BLUETOOTH_CONNECT)
        }
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val hasSensor = manager?.getDefaultSensor(Sensor.TYPE_LIGHT) != null
        val expected = if (hasSensor) hasText(" lx", substring = true) else hasText("环境照度：无光线传感器")
        fun verifyPanel() {
            compose.expandStatusPanel()
            compose.waitUntil(20_000) {
                compose.onAllNodes(hasContentDescription("环境照度") and expected).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("环境照度").assertIsDisplayed()
            compose.onNodeWithContentDescription("低光增强亮度范围").assertDoesNotExist()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            verifyPanel()
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            verifyPanel()
        }
    }
}
