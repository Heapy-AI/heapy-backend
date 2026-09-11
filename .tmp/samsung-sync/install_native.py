from pathlib import Path
import shutil
root = Path('C:/Users/jinwo/heapy-frontend/android/app/src/main/java/com/heapy/app')
shutil.copyfile(Path(__file__).with_name('SamsungHealthReader.kt'), root / 'SamsungHealthReader.kt')
p = root / 'SamsungHealthModule.kt'
s = p.read_text(encoding='utf-8')
s = s.replace('import com.facebook.react.bridge.ReactMethod', 'import com.facebook.react.bridge.ReactMethod\nimport com.facebook.react.bridge.ReadableMap\nimport java.time.LocalDate')
marker = '    private fun reject(error: Exception, promise: Promise) {'
assert marker in s
s = s.replace(marker, '''    /** 자동 동기화에서는 권한 창을 띄우지 않고 현재 권한만 확인한다. 작성자: 김진우 */
    @ReactMethod
    fun getReadPermissions(promise: Promise) {
        scope.launch {
            try {
                val granted = store.getGrantedPermissions(permissions)
                val preferences = context.getSharedPreferences("heapy.health.installation", Context.MODE_PRIVATE)
                val installationId = preferences.getString("id", null) ?: UUID.randomUUID().toString().also {
                    preferences.edit().putString("id", it).apply()
                }
                promise.resolve(Arguments.createMap().apply {
                    putString("deviceInstallationId", installationId)
                    putArray("grantedDataTypes", Arguments.createArray().apply {
                        permissionByType.forEach { (type, permission) -> if (granted.contains(permission)) pushString(type) }
                    })
                    putString("sdkVersion", "1.1.0")
                    putString("permissionCheckedAt", Instant.now().toString())
                })
            } catch (error: Exception) { reject(error, promise) }
        }
    }

    /** 한 페이지씩 읽어 메모리와 네트워크 배치 크기를 제한한다. 작성자: 김진우 */
    @ReactMethod
    fun readHealthPage(options: ReadableMap, promise: Promise) {
        scope.launch {
            try {
                if (!store.getGrantedPermissions(permissions).containsAll(permissions)) {
                    promise.reject("SAMSUNG_PERMISSION", "삼성헬스의 11개 읽기 권한을 모두 허용해 주세요.")
                    return@launch
                }
                if (context.currentActivity == null || context.lifecycleState != com.facebook.react.common.LifecycleState.RESUMED) {
                    promise.reject("SAMSUNG_BACKGROUND", "앱을 열어 두면 건강 기록 동기화를 이어갈 수 있어요.")
                    return@launch
                }
                val reader = SamsungHealthReader(store)
                val type = requireNotNull(options.getString("dataType"))
                val result = if (type == "activity") {
                    reader.activity(LocalDate.parse(options.getString("from")), LocalDate.parse(options.getString("to")),
                        Instant.parse(options.getString("cutoff")))
                } else {
                    reader.page(type, Instant.parse(options.getString("from")), Instant.parse(options.getString("to")),
                        options.getBoolean("changes"), if (options.hasKey("pageToken")) options.getString("pageToken") else null)
                }
                promise.resolve(result)
            } catch (error: CancellationException) {
                promise.reject("SAMSUNG_CANCELLED", "건강 기록 읽기가 취소되었어요.")
                throw error
            } catch (error: Exception) { reject(error, promise) }
        }
    }

''' + marker)
s = s.replace('import java.time.LocalDate\n', 'import java.time.LocalDate\nimport com.facebook.react.common.LifecycleState\n')
s = s.replace('com.facebook.react.common.LifecycleState.RESUMED', 'LifecycleState.RESUMED')
p.write_text(s, encoding='utf-8')
