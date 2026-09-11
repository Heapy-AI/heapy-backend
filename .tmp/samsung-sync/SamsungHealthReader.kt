package com.heapy.app

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.data.AggregateOperation
import com.samsung.android.sdk.health.data.data.ChangeType
import com.samsung.android.sdk.health.data.data.Field
import com.samsung.android.sdk.health.data.data.HealthDataPoint
import com.samsung.android.sdk.health.data.request.AggregateRequest
import com.samsung.android.sdk.health.data.request.ChangedDataRequest
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.InstantTimeFilter
import com.samsung.android.sdk.health.data.request.LocalTimeFilter
import com.samsung.android.sdk.health.data.request.LocalTimeGroup
import com.samsung.android.sdk.health.data.request.LocalTimeGroupUnit
import com.samsung.android.sdk.health.data.request.ReadDataRequest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.round

/** SDK 1.1.0 원본과 일별 집계를 서버 단위로 변환한다. 작성자: 김진우 */
class SamsungHealthReader(private val store: HealthDataStore) {
    private val zone = ZoneId.of("Asia/Seoul")

    suspend fun page(type: String, from: Instant, to: Instant, changes: Boolean, token: String?): WritableMap {
        require(from < to && Duration.between(from, to).toDays() <= 370)
        val output = Arguments.createArray()
        val filter = InstantTimeFilter.of(from, to)
        val next: String?
        if (changes) {
            val builder = changed(type).setPageSize(200).setChangeTimeFilter(filter)
            if (!token.isNullOrEmpty()) builder.setPageToken(token)
            val result = store.readChanges(builder.build())
            result.dataList.forEach { change ->
                if (change.changeType == ChangeType.DELETE) {
                    output.pushMap(envelope(type, requireNotNull(change.deleteDataUid), change.changeTime, true))
                } else {
                    val point = requireNotNull(change.upsertDataPoint)
                    output.pushMap(record(type, point, maxOf(point.updateTime, change.changeTime)))
                }
            }
            next = result.pageToken
        } else {
            val builder = raw(type).setPageSize(200).setInstantTimeFilter(filter)
            if (!token.isNullOrEmpty()) builder.setPageToken(token)
            val result = store.readData(builder.build())
            result.dataList.forEach { output.pushMap(record(type, it, it.updateTime)) }
            next = result.pageToken
        }
        return Arguments.createMap().apply { putArray("records", output); putString("nextPageToken", next) }
    }

    // 작성자: 김진우 — 걸음은 삼성헬스의 중복 기기 제거 집계값을 사용한다. 빈 날은 삭제로 표현한다.
    suspend fun activity(from: LocalDate, to: LocalDate, cutoff: Instant): WritableMap {
        require(from <= to && Duration.between(from.atStartOfDay(), to.atStartOfDay()).toDays() < 31)
        val start = from.atStartOfDay()
        val end = minOf(to.plusDays(1).atStartOfDay(), cutoff.atZone(zone).toLocalDateTime())
        require(start < end)
        val values = mutableMapOf<LocalDate, MutableMap<String, Double>>()
        suspend fun <T> collect(name: String, operation: AggregateOperation<T, AggregateRequest.LocalTimeBuilder<T>>, convert: (T) -> Double) {
            var token: String? = null
            do {
                val builder = operation.requestBuilder.setPageSize(100).setLocalTimeFilterWithGroup(
                    LocalTimeFilter.of(start, end), LocalTimeGroup.of(LocalTimeGroupUnit.DAILY, 1))
                if (!token.isNullOrEmpty()) builder.setPageToken(token!!)
                val result = store.aggregateData(builder.build())
                result.dataList.forEach { item ->
                    item.value?.let { value ->
                        val day = item.startLocalDateTime.toLocalDate()
                        if (day >= from && day <= to) values.getOrPut(day) { mutableMapOf() }[name] = convert(value)
                    }
                }
                val next = result.pageToken
                check(next.isNullOrEmpty() || next != token)
                token = next
            } while (!token.isNullOrEmpty())
        }
        collect("steps", DataType.StepsType.TOTAL) { it.toDouble() }
        collect("floors", DataType.FloorsClimbedType.TOTAL) { it.toInt().toDouble() }
        collect("activeTimeMinutes", DataType.ActivitySummaryType.TOTAL_ACTIVE_TIME) { it.toMinutes().toDouble() }
        collect("distanceM", DataType.ActivitySummaryType.TOTAL_DISTANCE) { it.toDouble() }
        collect("activeCaloriesKcal", DataType.ActivitySummaryType.TOTAL_ACTIVE_CALORIES_BURNED) { it.toDouble() }
        val records = Arguments.createArray()
        var day = from
        while (day <= to) {
            val data = values[day]
            records.pushMap(envelope("activity", day.toString(), cutoff, data == null).apply {
                if (data != null) putMap("data", Arguments.createMap().apply {
                    putString("recordDate", day.toString())
                    data.forEach { (key, value) -> number(key, value) }
                })
            })
            day = day.plusDays(1)
        }
        return Arguments.createMap().apply { putArray("records", records); putNull("nextPageToken") }
    }

    private fun record(type: String, point: HealthDataPoint, updated: Instant): WritableMap {
        val data = Arguments.createMap()
        fun number(name: String, field: Field<Float>) { data.number(name, point.getValue(field)?.toDouble()) }
        when (type) {
            "sleep" -> {
                data.putString("startAt", point.startTime.toString()); data.putString("endAt", point.endTime.toString())
                data.number("totalSleepMinutes", requireNotNull(point.getValue(DataType.SleepType.DURATION)).toMinutes().toDouble())
                val stages = point.getValue(DataType.SleepType.SESSIONS)?.flatMap { it.stages }
                if (!stages.isNullOrEmpty()) {
                    mapOf("awakeMinutes" to DataType.SleepType.StageType.AWAKE, "deepSleepMinutes" to DataType.SleepType.StageType.DEEP,
                        "lightSleepMinutes" to DataType.SleepType.StageType.LIGHT, "remSleepMinutes" to DataType.SleepType.StageType.REM).forEach { (key, stage) ->
                        data.number(key, stages.filter { it.stage == stage }.sumOf { Duration.between(it.startTime, it.endTime).seconds }.div(60).toDouble())
                    }
                }
                data.number("sleepScore", point.getValue(DataType.SleepType.SLEEP_SCORE)?.toDouble())
            }
            "heart_rate" -> number("heartRateBpm", DataType.HeartRateType.HEART_RATE)
            "blood_pressure" -> {
                number("systolicMmhg", DataType.BloodPressureType.SYSTOLIC); number("diastolicMmhg", DataType.BloodPressureType.DIASTOLIC)
                data.number("pulseBpm", point.getValue(DataType.BloodPressureType.PULSE_RATE)?.toDouble())
            }
            "blood_glucose" -> {
                // 작성자: 김진우 — SDK mmol/L를 DB mg/dL로 변환한다. 주입 인슐린 단위는 혈중 농도가 아니다.
                data.number("bloodGlucoseMgDl", point.getValue(DataType.BloodGlucoseType.GLUCOSE_LEVEL)?.times(18.0182))
                val meal = point.getValue(DataType.BloodGlucoseType.MEAL_STATUS)?.name
                if (meal == "FASTING") data.putBoolean("isFasting", true)
                else if (meal?.startsWith("AFTER_") == true) data.putBoolean("isFasting", false)
            }
            "body_composition" -> {
                number("weightKg", DataType.BodyCompositionType.WEIGHT); number("heightCm", DataType.BodyCompositionType.HEIGHT)
                number("bodyFatPercent", DataType.BodyCompositionType.BODY_FAT)
                number("skeletalMuscleKg", DataType.BodyCompositionType.SKELETAL_MUSCLE_MASS)
                number("bmiValue", DataType.BodyCompositionType.BODY_MASS_INDEX)
            }
            "exercise" -> {
                val sessions = requireNotNull(point.getValue(DataType.ExerciseType.SESSIONS))
                require(sessions.isNotEmpty())
                data.putString("startAt", point.startTime.toString()); data.putString("endAt", point.endTime.toString())
                data.putString("exerciseType", point.getValue(DataType.ExerciseType.CUSTOM_TITLE)?.takeIf { it.isNotBlank() }
                    ?: point.getValue(DataType.ExerciseType.EXERCISE_TYPE)?.name ?: "기타 운동")
                data.number("durationSeconds", sessions.sumOf { it.duration.seconds }.toDouble())
                data.number("caloriesKcal", sessions.sumOf { it.calories.toDouble() })
                if (sessions.all { it.distance != null }) data.number("distanceM", sessions.sumOf { it.distance!!.toDouble() })
            }
            "water" -> { data.putString("consumedAt", point.startTime.toString()); number("amountMl", DataType.WaterIntakeType.AMOUNT) }
            "nutrition" -> {
                data.putString("consumedAt", point.startTime.toString())
                point.getValue(DataType.NutritionType.TITLE)?.takeIf { it.isNotBlank() }?.let { data.putString("title", it.take(500)) }
                point.getValue(DataType.NutritionType.MEAL_TYPE)?.let { data.putString("mealType", it.name.lowercase()) }
                mapOf("calories" to DataType.NutritionType.CALORIES, "totalFat" to DataType.NutritionType.TOTAL_FAT,
                    "saturatedFat" to DataType.NutritionType.SATURATED_FAT, "polyunsaturatedFat" to DataType.NutritionType.POLYSATURATED_FAT,
                    "monounsaturatedFat" to DataType.NutritionType.MONOSATURATED_FAT, "transFat" to DataType.NutritionType.TRANS_FAT,
                    "carbohydrate" to DataType.NutritionType.CARBOHYDRATE, "dietaryFiber" to DataType.NutritionType.DIETARY_FIBER,
                    "sugar" to DataType.NutritionType.SUGAR, "protein" to DataType.NutritionType.PROTEIN,
                    "cholesterol" to DataType.NutritionType.CHOLESTEROL, "sodium" to DataType.NutritionType.SODIUM,
                    "potassium" to DataType.NutritionType.POTASSIUM, "vitaminA" to DataType.NutritionType.VITAMIN_A,
                    "vitaminC" to DataType.NutritionType.VITAMIN_C, "calcium" to DataType.NutritionType.CALCIUM,
                    "iron" to DataType.NutritionType.IRON).forEach { (key, field) -> number(key, field) }
            }
            else -> error("지원하지 않는 건강 항목입니다.")
        }
        if (type in setOf("heart_rate", "blood_pressure", "blood_glucose", "body_composition")) {
            data.putString("bioType", type); data.putString("measuredAt", point.startTime.toString())
        }
        return envelope(type, point.uid, updated, false).apply { putMap("data", data) }
    }

    private fun envelope(type: String, uid: String, updated: Instant, deleted: Boolean) = Arguments.createMap().apply {
        putString("metric", if (type in setOf("heart_rate", "blood_glucose", "blood_pressure", "body_composition")) "bio" else type)
        putString("externalRecordId", "$type:$uid"); putString("sourceUpdatedAt", updated.toString())
        putString("operation", if (deleted) "DELETE" else "UPSERT")
    }
    private fun WritableMap.number(name: String, value: Double?) {
        if (value != null) { require(value.isFinite() && value >= 0); putDouble(name, round(value * 10000) / 10000) }
    }
    private fun raw(type: String): ReadDataRequest.DualTimeBuilder<HealthDataPoint> = when (type) {
        "sleep" -> DataTypes.SLEEP.readDataRequestBuilder; "heart_rate" -> DataTypes.HEART_RATE.readDataRequestBuilder
        "blood_glucose" -> DataTypes.BLOOD_GLUCOSE.readDataRequestBuilder; "blood_pressure" -> DataTypes.BLOOD_PRESSURE.readDataRequestBuilder
        "body_composition" -> DataTypes.BODY_COMPOSITION.readDataRequestBuilder; "exercise" -> DataTypes.EXERCISE.readDataRequestBuilder
        "water" -> DataTypes.WATER_INTAKE.readDataRequestBuilder; "nutrition" -> DataTypes.NUTRITION.readDataRequestBuilder
        else -> error("지원하지 않는 건강 항목입니다.")
    }
    private fun changed(type: String): ChangedDataRequest.BasicBuilder<HealthDataPoint> = when (type) {
        "sleep" -> DataTypes.SLEEP.changedDataRequestBuilder; "heart_rate" -> DataTypes.HEART_RATE.changedDataRequestBuilder
        "blood_glucose" -> DataTypes.BLOOD_GLUCOSE.changedDataRequestBuilder; "blood_pressure" -> DataTypes.BLOOD_PRESSURE.changedDataRequestBuilder
        "body_composition" -> DataTypes.BODY_COMPOSITION.changedDataRequestBuilder; "exercise" -> DataTypes.EXERCISE.changedDataRequestBuilder
        "water" -> DataTypes.WATER_INTAKE.changedDataRequestBuilder; "nutrition" -> DataTypes.NUTRITION.changedDataRequestBuilder
        else -> error("지원하지 않는 건강 항목입니다.")
    }
}
