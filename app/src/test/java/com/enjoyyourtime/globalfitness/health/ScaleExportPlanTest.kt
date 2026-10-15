package com.enjoyyourtime.globalfitness.health

import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import com.enjoyyourtime.globalfitness.data.Measurement
import com.enjoyyourtime.globalfitness.data.MeasurementSource
import com.enjoyyourtime.globalfitness.data.Profile
import com.enjoyyourtime.globalfitness.data.Sex
import com.enjoyyourtime.globalfitness.domain.BodyMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ScaleExportPlanTest {
    private val profile = Profile(1, "Ana", Sex.FEMALE, LocalDate.of(1990, 5, 1), 165.0, null, 0, 0)
    private val t0 = 1_800_000_000_000L
    private val all = ExportRecordType.entries.toSet()

    private fun m(id: Long, impedance: Int? = 500, weight: Double = 62.0, source: MeasurementSource = MeasurementSource.SCALE) =
        Measurement(id, profile.id, t0 + id * 86_400_000L, weight, impedance, "k$id", source)

    private fun payload(id: Long, impedance: Int? = 500, weight: Double = 62.0) = ExportPayload.from(m(id, impedance, weight), profile, all)!!

    private fun ledger(p: ExportPayload, version: Long = 1) = LedgerEntry(p.measurementId, p.signature, version, p.types)

    // ---- clientRecordId scheme ------------------------------------------------------------

    @Test
    fun clientRecordIdSchemeRoundTrips() {
        assertEquals("gf-42-weight", ClientRecordIds.of(42, ExportRecordType.WEIGHT))
        assertEquals("gf-42-bodyfat", ClientRecordIds.of(42, ExportRecordType.BODY_FAT))
        assertEquals("gf-7-bmr", ClientRecordIds.of(7, ExportRecordType.BASAL_METABOLIC_RATE))
        for (type in ExportRecordType.entries) {
            assertEquals(123L to type, ClientRecordIds.parse(ClientRecordIds.of(123, type)))
        }
        val ids = ExportRecordType.entries.map { ClientRecordIds.of(5, it) }
        assertEquals("ids must be unique per type", ids.size, ids.toSet().size)
        assertNull(ClientRecordIds.parse("gf-x-weight"))
        assertNull(ClientRecordIds.parse("gf-5-height"))
        assertNull(ClientRecordIds.parse("other-5-weight"))
    }

    // ---- Payload --------------------------------------------------------------------------

    @Test
    fun payloadWithImpedanceHasAllSixValues() {
        val p = payload(1)
        val c = BodyMetrics.compute(profile.sex, profile.heightCm, profile.ageAt(m(1).timestamp), 62.0, 500)
        assertEquals(all, p.types)
        assertEquals(62.0, p.weightKg, 1e-9)
        assertEquals(c.fatPercent!!, p.bodyFatPercent!!, 1e-9)
        assertEquals(c.boneKg!!, p.boneKg!!, 1e-9)
        assertEquals(62.0 - c.fatMassKg!!, p.leanKg!!, 1e-9)
        assertEquals(c.bmr, p.bmrKcalPerDay!!, 1e-9)
        assertEquals(62.0 * c.waterPercent!! / 100.0, p.waterKg!!, 1e-9)
        assertTrue(p.fromScale)
    }

    @Test
    fun payloadWithoutImpedanceHasWeightAndBmrOnly() {
        val p = payload(1, impedance = null)
        assertEquals(setOf(ExportRecordType.WEIGHT, ExportRecordType.BASAL_METABOLIC_RATE), p.types)
    }

    @Test
    fun payloadKeepsOnlyWritableTypesAndNeedsWeight() {
        val p = ExportPayload.from(m(1), profile, setOf(ExportRecordType.WEIGHT, ExportRecordType.BODY_FAT))!!
        assertEquals(setOf(ExportRecordType.WEIGHT, ExportRecordType.BODY_FAT), p.types)
        assertNull(ExportPayload.from(m(1), profile, setOf(ExportRecordType.BODY_FAT)))
    }

    @Test
    fun signatureChangesWhenValuesChange() {
        assertEquals(payload(1).signature, payload(1).signature)
        assertNotEquals(payload(1).signature, payload(1, impedance = null).signature)
        assertNotEquals(payload(1).signature, payload(1, weight = 62.4).signature)
    }

    // ---- Planner --------------------------------------------------------------------------

    @Test
    fun newMeasurementsAfterTheWatermarkAreWrittenAutomatically() {
        val payloads = listOf(payload(1), payload(2), payload(3))
        val plan = ScaleExportPlanner.plan(setOf(1, 2, 3), payloads, emptyList(), autoAfterId = 2, backfill = false, writeEnabled = true)
        assertEquals(listOf(3L), plan.writes.map { it.payload.measurementId })
        assertEquals(1L, plan.writes.single().version)
        assertTrue(plan.deletes.isEmpty())
    }

    @Test
    fun historyExportWritesEverythingNotYetWritten() {
        val payloads = listOf(payload(1), payload(2), payload(3))
        val plan = ScaleExportPlanner.plan(setOf(1, 2, 3), payloads, listOf(ledger(payload(2))), autoAfterId = 5, backfill = true, writeEnabled = true)
        assertEquals(listOf(1L, 3L), plan.writes.map { it.payload.measurementId })
    }

    @Test
    fun unchangedMeasurementsAreNotRewritten() {
        val p = payload(4)
        val plan = ScaleExportPlanner.plan(setOf(4), listOf(p), listOf(ledger(p, version = 3)), autoAfterId = 0, backfill = true, writeEnabled = true)
        assertTrue(plan.isEmpty)
    }

    @Test
    fun changedMeasurementIsRewrittenWithHigherVersion() {
        // Weight-only first, then the impedance arrived: same client ids, version + 1.
        val before = payload(4, impedance = null)
        val after = payload(4)
        val plan = ScaleExportPlanner.plan(setOf(4), listOf(after), listOf(ledger(before, version = 1)), autoAfterId = 10, backfill = false, writeEnabled = true)
        val write = plan.writes.single()
        assertEquals(2L, write.version)
        assertTrue(write.staleTypes.isEmpty())

        // The other way round: values that disappear are deleted.
        val shrink = ScaleExportPlanner.plan(setOf(4), listOf(before), listOf(ledger(after, version = 2)), autoAfterId = 10, backfill = false, writeEnabled = true)
        assertEquals(3L, shrink.writes.single().version)
        assertEquals(after.types - before.types, shrink.writes.single().staleTypes)
    }

    @Test
    fun deletedOrUnassignedMeasurementsAreDeletedEvenWithTheSwitchOff() {
        val p1 = payload(1)
        val p2 = payload(2)
        val plan = ScaleExportPlanner.plan(setOf(1), listOf(p1), listOf(ledger(p1), ledger(p2)), autoAfterId = 0, backfill = false, writeEnabled = false)
        assertTrue(plan.writes.isEmpty())
        assertEquals(listOf(2L), plan.deletes.map { it.measurementId })
    }

    @Test
    fun switchOffWritesNothing() {
        val plan = ScaleExportPlanner.plan(setOf(9), listOf(payload(9)), emptyList(), autoAfterId = 0, backfill = false, writeEnabled = false)
        assertTrue(plan.isEmpty)
    }

    @Test
    fun missingPayloadDoesNotDeleteAnOwnedMeasurement() {
        // Weight permission revoked: no payloads, but measurements still belong to the profile.
        val p = payload(1)
        val plan = ScaleExportPlanner.plan(setOf(1), emptyList(), listOf(ledger(p)), autoAfterId = 0, backfill = false, writeEnabled = true)
        assertTrue(plan.isEmpty)
    }

    // ---- Health Connect records -----------------------------------------------------------

    @Test
    fun recordsCarryClientIdsVersionAndScaleDevice() {
        val p = payload(8)
        val records = ScaleExporter.buildRecords(p, version = 3, zone = ZoneId.of("Europe/Madrid"))
        assertEquals(6, records.size)
        val weight = records.filterIsInstance<WeightRecord>().single()
        assertEquals(62.0, weight.weight.inKilograms, 1e-9)
        assertEquals("gf-8-weight", weight.metadata.clientRecordId)
        assertEquals(3L, weight.metadata.clientRecordVersion)
        assertEquals(Device.TYPE_SCALE, weight.metadata.device?.type)
        assertEquals(Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED, weight.metadata.recordingMethod)
        assertEquals("gf-8-bodyfat", records.filterIsInstance<BodyFatRecord>().single().metadata.clientRecordId)
        val bmr = records.filterIsInstance<BasalMetabolicRateRecord>().single()
        assertEquals(p.bmrKcalPerDay!!, bmr.basalMetabolicRate.inKilocaloriesPerDay, 1e-6)
        assertEquals(records.map { it.metadata.clientRecordId }.toSet().size, records.size)
    }

    @Test
    fun manualMeasurementsAreManualEntries() {
        val p = ExportPayload.from(m(3, impedance = null, source = MeasurementSource.MANUAL), profile, all)!!
        val records = ScaleExporter.buildRecords(p, version = 1, zone = ZoneId.of("UTC"))
        assertEquals(2, records.size)
        records.forEach { assertEquals(Metadata.RECORDING_METHOD_MANUAL_ENTRY, it.metadata.recordingMethod) }
    }
}
