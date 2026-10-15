package com.enjoyyourtime.globalfitness.scale

import com.enjoyyourtime.globalfitness.scale.ScaleTestSupport.hex
import com.enjoyyourtime.globalfitness.scale.libs.AfuB1Lib
import com.enjoyyourtime.globalfitness.scale.libs.EtekcityLib
import com.enjoyyourtime.globalfitness.scale.libs.FormulaLibs
import com.enjoyyourtime.globalfitness.scale.libs.KeepS3BodyComposition
import com.enjoyyourtime.globalfitness.scale.libs.OmronBodyCompositionLib
import com.enjoyyourtime.globalfitness.scale.libs.OmronWlpFrame
import com.enjoyyourtime.globalfitness.scale.libs.OneByoneLib
import com.enjoyyourtime.globalfitness.scale.libs.OneByoneNewLib
import com.enjoyyourtime.globalfitness.scale.libs.SoehnleLib
import com.enjoyyourtime.globalfitness.scale.libs.StandardImpedanceLib
import com.enjoyyourtime.globalfitness.scale.libs.TrisaBodyAnalyzeLib
import com.enjoyyourtime.globalfitness.scale.libs.YunmaiLib
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Reference values from openScale's library tests (same inputs, same outputs). */
class FormulaLibVectorsTest {
    private val eps = 1e-3

    @Test
    fun standardImpedanceLib() {
        val l = StandardImpedanceLib(true, 30, 80.0, 1.8, 527.0)
        assertEquals(60.622, l.fatFreeMassKg, eps)
        assertEquals(24.222, l.totalFatPercentage, eps)
        assertEquals(53.819, l.totalBodyWaterPercentage, eps)
        assertEquals(1679.436, l.basalMetabolicRate, eps)
        assertEquals(39.313, l.skeletalMusclePercentage, eps)
        assertEquals(3.455, l.boneMassKg, eps)
    }

    @Test
    fun etekcityLib() {
        val l = EtekcityLib(true, 30, 80.0, 1.8, 527.0)
        assertEquals(24.69136, l.bmi, eps)
        assertEquals(17.7, l.bodyFatPercentage, eps)
        assertEquals(65.84, l.fatFreeWeight, eps)
        assertEquals(7.64163, l.visceralFat, eps)
        assertEquals(59.4206, l.water, eps)
        assertEquals(1792.144, l.basalMetabolicRate, eps)
        assertEquals(53.1658, l.skeletalMusclePercentage, eps)
        assertEquals(3.292, l.boneMass, eps)
        assertEquals(18.7644, l.proteinPercentage, eps)
        assertEquals(29, l.metabolicAge)
    }

    @Test
    fun yunmaiLib() {
        val male = YunmaiLib(1, 180f, ActivityLevel.MODERATE)
        assertEquals(55.907001f, male.getWater(23f), 1e-3f)
        assertEquals(23.237043f, male.getFat(30, 80f, 500), 1e-3f)
        assertEquals(51.595001f, male.getMuscle(23f), 1e-3f)
        assertEquals(40.814999f, male.getSkeletalMuscle(23f), 1e-3f)
        assertEquals(3.263390f, male.getBoneMass(51.595001f, 80f), 1e-3f)
        assertEquals(61.599998f, male.getLeanBodyMass(80f, 23f), 1e-3f)
        assertEquals(11.318182f, male.getVisceralFat(23f, 30), 1e-3f)
        val heavy = YunmaiLib(1, 190f, ActivityLevel.HEAVY)
        assertEquals(15.266259f, heavy.getFat(20, 72f, 480), 1e-3f)
        assertEquals(60.205002f, heavy.getMuscle(14f), 1e-3f)
        assertEquals(1, YunmaiLib.toYunmaiActivityLevel(ActivityLevel.EXTREME))
        assertEquals(0, YunmaiLib.toYunmaiActivityLevel(ActivityLevel.MODERATE))
    }

    @Test
    fun trisaLib() {
        val l = TrisaBodyAnalyzeLib(1, 30, 180f)
        assertEquals(57.031845f, l.getWater(80f, 500f), 1e-3f)
        assertEquals(23.186619f, l.getFat(80f, 500f), 1e-3f)
        assertEquals(40.767307f, l.getMuscle(80f, 500f), 1e-3f)
        assertEquals(4.254889f, l.getBone(80f, 500f), 1e-3f)
    }

    @Test
    fun soehnleLib() {
        val male = SoehnleLib(true, 30, 180f, 3)
        assertEquals(18.604935f, male.getFat(80f, 500f), 1e-3f)
        assertEquals(54.8644f, male.getWater(80f, 500f), 1e-3f)
        assertEquals(9.49897f, male.getMuscle(80f, 500f, 200f), 1e-3f)
        val female = SoehnleLib(false, 45, 160f, 1)
        assertEquals(48.433907f, female.getFat(70f, 700f), 1e-3f)
        assertEquals(38.981922f, female.getWater(70f, 700f), 1e-3f)
        assertEquals(2.8784876f, female.getMuscle(70f, 700f, 250f), 1e-3f)
    }

    @Test
    fun oneByoneLib() {
        val m = OneByoneLib(1, 30, 180f, 0)
        val bf = m.getBodyFat(80f, 500f)
        assertEquals(23.315102f, bf, 1e-3f)
        assertEquals(61.34792f, m.getLBM(80f, bf), 1e-3f)
        assertEquals(40.97725f, m.getMuscle(80f, 500f), 1e-3f)
        assertEquals(52.60584f, m.getWater(bf), 1e-3f)
        assertEquals(3.030576f, m.getBoneMass(80f, 500f), 1e-3f)
        assertEquals(10.79977f, m.getVisceralFat(80f), 1e-3f)
        val f = OneByoneLib(0, 28, 165f, 1)
        val fbf = f.getBodyFat(60f, 520f)
        assertEquals(25.210106f, fbf, 1e-3f)
        assertEquals(51.305866f, f.getWater(fbf), 1e-3f)
        assertEquals(2.3883991f, f.getBoneMass(60f, 520f), 1e-3f)
        assertEquals(0.70499706f, f.getVisceralFat(60f), 1e-3f)
    }

    @Test
    fun oneByoneNewLib() {
        val m = OneByoneNewLib(1, 30, 180f, 0)
        assertEquals(62.14792f, m.getLBM(80f, 500), 1e-3f)
        assertEquals(23.315102f, m.getBodyFatPercentage(80f, 500), 1e-3f)
        assertEquals(3.1254208f, m.getBoneMass(80f, 500), 1e-3f)
        assertEquals(40.566765f, m.getSkeletonMusclePercentage(80f, 500), 1e-3f)
        assertEquals(10.79977f, m.getVisceralFat(80f), 1e-3f)
        assertEquals(52.60584f, m.getWaterPercentage(80f, 500), 1e-3f)
        val f = OneByoneNewLib(0, 28, 165f, 1)
        assertEquals(28.27285f, f.getBodyFatPercentage(60f, 520), 1e-3f)
        assertEquals(2.486581f, f.getBoneMass(60f, 520), 1e-3f)
        assertEquals(36.45647f, f.getSkeletonMusclePercentage(60f, 520), 1e-3f)
        assertEquals(4.704997f, f.getVisceralFat(60f), 1e-3f)
        assertEquals(49.204823f, f.getWaterPercentage(60f, 520), 1e-3f)
    }

    @Test
    fun keepS3DualFrequencyComposition() {
        val r = KeepS3BodyComposition.calculate(true, 26, 168, 85.1f, 506, 478)!!
        assertEquals(29.5f, r.bodyFatPercent)
        assertEquals(60.0f, r.fatFreeMassKg)
        assertEquals(50.2f, r.waterPercent)
        assertEquals(3.0f, r.boneKg)
        assertEquals(66.9f, r.musclePercent)
        assertEquals(38.42538f, r.skeletalMusclePercent, 1e-3f)
        assertEquals(12.7f, r.proteinPercent)
        assertEquals(12, r.visceralFatLevel)
        assertEquals(1791, r.basalMetabolicRateKcal)
        assertEquals(28, r.bodyAge)
        val athlete = KeepS3BodyComposition.calculate(false, 42, 168, 85.1f, 506, 478, athlete = true)!!
        assertEquals(38.4f, athlete.bodyFatPercent)
        assertEquals(44.1f, athlete.waterPercent)
        assertEquals(3.1f, athlete.boneKg)
        assertEquals(10.3f, athlete.proteinPercent)
        assertEquals(7, athlete.visceralFatLevel)
        assertEquals(1481, athlete.basalMetabolicRateKcal)
        assertEquals(44, athlete.bodyAge)
        val older = KeepS3BodyComposition.calculate(false, 55, 160, 50f, 506, 478)!!
        assertEquals(22.8f, older.bodyFatPercent)
        assertEquals(4, older.visceralFatLevel)
        assertEquals(970, older.basalMetabolicRateKcal)
        assertNull(KeepS3BodyComposition.calculate(true, 26, 168, 85.1f, 199, 478))
    }

    @Test
    fun afuB1FramesAndComposition() {
        assertArrayEquals(hex("af4516ba90806a20000000000000000000005f09"), AfuB1Lib.buildTimeSync(1786810554L))
        assertArrayEquals(hex("af45040001afb6171d010002b4c61b560100620f"), AfuB1Lib.buildSyncRequest(AfuB1Lib.SyncRequestVariant.FIRST))
        assertArrayEquals(hex("af45040103a5f8111d020004b4c61b1d0100620e"), AfuB1Lib.buildSyncRequest(AfuB1Lib.SyncRequestVariant.SECOND))
        assertArrayEquals(
            hex("af453fd490806a0001afb6171d01881303006107"),
            AfuB1Lib.buildUserProfile(AfuB1Lib.ProfileVariant.INITIAL, 1786810580L, 1, 29, 1, 50f),
        )
        assertArrayEquals(hex("af458e4090806a0001afde171d0188130300610a"), AfuB1Lib.buildHistoryDumpRequest(1786810432L, 1, 29, 1, 50f))
        assertArrayEquals(hex("af45140000000000000000000000000000005f13"), AfuB1Lib.buildChunkRequest())
        assertArrayEquals(hex("af45125400050000000000000000000000005f0a"), AfuB1Lib.buildAck(AfuB1Lib.WIRE_STORED_RECORD, 5))

        val live = AfuB1Lib.parse(hex("af45b2ed080002800188384b0000000000005106")) as AfuB1Lib.ScaleFrame.LiveWeight
        assertEquals(60850, live.weightGrams)
        assertEquals(false, live.locked)
        val record = AfuB1Lib.parse(hex("af450046a4826a7aee0880000127020000005404")) as AfuB1Lib.ScaleFrame.StoredRecord
        assertEquals(1786946630L, record.timestampEpochSeconds)
        assertEquals(61050, record.weightGrams)
        assertEquals(551, record.resistanceOhms)
        assertTrue(record.locked)
        val impedance = AfuB1Lib.parse(hex("af4501002702000000000000000000000000521c")) as AfuB1Lib.ScaleFrame.ImpedanceResult
        assertEquals(551, impedance.adcOhms)
        assertNull(AfuB1Lib.parse(hex("af4516ba90806a20000000000000000000005f0a")))

        assertEquals(16.7f, AfuB1Lib.bodyFatPercent(61.0, 175.0, 29, 1, 550.0))
        assertNull(AfuB1Lib.bodyFatPercent(61.0, 175.0, 29, 1, 0.0))
        val c = AfuB1Lib.bodyComposition(1, 29, 175f, 61f, 16.7f)!!
        assertEquals(56.15439616656694, c.waterPercent, 1e-9)
        assertEquals(50.25818621525999, c.skeletalMusclePercent, 1e-9)
        assertEquals(2.6422760486602783, c.boneMassKg.toDouble(), 1e-9)
        assertEquals(6, c.visceralFatIndex)
        assertEquals(22.814002990722656, c.proteinPercent, 1e-9)
        assertEquals(50.8129997253418, c.fatFreeMassKg.toDouble(), 1e-9)
        assertNull(AfuB1Lib.bodyComposition(1, 29, 175f, 61f, 3.9f))
    }

    @Test
    fun omronRecordsAndFrames() {
        val record702t = hex(
            "00 00 3a d0 5f 02 4e 18 3b 6d 22 33 89 cc 00 00" +
                "00 00 00 00 00 00 00 00 00 00 5a 80 00 00 00 00" +
                "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00",
        )
        val zone = ZoneId.of("Europe/Madrid")
        val r = OmronBodyCompositionLib.decodeRecord(record702t, OmronBodyCompositionLib.PROFILE_HBF_702T, zone)!!
        assertEquals(72.40f, r.weightKg, 1e-4f)
        assertEquals(23.5f, r.bodyFatPercent!!, 1e-4f)
        assertEquals(31.2f, r.skeletalMusclePercent!!, 1e-4f)
        assertEquals(1520, r.bmrKcal)
        assertEquals(8.0f, r.visceralFatLevel!!, 1e-4f)
        assertEquals(34, r.bodyAgeYears)
        assertEquals(LocalDateTime.of(2024, 3, 17, 7, 45, 12), LocalDateTime.ofInstant(Instant.ofEpochMilli(r.timestampMillis), zone))
        val r32 = OmronBodyCompositionLib.decodeRecord(record702t.copyOf(32).also { it[3] = 0xC8.toByte() }, OmronBodyCompositionLib.PROFILE_HBF_32_NO_BODY_AGE, zone)!!
        assertEquals(8.0f, r32.visceralFatLevel!!, 1e-4f)
        assertNull(r32.bodyAgeYears)
        assertNull(OmronBodyCompositionLib.decodeRecord(ByteArray(48) { 0xFF.toByte() }, OmronBodyCompositionLib.PROFILE_HBF_702T))
        assertNull(OmronBodyCompositionLib.decodeRecord(record702t.copyOf().also { it[11] = 0x3F }, OmronBodyCompositionLib.PROFILE_HBF_702T))

        assertArrayEquals(hex("0800000000100018"), OmronWlpFrame.startTransmission())
        assertArrayEquals(hex("080f000000000007"), OmronWlpFrame.endTransmission())
        assertArrayEquals(hex("08010002c02000eb"), OmronWlpFrame.readEeprom(0x02C0, 0x20))
    }

    @Test
    fun everyFormulaLibraryGivesPlausibleCompositionForATypicalAdult() {
        for (lib in FormulaLib.entries) {
            val c = FormulaLibs.compute(lib, isMale = true, age = 35, heightCm = 180.0, weightKg = 80.0, impedance = 500)
            when (lib) {
                FormulaLib.MI_SCALE, FormulaLib.WLA25 -> assertNull("$lib", c)
                else -> {
                    assertNotNull("$lib", c)
                    val fat = c!!.fatPercent
                    if (fat != null) assertTrue("$lib fat $fat", fat in 3.0..60.0)
                    c.waterPercent?.let { assertTrue("$lib water $it", it in 20.0..80.0) }
                    c.boneKg?.let { assertTrue("$lib bone $it", it in 0.5..10.0) }
                }
            }
        }
    }
}
