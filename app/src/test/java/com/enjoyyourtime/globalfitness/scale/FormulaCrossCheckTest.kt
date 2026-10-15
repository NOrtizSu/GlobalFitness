package com.enjoyyourtime.globalfitness.scale

import com.enjoyyourtime.globalfitness.scale.ScaleTestSupport.hex
import com.enjoyyourtime.globalfitness.scale.drivers.AiLinkLib
import com.enjoyyourtime.globalfitness.scale.libs.OneByoneLib
import com.enjoyyourtime.globalfitness.scale.libs.OneByoneNewLib
import com.enjoyyourtime.globalfitness.scale.libs.S400BodyComposition
import com.enjoyyourtime.globalfitness.scale.libs.S400Inputs
import com.enjoyyourtime.globalfitness.scale.libs.S400Reliability
import com.enjoyyourtime.globalfitness.scale.libs.SoehnleLib
import com.enjoyyourtime.globalfitness.scale.libs.TrisaBodyAnalyzeLib
import com.enjoyyourtime.globalfitness.scale.libs.Wla25BodyComposition
import com.enjoyyourtime.globalfitness.scale.libs.YunmaiLib
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every fixture table of openScale's own library tests, run against the ported libraries. */
class FormulaCrossCheckTest {
    private val eps = 1e-3f

    @Test
    fun yunmaiAllFixtures() {
        // sex, height, activity, age, weight, resistance, bf -> water, fat, muscle, skeletal, bone, lbm, visceral
        data class Fx(
            val sex: Int, val h: Float, val act: ActivityLevel, val age: Int, val w: Float, val res: Int, val bf: Float,
            val water: Float, val fat: Float, val muscle: Float, val skeletal: Float, val bone: Float, val lbm: Float, val vf: Float,
        )
        val fixtures = listOf(
            Fx(1, 180f, ActivityLevel.MODERATE, 30, 80f, 500, 23f, 55.907001f, 23.237043f, 51.595001f, 40.814999f, 3.263390f, 61.599998f, 11.318182f),
            Fx(0, 165f, ActivityLevel.MILD, 28, 60f, 520, 28f, 52.276997f, 29.947247f, 48.244999f, 38.164993f, 2.530795f, 43.200001f, 6.166667f),
            Fx(1, 175f, ActivityLevel.SEDENTARY, 55, 95f, 430, 32f, 49.372997f, 34.547203f, 45.564999f, 36.044998f, 3.365057f, 64.599998f, 18.590908f),
            Fx(0, 160f, ActivityLevel.SEDENTARY, 55, 50f, 600, 27f, 53.003002f, 28.532946f, 48.915001f, 38.694996f, 2.088284f, 36.500000f, 5.055555f),
            Fx(1, 190f, ActivityLevel.HEAVY, 20, 72f, 480, 14f, 62.441002f, 15.266259f, 60.205002f, 51.605000f, 3.519648f, 61.919998f, 9.000000f),
            Fx(0, 155f, ActivityLevel.MODERATE, 22, 55f, 510, 29f, 51.551003f, 30.724077f, 47.575001f, 37.634998f, 2.187678f, 39.049999f, 6.722222f),
            Fx(1, 175f, ActivityLevel.MILD, 35, 85f, 200, 25f, 54.455002f, 27.232653f, 50.255001f, 39.754993f, 3.322063f, 63.750000f, 13.136364f),
            Fx(0, 170f, ActivityLevel.SEDENTARY, 40, 70f, 800, 36f, 46.468998f, 34.777931f, 42.884998f, 33.924999f, 2.674562f, 44.799999f, 10.409091f),
        )
        for (f in fixtures) {
            val l = YunmaiLib(f.sex, f.h, f.act)
            assertEquals("$f water", f.water, l.getWater(f.bf), eps)
            assertEquals("$f fat", f.fat, l.getFat(f.age, f.w, f.res), eps)
            assertEquals("$f muscle", f.muscle, l.getMuscle(f.bf), eps)
            assertEquals("$f skeletal", f.skeletal, l.getSkeletalMuscle(f.bf), eps)
            assertEquals("$f bone", f.bone, l.getBoneMass(f.muscle, f.w), eps)
            assertEquals("$f lbm", f.lbm, l.getLeanBodyMass(f.w, f.bf), eps)
            assertEquals("$f vf", f.vf, l.getVisceralFat(f.bf, f.age), eps)
        }
    }

    @Test
    fun trisaAllFixtures() {
        // sex, age, height, weight, impedance -> water, fat, muscle, bone
        val fixtures = listOf(
            listOf(1f, 30f, 180f, 80f, 500f, 57.031845f, 23.186619f, 40.767307f, 4.254889f),
            listOf(0f, 28f, 165f, 60f, 520f, 51.246567f, 27.63467f, 32.776436f, 4.575968f),
            listOf(1f, 45f, 175f, 95f, 430f, 51.385693f, 34.484245f, 30.524948f, 3.1716952f),
            listOf(0f, 55f, 160f, 50f, 600f, 55.407524f, 26.659752f, 27.356312f, 3.8092093f),
            listOf(1f, 20f, 190f, 65f, 480f, 64.203964f, 10.668964f, 49.972504f, 5.2273664f),
            listOf(0f, 22f, 155f, 55f, 510f, 49.936302f, 28.405312f, 33.747982f, 4.713689f),
            listOf(1f, 35f, 175f, 85f, 200f, 56.290474f, 25.228241f, 38.142612f, 3.9760387f),
            listOf(0f, 40f, 170f, 70f, 800f, 47.909973f, 35.216103f, 27.238312f, 3.7960525f),
        )
        for (f in fixtures) {
            val l = TrisaBodyAnalyzeLib(f[0].toInt(), f[1].toInt(), f[2])
            assertEquals("$f water", f[5], l.getWater(f[3], f[4]), eps)
            assertEquals("$f fat", f[6], l.getFat(f[3], f[4]), eps)
            assertEquals("$f muscle", f[7], l.getMuscle(f[3], f[4]), eps)
            assertEquals("$f bone", f[8], l.getBone(f[3], f[4]), eps)
        }
    }

    @Test
    fun soehnleAllFixtures() {
        // male, age, height, weight, imp50, imp5, activity -> fat, water, muscle
        val fixtures = listOf(
            listOf(1f, 30f, 180f, 80f, 500f, 200f, 3f, 18.604935f, 54.8644f, 9.49897f),
            listOf(0f, 28f, 165f, 60f, 520f, 210f, 4f, 26.137234f, 56.005848f, 5.708269f),
            listOf(1f, 35f, 178f, 85f, 480f, 190f, 5f, 26.860249f, 55.484665f, 10.4964695f),
            listOf(0f, 45f, 160f, 70f, 700f, 250f, 1f, 48.433907f, 38.981922f, 2.8784876f),
        )
        for (f in fixtures) {
            val l = SoehnleLib(f[0] == 1f, f[1].toInt(), f[2], f[6].toInt())
            assertEquals("$f fat", f[7], l.getFat(f[3], f[4]), eps)
            assertEquals("$f water", f[8], l.getWater(f[3], f[4]), eps)
            assertEquals("$f muscle", f[9], l.getMuscle(f[3], f[4], f[5]), eps)
        }
    }

    @Test
    fun oneByoneAllFixtures() {
        // sex, age, h, w, imp, peopleType -> bf, lbm, muscle, water, bone, vf
        val fixtures = listOf(
            listOf(1f, 30f, 180f, 80f, 500f, 0f, 23.315102f, 61.34792f, 40.97725f, 52.60584f, 3.030576f, 10.79977f),
            listOf(0f, 28f, 165f, 60f, 520f, 1f, 25.210106f, 44.873936f, 40.181107f, 51.305866f, 2.3883991f, 0.70499706f),
            listOf(1f, 52f, 175f, 95f, 430f, 2f, 26.381027f, 69.93803f, 35.573257f, 50.502613f, 3.1443515f, 13.163806f),
            listOf(1f, 25f, 178f, 72f, 80f, 0f, 16.04116f, 60.450363f, 230.51118f, 57.595764f, 3.0263696f, 9.316022f),
            listOf(0f, 35f, 170f, 68f, 300f, 2f, 25.14642f, 50.900436f, 60.656864f, 51.349552f, 2.650265f, 2.6039982f),
            listOf(1f, 45f, 182f, 90f, 1300f, 1f, 30.914497f, 62.176952f, 17.721643f, 49.32705f, 2.901557f, 11.179609f),
        )
        for (f in fixtures) {
            val l = OneByoneLib(f[0].toInt(), f[1].toInt(), f[2], f[5].toInt())
            val bf = l.getBodyFat(f[3], f[4])
            assertEquals("$f bf", f[6], bf, eps)
            assertEquals("$f lbm", f[7], l.getLBM(f[3], bf), eps)
            assertEquals("$f muscle", f[8], l.getMuscle(f[3], f[4]), eps)
            assertEquals("$f water", f[9], l.getWater(bf), eps)
            assertEquals("$f bone", f[10], l.getBoneMass(f[3], f[4]), eps)
            assertEquals("$f vf", f[11], l.getVisceralFat(f[3]), eps)
        }
    }

    @Test
    fun oneByoneNewAllFixtures() {
        // sex, age, h, w, imp, peopleType -> lbm, bf, bone, skeletal, vf, water, protein
        val fixtures = listOf(
            listOf(1f, 30f, 180f, 80f, 500f, 0f, 62.14792f, 23.315102f, 3.1254208f, 40.566765f, 10.79977f, 52.60584f, 15.963814f),
            listOf(0f, 28f, 165f, 60f, 520f, 1f, 51.032806f, 28.27285f, 2.486581f, 36.45647f, 4.704997f, 49.204823f, 14.44164f),
            listOf(1f, 25f, 178f, 72f, 80f, 0f, 62.06637f, 14.907819f, 3.1212144f, 45.008743f, 9.316022f, 58.373234f, 17.714064f),
            listOf(0f, 35f, 170f, 68f, 300f, 2f, 56.22662f, 31.690466f, 2.754478f, 36.679123f, 6.603998f, 48.773006f, 11.583979f),
            listOf(1f, 45f, 182f, 90f, 1300f, 1f, 59.750725f, 34.49919f, 3.0017734f, 36.065098f, 13.974511f, 46.76758f, 11.656517f),
        )
        for (f in fixtures) {
            val l = OneByoneNewLib(f[0].toInt(), f[1].toInt(), f[2], f[5].toInt())
            val imp = f[4].toInt()
            assertEquals("$f lbm", f[6], l.getLBM(f[3], imp), eps)
            assertEquals("$f bf", f[7], l.getBodyFatPercentage(f[3], imp), eps)
            assertEquals("$f bone", f[8], l.getBoneMass(f[3], imp), eps)
            assertEquals("$f skeletal", f[9], l.getSkeletonMusclePercentage(f[3], imp), eps)
            assertEquals("$f vf", f[10], l.getVisceralFat(f[3]), eps)
            assertEquals("$f water", f[11], l.getWaterPercentage(f[3], imp), eps)
            assertEquals("$f protein", f[12], l.getProteinPercentage(f[3], imp), eps)
        }
    }

    @Test
    fun s400DualFrequencySubjects() {
        val a = S400BodyComposition.compute(S400Inputs(27, true, 172f, 76.5f, 365f, 402f))
        assertEquals(48.13f, a.tbwKg!!, 0.05f)
        assertEquals(62.9f, a.tbwPct!!, 0.1f)
        assertEquals(65.75f, a.ffmKg!!, 0.05f)
        assertEquals(14.1f, a.bfPct!!, 0.1f)
        assertEquals(36.56f, a.smmKg!!, 0.05f)
        assertEquals(47.8f, a.smmPct!!, 0.1f)
        assertEquals(2.99f, a.boneKg!!, 0.05f)
        assertEquals(13.2f, a.vfi!!, 0.1f)
        assertEquals(1790f, a.bmrKcal!!, 5f)
        val swapped = S400BodyComposition.compute(S400Inputs(27, true, 172f, 76.5f, 402f, 365f))
        assertTrue(swapped.labelSwapApplied)
        assertEquals(65.75f, swapped.ffmKg!!, 0.05f)
        val b = S400BodyComposition.compute(S400Inputs(55, false, 162f, 68f, 600f, 690f))
        assertEquals(29.12f, b.tbwKg!!, 0.05f)
        assertEquals(39.79f, b.ffmKg!!, 0.05f)
        assertEquals(41.5f, b.bfPct!!, 0.1f)
        assertEquals(17.14f, b.smmKg!!, 0.05f)
        assertEquals(2.47f, b.boneKg!!, 0.05f)
        assertEquals(1229f, b.bmrKcal!!, 5f)
        val c = S400BodyComposition.compute(S400Inputs(70, true, 170f, 80f, 520f, 610f))
        assertEquals(52.37f, c.ffmKg!!, 0.05f)
        assertEquals(34.5f, c.bfPct!!, 0.1f)
        assertEquals(24.22f, c.smmKg!!, 0.05f)
        assertEquals(1501f, c.bmrKcal!!, 5f)
        val edge = S400BodyComposition.compute(S400Inputs(30, true, 180f, 85f, 395f, 398f))
        assertEquals(S400Reliability.UNRELIABLE, edge.reliability)
        assertNull(edge.bfPct)
    }

    @Test
    fun wla25Rules() {
        val imps = doubleArrayOf(20.0, 300.0, 320.0, 310.0, 305.0, 18.0, 300.0, 315.0, 308.0, 302.0)
        assertTrue(Wla25BodyComposition.impedancesValid(imps))
        assertNull(Wla25BodyComposition.compute(170, 70.0, DoubleArray(10)))
        assertEquals(22.857142857142858, Wla25BodyComposition.bmi(175, 70.0), 1e-9)
        assertEquals(26.4f, Wla25BodyComposition.compute(100, 26.35, imps)!!.weightKg)
        assertEquals(1.9f, Wla25BodyComposition.compute(100, 1.95, imps)!!.weightKg)
        val r = Wla25BodyComposition.compute(175, 70.0, imps)!!
        assertEquals((r.lbmKg * 21.6 + 370.0).toInt(), r.bmrKcal)
        assertEquals(r.musclePercent / 100f * r.weightKg, r.muscleKg, 0.05f)
        for (w in listOf(40.0, 60.0, 80.0, 110.0)) {
            val x = Wla25BodyComposition.compute(175, w, imps)!!
            assertTrue(x.fat in 3.0f..60.0f)
            assertTrue(x.visceralFat in 1..20)
        }
    }

    @Test
    fun aiLinkBroadcastFrames() {
        fun parse(h: String) = AiLinkLib.parse(0x0301, hex(h))
        val done = parse("015f4e3d2c1b0a8f10e69cf7cb452cccffff")!!
        assertEquals("0A:1B:2C:3D:4E:5F", done.macAddress)
        assertEquals(AiLinkLib.STATUS_COMPLETE, done.status)
        assertEquals(42, done.sequence)
        assertEquals(1, done.decimals)
        assertEquals(700, done.rawWeight)
        assertEquals(70.0f, done.weightKg!!, 0.001f)
        assertEquals(500, done.impedance)
        assertEquals(68.4f, parse("015f4e3d2c1b0a7e945ab8103c0b750effff")!!.weightKg!!, 0.001f)
        val measuring = parse("015f4e3d2c1b0a3331c5f84954a1b455ffff")!!
        assertTrue(!measuring.isComplete)
        assertEquals(70.0f, measuring.weightKg!!, 0.001f)
        assertNull(parse("015f4e3d2c1b0a81cd9244ffe6049c5bffff")!!.usableWeightKg) // idle
        assertEquals(0, parse("015f4e3d2c1b0af995fd662927ee4283ffff")!!.impedance)
        assertNull(parse("015f4e3d2c1b0a9010e69cf7cb452cccffff")) // checksum
        assertNull(AiLinkLib.parse(0x00FF, ByteArray(18)))
    }
}
