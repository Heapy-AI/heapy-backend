# 작성자: 김진우 — 원본 SVG 경로를 변형하지 않고 React Native SVG로 옮긴다.
from pathlib import Path
import re, json, xml.etree.ElementTree as ET
p=Path('C:/Users/jinwo/heapy-frontend/src/features/home/HomeDashboard.tsx')
s=p.read_text(encoding='utf-8').replace("import { SvgXml } from 'react-native-svg';", "import Svg, { Path } from 'react-native-svg';")
s=re.sub(r'const settingsIcon = .*?;\n', '', s)
root=ET.parse('C:/Users/jinwo/heapy-backend/.tmp/home-chat-ui/settings.svg').getroot()
paths=''.join('<Path d='+json.dumps(e.attrib['d'])+' stroke="#434343" strokeWidth={1.13333} fill="none" />' for e in root.iter() if e.tag.endswith('path'))
s=s.replace('<SvgXml xml={configurable ? settingsIcon : \'<svg xmlns="http://www.w3.org/2000/svg"/>\'} width={16} height={16} />', '{configurable && <Svg width={16} height={16} viewBox="0 0 16 16">'+paths+'</Svg>}')
p.write_text(s,encoding='utf-8')
