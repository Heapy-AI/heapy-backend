from pathlib import Path
root=Path('C:/Users/jinwo/heapy-frontend')
p=root/'android/app/src/main/java/com/heapy/app/SamsungHealthModule.kt';s=p.read_text(encoding='utf-8')
s=s.replace('private fun reject(error: Exception, promise: Promise) {', 'private fun reject(error: Exception, promise: Promise, resolve: Boolean = true) {').replace('if (error is ResolvablePlatformException && error.hasResolution)', 'if (resolve && error is ResolvablePlatformException && error.hasResolution)')
idx=s.index('    fun getReadPermissions')
s=s[:idx]+s[idx:].replace('reject(error, promise)', 'reject(error, promise, false)')
p.write_text(s,encoding='utf-8')
p=root/'src/features/health/HealthScreen.tsx';s=p.read_text(encoding='utf-8')
old='''      setSyncNotice(result.message);
    } catch (error) {
      setSyncError('''
assert old in s
s=s.replace(old,'''      setSyncNotice(result.message);
    } catch (error) {
      setSyncNotice('');
      setSyncError(''')
s=s.replace("await syncSamsungHealth({ automatic: true });", "await syncSamsungHealth({ automatic: true });\n        if (mounted) setSyncError('');")
p.write_text(s,encoding='utf-8')
