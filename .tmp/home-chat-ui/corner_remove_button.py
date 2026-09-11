# 작성자: 김진우 — 카드 왼쪽 위의 마이너스 버튼으로 홈 표시를 해제한다.
from pathlib import Path
p=Path('C:/Users/jinwo/heapy-frontend/src/features/home/HomeDashboard.tsx')
s=p.read_text(encoding='utf-8')
s=s.replace("configurable ? '설정' : '선택 해제'", "configurable ? '설정' : '선택됨'")
s=s.replace('onPress={configurable ? onSettings : onRemove}', 'disabled={!configurable}\n        onPress={configurable ? onSettings : undefined}')
s=s.replace('<Text style={s.removeLabel}>숨기기</Text>', '<Text style={s.removeLabel}>−</Text>')
s=s.replace('톱니바퀴로 설정하고, 숨기기로 빼고, 손잡이로 순서를 바꿔요.', '톱니바퀴로 설정하고, −로 빼고, 손잡이로 순서를 바꿔요.')
start=s.index('  selectedRow: {');end=s.index('  circle:', start)
s=s[:start]+s[start:end].replace('paddingLeft: 10', 'paddingLeft: 32')+s[end:]
start=s.index('  removeButton: {');end=s.index('  drag: {',start)
s=s[:start]+'''  removeButton: {
    position: 'absolute',
    left: 2,
    top: 2,
    width: 28,
    height: 28,
    zIndex: 2,
    alignItems: 'center',
    justifyContent: 'center',
  },
  removeLabel: {
    width: 22,
    height: 22,
    borderRadius: 11,
    backgroundColor: '#E15B5B',
    color: '#FFFFFF',
    fontSize: 21,
    lineHeight: 22,
    fontWeight: '600',
    textAlign: 'center',
    includeFontPadding: false,
  },
'''+s[end:]
p.write_text(s,encoding='utf-8')
