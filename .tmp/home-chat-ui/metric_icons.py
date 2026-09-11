# 작성자: 김진우 — Figma에서 내보낸 아이콘 경로를 그대로 재사용한다.
from pathlib import Path
import xml.etree.ElementTree as ET
import json
p=Path('C:/Users/jinwo/heapy-frontend/src/features/home/HomeDashboard.tsx');s=p.read_text(encoding='utf-8')
icons={}
for name in ['sleep','steps']:
    paths=[x.attrib for x in ET.parse(f'C:/Users/jinwo/heapy-backend/.tmp/home-chat-ui/{name}.svg').getroot().iter() if x.tag.endswith('path')]
    icons[name]=paths[0]
source='const metricIcons = '+json.dumps(icons)+' as const;\n'
s=s.replace('function MetricCards(',source+'function MetricCards(')
s=s.replace('<Text style={s.small}>{metrics[id][0]}</Text>', '''<View style={s.row}><Text style={s.small}>{metrics[id][0]}</Text>{(id === 'sleep' || id === 'steps') && <View style={{ backgroundColor: 'white', padding: 6, borderRadius: 16 }}><Svg width={17} height={17} viewBox="0 0 17 17"><Path d={metricIcons[id].d} stroke={metricIcons[id].stroke} strokeWidth={Number(metricIcons[id]['stroke-width'])} strokeLinecap="round" strokeLinejoin="round" fill="none" /></Svg></View>}</View>''')
p.write_text(s,encoding='utf-8')
