package tk.glucodata

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.drivers.ManagedSensorUiFamily

/**
 * Native tells Ottai, Anytime, MQ and iCan apart from Libre 2 only if the driver
 * that holds the sensor says so, and the code it says it with is on disk and
 * travels to other phones. These pin that contract on both sides.
 */
class ManagedSensorFamilyTests {
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "Common/src/main/cpp/g.cpp").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("repo root not found")
    }

    private fun source(relative: String): String = File(repoRoot(), relative).readText()

    @Test
    fun familyCodesAreStableAndFitTheThreeBitsNativeStores() {
        // On disk, and shipped to other phones: reordering the enum must not relabel a sensor.
        assertEquals(0, ManagedSensorUiFamily.GENERIC.nativeCode)
        assertEquals(1, ManagedSensorUiFamily.MQ.nativeCode)
        assertEquals(2, ManagedSensorUiFamily.AIDEX.nativeCode)
        assertEquals(3, ManagedSensorUiFamily.ICAN.nativeCode)
        assertEquals(4, ManagedSensorUiFamily.ANYTIME.nativeCode)
        assertEquals(5, ManagedSensorUiFamily.OTTAI.nativeCode)
        assertEquals(6, ManagedSensorUiFamily.SIBIONICS.nativeCode)
        assertEquals(7, ManagedSensorUiFamily.NIGHTSCOUT.nativeCode)
        assertTrue(ManagedSensorUiFamily.entries.all { it.nativeCode in 0..7 })
        assertEquals(ManagedSensorUiFamily.entries.size, ManagedSensorUiFamily.entries.map { it.nativeCode }.toSet().size)
        for (family in ManagedSensorUiFamily.entries) {
            assertEquals(family, ManagedSensorUiFamily.fromNativeCode(family.nativeCode))
        }
        assertEquals(ManagedSensorUiFamily.GENERIC, ManagedSensorUiFamily.fromNativeCode(99))
    }

    @Test
    fun theInfoBlockHoldsThreeBitsAndNativeRefusesAnythingWider() {
        val info = source("Common/src/main/cpp/SensorGlucoseData.hpp")
        assertTrue(info.contains("uint16_t managedFamily : 3;"))
        val jni = source("Common/src/main/cpp/g.cpp")
        assertTrue(jni.contains("fromjava(setSensorManagedFamily)"))
        assertTrue(jni.contains("code < 0 || code > 7"))
        assertTrue(jni.contains("fromjava(getSensorManagedFamily)"))
    }

    @Test
    fun everyManagedDriverStampsItsFamilyOnTheShellItWrites() {
        val drivers = mapOf(
            "Common/src/main/java/tk/glucodata/drivers/mq/MQBleManager.kt" to "MQ",
            "Common/src/main/java/tk/glucodata/drivers/anytime/AnytimeBleManager.kt" to "ANYTIME",
            "Common/src/main/java/tk/glucodata/drivers/ottai/OttaiBleManager.kt" to "OTTAI",
            "Common/src/main/java/tk/glucodata/drivers/icanhealth/ICanHealthBleManager.kt" to "ICAN",
            "Common/src/main/java/tk/glucodata/drivers/sibionics/SibionicsBleManager.kt" to "SIBIONICS",
        )
        for ((path, family) in drivers) {
            val text = source(path)
            assertTrue(
                "$path must stamp ManagedSensorUiFamily.$family on its native shell",
                text.contains("Natives.setSensorManagedFamily(") &&
                    text.contains("ManagedSensorUiFamily.$family.nativeCode"),
            )
        }
    }

    @Test
    fun theReceiverAsksTheFamilyBeforeItAsksTheLibreLadder() {
        val vm = source("Common/src/mobile/java/tk/glucodata/ui/viewmodel/SensorViewModel.kt")
            .replace(Regex("\\s+"), " ")
        val family = vm.indexOf("Natives.getSensorManagedFamily(gatt.dataptr)")
        val ladder = vm.indexOf("SensorVendor.fromNativeKind(nativeSensorKind)")
        assertTrue("the view model must read the family", family >= 0)
        assertTrue("and consult it before the elimination ladder", family < ladder)
        assertTrue(vm.contains("nativeFamily != ManagedSensorUiFamily.GENERIC -> SensorVendor.fromManagedFamily(nativeFamily)"))
    }
}
