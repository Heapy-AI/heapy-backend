from pathlib import Path
p=Path('C:/Users/jinwo/heapy-frontend/src/features/health/HealthScreen.tsx')
s=p.read_text(encoding='utf-8')
needle='''      <Text style={[hs.muted, hs.white]}>
        {valid
          ?'''
replacement='''      {valid && category === 'checkup' && !!data.report?.overall_analysis && (
        <Text style={[hs.text, hs.white]}>{data.report.overall_analysis}</Text>
      )}
      {valid && (data.report?.actions ?? data.report?.recommendations ?? []).map((action, index) => (
        <Text key={index} style={[hs.text, hs.white]}>• {action}</Text>
      ))}
      <Text style={[hs.muted, hs.white]}>
        {valid
          ?'''
assert needle in s
p.write_text(s.replace(needle,replacement,1),encoding='utf-8')
