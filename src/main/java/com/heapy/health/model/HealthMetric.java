package com.heapy.health.model;

import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import java.util.List;

/** 사용자 입력을 SQL 식별자로 사용하지 않는 건강 지표 허용 목록이다. @author 김진우 */
public enum HealthMetric {
    SLEEP("sleep", "lifestyle_sleep", "sleep_id", "start_at", false,
            List.of(new Field("total_sleep_minutes", "수면시간", "분", true),
                    new Field("deep_sleep_minutes", "깊은 수면", "분", true),
                    new Field("light_sleep_minutes", "얕은 수면", "분", true),
                    new Field("rem_sleep_minutes", "REM 수면", "분", true),
                    new Field("awake_minutes", "깨어 있던 시간", "분", true),
                    new Field("sleep_score", "수면점수", "점", false))),
    BIO("bio", "lifestyle_bio", "bio_id", "measured_at", false,
            List.of(new Field("heart_rate_bpm", "심박수", "bpm", false),
                    new Field("systolic_mmhg", "수축기 혈압", "mmHg", false),
                    new Field("diastolic_mmhg", "이완기 혈압", "mmHg", false),
                    new Field("blood_glucose_mg_dl", "혈당", "mg/dL", false),
                    new Field("weight_kg", "체중", "kg", false), new Field("bmi_value", "BMI", "kg/m²", false),
                    new Field("body_fat_percent", "체지방률", "%", false),
                    new Field("skeletal_muscle_kg", "골격근량", "kg", false))),
    ACTIVITY("activity", "lifestyle_activity", "activity_id", "record_date", true,
            List.of(new Field("steps", "걸음 수", "걸음", true), new Field("floors", "오른 층수", "층", true),
                    new Field("active_time_minutes", "활동시간", "분", true),
                    new Field("distance_m", "활동거리", "m", true),
                    new Field("active_calories_kcal", "활동 열량", "kcal", true))),
    EXERCISE("exercise", "lifestyle_exercise", "exercise_id", "start_at", false,
            List.of(new Field("duration_seconds", "운동시간", "초", true),
                    new Field("distance_m", "운동거리", "m", true), new Field("calories_kcal", "운동 열량", "kcal", true))),
    NUTRITION("nutrition", "lifestyle_nutrition", "nutrition_id", "consumed_at", false,
            List.of(new Field("calories", "섭취 열량", "kcal", true),
                    new Field("carbohydrate", "탄수화물", "g", true), new Field("protein", "단백질", "g", true),
                    new Field("total_fat", "지방", "g", true), new Field("saturated_fat", "포화지방", "g", true),
                    new Field("sugar", "당", "g", true), new Field("sodium", "나트륨", "mg", true),
                    new Field("dietary_fiber", "식이섬유", "g", true), new Field("potassium", "칼륨", "mg", true),
                    new Field("calcium", "칼슘", "mg", true))),
    WATER("water", "lifestyle_water_intake", "water_intake_id", "consumed_at", false,
            List.of(new Field("amount_ml", "물 섭취", "mL", true)));

    private final String code;
    private final String table;
    private final String id;
    private final String time;
    private final boolean dateOnly;
    private final List<Field> fields;

    HealthMetric(String code, String table, String id, String time, boolean dateOnly, List<Field> fields) {
        this.code = code; this.table = table; this.id = id; this.time = time;
        this.dateOnly = dateOnly; this.fields = fields;
    }

    public static HealthMetric of(String code) {
        for (HealthMetric metric : values()) if (metric.code.equals(code)) return metric;
        throw new HeapyException(ErrorCode.INVALID_INPUT);
    }

    public String code() { return code; }
    public String table() { return table; }
    public String id() { return id; }
    public String time() { return time; }
    public boolean dateOnly() { return dateOnly; }
    public List<Field> fields() { return fields; }

    public record Field(String key, String label, String unit, boolean dailySum) { }
}
