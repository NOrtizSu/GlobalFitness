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

    private fun ledger(p: ExportPayload, version: Long = 1, key: String? = p.recordKey) =
        LedgerEntry(p.measurementId, p.signature, version, p.types, key)

    private val now = t0 + 400 * 86_400_000L

    // ---- clientRecordId scheme ------------------------------------------------------------

    @Test
    fun clientRecordIdsUseTheStableNaturalId() {
        val m = m(42, weight = 62.35)
        val key = ClientRecordIds.recordKey(m)
        assertEquals("m_${m.timestamp}_62350", key)
        assertEquals("$key-weight", ClientRecordIds.of(key, ExportRecordType.WEIGHT))
        assertEquals("$key-bodyfat", ClientRecordIds.of(key, ExportRecordType.BODY_FAT))
        assertEquals("$key-bmr", ClientRecordIds.of(key, ExportRecordType.BASAL_METABOLIC_RATE))
        val ids = ExportRecordType.entries.map { ClientRecordIds.of(key, it) }
        assertEquals("ids must be unique per type", ids.size, ids.toSet().size)
        // The local database id plays no part: the same reading after a reinstall gets the same ids.
        assertEquals(key, ClientRecordIds.recordKey(m.copy(id = 1)))
        assertEquals(key, payload(42, weight = 62.35).recordKey)
    }

    @Test
    fun legacyLedgerEntriesResolveToTheOldIds() {
        assertEquals("gf-42-weight", ClientRecordIds.legacy(42, ExportRecordType.WEIGHT))
        val legacy = LedgerEntry(42, "sig", 1, setOf(ExportRecordType.WEIGHT, ExportRecordType.BODY_FAT), recordKey = null)
        assertEquals(listOf("gf-42-weight", "gf-42-bodyfat"), legacy.refs().map { it.clientId })
        val current = legacy.copy(recordKey = "m_5_62000")
        assertEquals("m_5_62000-weight", current.clientId(ExportRecordType.WEIGHT))
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
        val plan = ScaleExportPlanner.plan(setOf(1, 2, 3), payloads, emptyList(), autoAfterId = 2, backfill = false, writeEnabled = true, now = now)
        assertEquals(listOf(3L), plan.writes.map { it.payload.measurementId })
        // First writes are versioned with the clock, above anything an earlier installation wrote.
        assertEquals(now, plan.writes.single().version)
        assertTrue(plan.writes.single().stale.isEmpty())
        assertTrue(plan.deletes.isEmpty())
    }

    @Test
    fun historyExportWritesEverythingNotYetWritten() {
        val payloads = listOf(payload(1), payload(2), payload(3))
        val plan = ScaleExportPlanner.plan(setOf(1, 2, 3), payloads, listOf(ledger(payload(2))), autoAfterId = 5, backfill = true, writeEnabled = true, now = now)
        assertEquals(listOf(1L, 3L), plan.writes.map { it.payload.measurementId })
    }

    @Test
    fun unchangedMeasurementsAreNotRewritten() {
        val p = payload(4)
        val plan = ScaleExportPlanner.plan(setOf(4), listOf(p), listOf(ledger(p, version = 3)), autoAfterId = 0, backfill = true, writeEnabled = true, now = now)
        assertTrue(plan.isEmpty)
    }

    @Test
    fun changedMeasurementIsRewrittenWithHigherVersion() {
        // Weight-only first, then the impedance arrived: same client ids, version + 1.
        val before = payload(4, impedance = null)
        val after = payload(4)
        val plan = ScaleExportPlanner.plan(setOf(4), listOf(after), listOf(ledger(before, version = now)), autoAfterId = 10, backfill = false, writeEnabled = true, now = now)
        val write = plan.writes.single()
        assertEquals(now + 1, write.version)
        assertTrue(write.stale.isEmpty())

        // The other way round: values that disappear are deleted (same ids).
        val shrink = ScaleExportPlanner.plan(setOf(4), listOf(before), listOf(ledger(after, version = 7)), autoAfterId = 10, backfill = false, writeEnabled = true, now = now)
        assertEquals(now, shrink.writes.single().version)
        assertEquals(
            (after.types - before.types).map { ClientRecordIds.of(before.recordKey, it) }.toSet(),
            shrink.writes.single().stale.map { it.clientId }.toSet(),
        )
    }

    @Test
    fun legacyEntriesAreRewrittenWithNaturalIdsAndTheirOldRecordsDeleted() {
        val p = payload(4)
        val legacy = ledger(p, version = 2, key = null)
        val plan = ScaleExportPlanner.plan(setOf(4), listOf(p), listOf(legacy), autoAfterId = 10, backfill = false, writeEnabled = true, now = now)
        val write = plan.writes.single()
        assertEquals(p.recordKey, write.payload.recordKey)
        assertEquals(p.types.map { ClientRecordIds.legacy(4, it) }.toSet(), write.stale.map { it.clientId }.toSet())
        // With the switch off nothing is migrated yet, but deletions keep working on the legacy ids.
        val off = ScaleExportPlanner.plan(emptySet(), emptyList(), listOf(legacy), autoAfterId = 10, backfill = false, writeEnabled = false, now = now)
        assertEquals(listOf("gf-4-weight"), off.deletes.single().refs(setOf(ExportRecordType.WEIGHT)).map { it.clientId })
    }

    @Test
    fun legacyMigrationWaitsUntilEveryOldRecordCanBeDeleted() {
        val p = payload(4)
        val legacy = ledger(p, version = 2, key = null)
        val weightOnly = setOf(ExportRecordType.WEIGHT)
        val partial = ScaleExportPlanner.plan(
            setOf(4), listOf(p), listOf(legacy), autoAfterId = 10, backfill = false, writeEnabled = true, now = now, deletable = weightOnly,
        )
        assertTrue("keeps the legacy entry (and its ids) until body fat etc. can be deleted too", partial.isEmpty)
    }

    @Test
    fun rowsSharingANaturalIdAreWrittenOnce() {
        // A manual weight saved twice in the same minute: same timestamp and grams.
        val first = payload(7, impedance = null)
        val twin = first.copy(measurementId = 8)
        val plan = ScaleExportPlanner.plan(setOf(7, 8), listOf(twin, first), emptyList(), autoAfterId = 0, backfill = false, writeEnabled = true, now = now)
        assertEquals(listOf(7L), plan.writes.map { it.payload.measurementId })
    }

    @Test
    fun editedWeightMovesTheRecordsToTheNewNaturalId() {
        val before = payload(4, weight = 62.0)
        val after = payload(4, weight = 62.4)
        val plan = ScaleExportPlanner.plan(setOf(4), listOf(after), listOf(ledger(before)), autoAfterId = 10, backfill = false, writeEnabled = true, now = now)
        val write = plan.writes.single()
        assertNotEquals(before.recordKey, after.recordKey)
        assertEquals(before.types.map { ClientRecordIds.of(before.recordKey, it) }.toSet(), write.stale.map { it.clientId }.toSet())
    }

    @Test
    fun deletedOrUnassignedMeasurementsAreDeletedEvenWithTheSwitchOff() {
        val p1 = payload(1)
        val p2 = payload(2)
        val plan = ScaleExportPlanner.plan(setOf(1), listOf(p1), listOf(ledger(p1), ledger(p2)), autoAfterId = 0, backfill = false, writeEnabled = false, now = now)
        assertTrue(plan.writes.isEmpty())
        assertEquals(listOf(2L), plan.deletes.map { it.measurementId })
    }

    @Test
    fun switchOffWritesNothing() {
        val plan = ScaleExportPlanner.plan(setOf(9), listOf(payload(9)), emptyList(), autoAfterId = 0, backfill = false, writeEnabled = false, now = now)
        assertTrue(plan.isEmpty)
    }

    @Test
    fun missingPayloadDoesNotDeleteAnOwnedMeasurement() {
        // Weight permission revoked: no payloads, but measurements still belong to the profile.
        val p = payload(1)
        val plan = ScaleExportPlanner.plan(setOf(1), emptyList(), listOf(ledger(p)), autoAfterId = 0, backfill = false, writeEnabled = true, now = now)
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
        assertEquals("${p.recordKey}-weight", weight.metadata.clientRecordId)
        assertEquals(3L, weight.metadata.clientRecordVersion)
        assertEquals(Device.TYPE_SCALE, weight.metadata.device?.type)
        assertEquals(Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED, weight.metadata.recordingMethod)
        assertEquals("${p.recordKey}-bodyfat", records.filterIsInstance<BodyFatRecord>().single().metadata.clientRecordId)
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
