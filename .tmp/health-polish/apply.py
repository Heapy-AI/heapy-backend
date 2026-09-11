from pathlib import Path
p=Path('C:/Users/jinwo/heapy-frontend')
stage=Path('C:/Users/jinwo/heapy-backend/.tmp/health-polish')
for name in ['HealthChart.tsx','MetricIcon.tsx','chartGeometry.ts','useHealthMotion.ts','healthRefresh.ts']:
    (p/'src/features/health'/name).write_text((stage/name).read_text(encoding='utf-8'),encoding='utf-8')
f=p/'src/features/health/HealthScreen.tsx';s=f.read_text(encoding='utf-8').replace('  NativeModules,\n','')
s=s.replace("import { HealthIcon }", "import { Animated } from 'react-native';\nimport { AmbientEffect } from '../../shared/components/AmbientEffect';\nimport { MetricIcon } from './MetricIcon';\nimport { useHealthMotion } from './useHealthMotion';\nimport { refreshSamsungConnection } from './healthRefresh';\nimport { HealthIcon }")
s=s.replace("function AnalysisCard({ category }: { category: Category })", "function AnalysisCard({ category, active = true }: { category: Category; active?: boolean })")
s=s.replace("style={{ borderRadius: 24, padding: 18, gap: 10, minHeight: 125 }}", "style={{ borderRadius: 26, padding: 22, gap: 12, minHeight: 145, overflow: 'hidden' }}")
s=s.replace('''      <Text style={[hs.muted, hs.white]}>
        HEAPY AI''','''      <AmbientEffect active={active} testID="health-analysis-wave" />
      <Text style={[hs.muted, hs.white, {fontSize: 10, letterSpacing: .5}]}>
        HEAPY AI''',1)
s=s.replace('<AnalysisCard category="checkup" />','<AnalysisCard category="checkup" active={active} />').replace('<AnalysisCard category={category} />','<AnalysisCard category={category} active={active} />')
start=s.index('  return (\n    <View style={hs.metric}>',s.index('function MetricCard'))
end=s.index('\n}\n\nexport function HealthScreen',start)
s=s[:start]+'''  const motion = useHealthMotion(label + field);
  return (
    <Animated.View testID={'health-metric-' + field} style={[hs.metric, {opacity: motion.interpolate({inputRange:[0,1],outputRange:[.4,1]}), transform:[{translateY:motion.interpolate({inputRange:[0,1],outputRange:[12,0]})}]}]}>
      <View style={hs.between}>
        <View style={[hs.metricIcon, {backgroundColor: color + '12'}]}><MetricIcon field={field} color={color}/></View>
        <Text style={hs.metricLabel}>{label}</Text>
      </View>
      <View style={{flexDirection:'row',alignItems:'baseline',gap:5,flexWrap:'wrap'}}>
        <Text style={[hs.value, {color}]}>{format(value)}</Text><Text style={hs.metricUnit}>{unit}</Text>
      </View>
      <Text style={hs.metricDate}>{today ? koreanDay() : record?.date ?? '기록 없음'}</Text>
    </Animated.View>
  );'''+s[end:]
s=s.replace("    [syncError, setSyncError] = useState('');", "    [syncError, setSyncError] = useState(''),\n    [syncNotice, setSyncNotice] = useState('');")
start=s.index('  // 작성자: 김진우 — 읽기 갱신과 삼성 전송 성공')
end=s.index('\n  useEffect(() => {',start)
s=s[:start]+'''  // 작성자: 김진우 — 마이탭과 같은 연결 정보를 조회하고 실제 읽기 지원 범위만 안내한다.
  const refresh = async () => {
    if (refreshing) return;
    setRefreshing(true); setSyncError(''); setSyncNotice('');
    try {
      const result = await refreshSamsungConnection();
      setSyncNotice(result.message);
    } catch (error) {
      setSyncError(error instanceof Error ? error.message : '삼성헬스 연결 상태를 확인하지 못했어요.');
    } finally {
      await Promise.all([
        client.invalidateQueries({queryKey:['health']}),
        client.invalidateQueries({queryKey:['health-connections']}),
      ]);
      setRefreshing(false);
    }
  };'''+s[end:]
s=s.replace('''      {!!syncError && (''','''      {!!syncNotice && <Text accessibilityLiveRegion="polite" style={hs.syncNotice}>{syncNotice}</Text>}
      {!!syncError && (''',1)
needle="""        {route !== 'entries' && (
          <Pressable"""
s=s.replace(needle,"""        {route !== 'entries' && (
          <View style={hs.row}>
          <Pressable accessibilityRole="button" accessibilityLabel="건강 기록 새로고침" disabled={refreshing} onPress={refresh} style={{padding:6}}><Text style={{fontSize:22,color:'#769B94'}}>{refreshing ? '⋯' : '↻'}</Text></Pressable>
          <Pressable""",1)
needle='''            <Text style={[hs.pillText, hs.white]}>+ 수치 입력</Text>
          </Pressable>
        )}'''
s=s.replace(needle,'''            <Text style={[hs.pillText, hs.white]}>+ 수치 입력</Text>
          </Pressable>
          </View>
        )}''',1)
f.write_text(s,encoding='utf-8')
f=p/'src/features/health/healthStyles.ts';s=f.read_text(encoding='utf-8')
s=s.replace('    padding: 12,\n    borderRadius: 20,','    padding: 16,\n    borderRadius: 24,')
s=s.replace('  value: { fontSize: 22,',"  metricIcon: {width: 40, height:40, borderRadius:14, alignItems:'center',justifyContent:'center'},\n  metricLabel: {fontSize:11,color:'#77918F',fontWeight:'600',flexShrink:1,textAlign:'right'},\n  metricUnit: {fontSize:11,color:'#93A6A3',fontWeight:'500'},\n  metricDate: {fontSize:9,color:'#A0B0AD',letterSpacing:.2},\n  syncNotice: {fontSize:11,lineHeight:17,color:'#72948E',paddingHorizontal:4},\n  value: { fontSize: 28,")
s=s.replace('    gap: 8,\n  },\n  metricIcon',"    gap: 12,\n    borderWidth:1,\n    borderColor:'#EDF4F1',\n    boxShadow:'0 5px 20px rgba(33,83,67,0.035)',\n  },\n  metricIcon")
f.write_text(s,encoding='utf-8')
f=p/'src/features/dataConnection/DataConnectionScreen.tsx';s=f.read_text(encoding='utf-8')
s=s.replace('''        {todaySteps && (''','''        <Text style={s.description}>
          현재 연결 확인은 읽기 권한과 오늘 걸음 수를 확인해요. 내 건강의 전체 기록을 서버로 전송하는 기능은 아직 지원하지 않아요.
        </Text>
        {todaySteps && (''',1)
f.write_text(s,encoding='utf-8')
